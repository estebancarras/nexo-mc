package cl.esteban.nexo.services

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.game.CadenaGame
import cl.esteban.nexo.game.GameState
import cl.esteban.nexo.game.Team
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Servicio que gestiona el encadenamiento lineal entre jugadores de un equipo.
 * 
 * Responsabilidades:
 * - Mantener a los jugadores conectados en cadena lineal (A-B-C-D)
 * - Aplicar fuerzas de arrastre solo entre jugadores adyacentes
 * - Gestionar los enlaces lógicos de la cadena
 * - Gestionar el encadenamiento durante la partida
 * 
 * Física:
 * - No usa teleport() para evitar rubber-banding
 * - Usa setVelocity() para un arrastre suave y natural
 * - La fuerza aumenta proporcionalmente a la distancia
 * - Reduce la fuerza si el jugador está en el aire (saltando)
 * - Solo aplica fuerzas entre jugadores adyacentes en la cadena
 */
class ChainService(private val minigame: NexoPlugin) {
    
    /**
     * Mapa de enlaces de cadena.
     * Cada jugador tiene una lista de UUIDs de jugadores con los que está directamente conectado.
     * En una cadena lineal A-B-C: A tiene [B], B tiene [A, C], C tiene [B]
     */
    private val playerLinks = mutableMapOf<UUID, MutableList<UUID>>()
    
    /**
     * Configuración: Distancia máxima permitida entre jugadores (en bloques).
     * Leído desde cadena.yml: fisica-cadena.distancia-maxima
     */
    internal val maxDistance: Double
        get() = minigame.config.getDouble("fisica-cadena.distancia-maxima", 6.0)
    
    /**
     * Configuración: Fuerza base del tirón (multiplicador de velocidad).
     * Leído desde cadena.yml: fisica-cadena.fuerza-atraccion
     */
    internal val pullStrength: Double
        get() = minigame.config.getDouble("fisica-cadena.fuerza-atraccion", 0.3)
    
    /**
     * Configuración: Reducción de fuerza cuando el jugador está en el aire.
     * Leído desde cadena.yml: fisica-cadena.reduccion-fuerza-aire
     */
    private val airReduction: Double
        get() = minigame.config.getDouble("fisica-cadena.reduccion-fuerza-aire", 0.5)
    
    /**
     * Configuración: Cada cuántos ticks se ejecuta la tarea.
     * Leído desde cadena.yml: fisica-cadena.intervalo-ticks
     */
    private val tickInterval: Long
        get() = minigame.config.getLong("fisica-cadena.intervalo-ticks", 3)
    
    /**
     * Tareas activas de encadenamiento por partida.
     */
    private val activeTasks = mutableMapOf<UUID, BukkitTask>()
    
    /**
     * Inicia el encadenamiento para una partida.
     * Crea los enlaces lógicos lineales entre jugadores de cada equipo.
     * 
     * @param game Partida para la cual activar el encadenamiento
     */
    fun startChaining(game: CadenaGame) {
        // Verificar que la partida esté en juego
        if (game.state != GameState.IN_GAME) {
            return
        }
        
        // Verificar si ya hay una tarea activa
        if (activeTasks.containsKey(game.id)) {
            return
        }
        
        // Crear los enlaces lógicos lineales
        createLinearChains(game.teams)
        
        // Crear tarea de encadenamiento
        val task = object : BukkitRunnable() {
            override fun run() {
                // Verificar si la partida sigue activa
                if (game.state != GameState.IN_GAME) {
                    cancel()
                    activeTasks.remove(game.id)
                    return
                }
                
                // Aplicar encadenamiento a cada equipo
                game.teams.forEach { team ->
                    applyChainPhysics(team)
                }
            }
        }
        
        // Ejecutar cada tickInterval ticks
        val bukkitTask = task.runTaskTimer(minigame, 0L, tickInterval)
        activeTasks[game.id] = bukkitTask
    }
    
    /**
     * Crea los enlaces lógicos lineales para todos los equipos.
     * 
     * @param teams Lista de equipos
     */
    private fun createLinearChains(teams: List<Team>) {
        // Limpiar enlaces anteriores
        playerLinks.clear()
        
        minigame.logger.info("[ChainService] Creando enlaces lineales para ${teams.size} equipos")
        
        for (team in teams) {
            // Convertir el Set de UUIDs a una lista de Players online
            val players = team.players.mapNotNull { org.bukkit.Bukkit.getPlayer(it) }
            
            // Si el equipo tiene menos de 2 jugadores, no se pueden formar cadenas
            if (players.size < 2) {
                minigame.logger.warning("[ChainService] Equipo ${team.teamId} tiene menos de 2 jugadores, saltando")
                continue
            }
            
            minigame.logger.info("[ChainService] Creando cadena lineal para equipo ${team.teamId} con ${players.size} jugadores")
            
            // --- BUCLE LINEAL ---
            // Itera hasta el penúltimo jugador para crear los enlaces
            for (i in 0 until players.size - 1) {
                val playerA = players[i]
                val playerB = players[i + 1] // Conecta al jugador [i] con el [i+1]
                
                // Almacenar los enlaces lógicos (bidireccionales)
                playerLinks.computeIfAbsent(playerA.uniqueId) { mutableListOf() }.add(playerB.uniqueId)
                playerLinks.computeIfAbsent(playerB.uniqueId) { mutableListOf() }.add(playerA.uniqueId)
                
                minigame.logger.info("[ChainService] Enlace creado: ${playerA.name} <-> ${playerB.name}")
            }
        }
        
        minigame.logger.info("[ChainService] ✓ Enlaces lineales creados. Total de jugadores enlazados: ${playerLinks.size}")
    }
    
    /**
     * Detiene el encadenamiento para una partida.
     * 
     * @param game Partida para la cual detener el encadenamiento
     */
    fun stopChaining(game: CadenaGame) {
        val task = activeTasks.remove(game.id)
        task?.cancel()
    }
    
    /**
     * Aplica la física de encadenamiento lineal a un equipo.
     * Solo aplica fuerzas entre jugadores directamente conectados en la cadena.
     * 
     * @param team Equipo al cual aplicar el encadenamiento
     */
    private fun applyChainPhysics(team: Team) {
        val players = team.getOnlinePlayers()
        
        // Si hay menos de 2 jugadores, no hay encadenamiento
        if (players.size < 2) {
            return
        }
        
        // Aplicar fuerza solo entre jugadores directamente enlazados
        for (player in players) {
            val linkedUUIDs = playerLinks[player.uniqueId] ?: continue
            
            // Para cada jugador enlazado, aplicar fuerza
            for (linkedUUID in linkedUUIDs) {
                val linkedPlayer = org.bukkit.Bukkit.getPlayer(linkedUUID) ?: continue
                
                // Solo aplicar la fuerza una vez por par (cuando player < linkedPlayer)
                if (player.uniqueId.compareTo(linkedUUID) < 0) {
                    applyPullForceBetweenPlayers(player, linkedPlayer)
                }
            }
        }
    }
    
    /**
     * Aplica una fuerza de tirón entre dos jugadores si están demasiado lejos.
     * 
     * @param player1 Primer jugador
     * @param player2 Segundo jugador
     */
    private fun applyPullForceBetweenPlayers(player1: Player, player2: Player) {
        val loc1 = player1.location
        val loc2 = player2.location
        
        // Calcular distancia entre los dos jugadores
        val distance = loc1.distance(loc2)
        
        // Si están dentro del rango permitido, no hacer nada
        if (distance <= maxDistance) {
            return
        }
        
        // Calcular la distancia excedida
        val excessDistance = distance - maxDistance
        
        // Calcular fuerza base
        val forceMagnitude = excessDistance * pullStrength
        
        // Aplicar fuerza a player1 hacia player2
        applyPullToPlayer(player1, loc2, forceMagnitude)
        
        // Aplicar fuerza a player2 hacia player1
        applyPullToPlayer(player2, loc1, forceMagnitude)
    }
    
    /**
     * Aplica una fuerza de tirón a un jugador hacia una ubicación.
     * 
     * @param player Jugador al cual aplicar la fuerza
     * @param targetLocation Ubicación objetivo
     * @param forceMagnitude Magnitud de la fuerza
     */
    private fun applyPullToPlayer(player: Player, targetLocation: Location, forceMagnitude: Double) {
        val playerLoc = player.location
        
        // Calcular vector de dirección hacia el objetivo
        val direction = targetLocation.toVector().subtract(playerLoc.toVector())
        
        // Normalizar el vector (longitud = 1)
        direction.normalize()
        
        // Aplicar el vector de fuerza
        val pullVector = direction.multiply(forceMagnitude)
        
        // Reducir la fuerza si el jugador está en el aire
        if (!player.isOnGround) {
            pullVector.multiply(airReduction)
        }
        
        // Aplicar la velocidad al jugador
        // Sumar a la velocidad actual para no interrumpir el movimiento
        val currentVelocity = player.velocity
        val newVelocity = currentVelocity.add(pullVector)
        
        // Limitar la velocidad máxima para evitar lanzamientos extremos
        val maxVelocity = 2.0
        if (newVelocity.length() > maxVelocity) {
            newVelocity.normalize().multiply(maxVelocity)
        }
        
        player.velocity = newVelocity
    }
    
    /**
     * Verifica si una partida tiene encadenamiento activo.
     */
    fun isChainActive(game: CadenaGame): Boolean {
        return activeTasks.containsKey(game.id)
    }
    
    /**
     * Verifica si un jugador está enlazado con otro.
     * Útil para lógica de daño entre compañeros de equipo.
     * 
     * @param playerA Primer jugador
     * @param playerB Segundo jugador
     * @return true si están directamente enlazados en la cadena
     */
    fun isLinked(playerA: Player, playerB: Player): Boolean {
        return playerLinks[playerA.uniqueId]?.contains(playerB.uniqueId) ?: false
    }
    
    /**
     * Verifica si la cadena de un jugador está rota.
     * Comprueba si alguno de sus enlaces directos está demasiado lejos.
     * 
     * @param player Jugador a verificar
     * @return true si algún enlace está roto
     */
    fun checkChainBreak(player: Player): Boolean {
        val links = playerLinks[player.uniqueId] ?: return false
        
        for (linkedUUID in links) {
            val linkedPlayer = org.bukkit.Bukkit.getPlayer(linkedUUID) ?: continue
            
            // Verificar si están en el mismo mundo
            if (player.world != linkedPlayer.world) {
                return true
            }
            
            // Verificar distancia
            val distance = player.location.distance(linkedPlayer.location)
            if (distance > maxDistance) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Limpia todas las tareas activas y enlaces.
     */
    fun clearAll() {
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        playerLinks.clear()
    }
}
