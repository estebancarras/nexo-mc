package los5fantasticos.minigameCadena.services

import los5fantasticos.minigameCadena.MinigameCadena
import los5fantasticos.minigameCadena.game.CadenaGame
import los5fantasticos.minigameCadena.game.GameState
import los5fantasticos.minigameCadena.game.Team
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team as BukkitTeam
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestiona todas las partidas activas del minijuego Cadena.
 * 
 * Responsabilidades:
 * - Crear y gestionar partidas
 * - Asignar jugadores a equipos
 * - Controlar el ciclo de vida de las partidas
 * - Mantener el estado global del juego
 */
class GameManager(
    private val minigame: MinigameCadena? = null,
    private val chainVisualizerService: ChainVisualizerService? = null
) {
    
    /**
     * Mapa de partidas activas por ID.
     */
    private val activeGames = ConcurrentHashMap<UUID, CadenaGame>()
    
    /**
     * Scoreboard temporal para gestionar las auras de equipo durante la partida.
     */
    private var gameScoreboard: Scoreboard? = null
    
    /**
     * Tarea del game loop para detección de regiones.
     */
    private var gameTask: org.bukkit.scheduler.BukkitTask? = null
    
    /**
     * Mapa de jugadores a partidas (para búsqueda rápida).
     */
    private val playerToGame = ConcurrentHashMap<UUID, UUID>()
    
    /**
     * Partida en lobby esperando jugadores.
     * Solo puede haber una partida en lobby a la vez.
     */
    private var lobbyGame: CadenaGame? = null
    
    /**
     * Configuración: Número mínimo de jugadores para iniciar.
     */
    var minPlayers: Int = 2
        private set
    
    /**
     * Configuración: Número máximo de jugadores por partida.
     */
    var maxPlayers: Int = 8
        private set
    
    /**
     * Configuración: Tamaño máximo de equipo.
     */
    var maxTeamSize: Int = 4
        private set
    
    /**
     * Añade un jugador a una partida.
     * Si no hay partida en lobby, crea una nueva.
     * 
     * @param player Jugador a añadir
     * @return true si se añadió exitosamente, false si ya está en una partida
     */
    fun addPlayer(player: Player): Boolean {
        // Verificar si el jugador ya está en una partida
        if (playerToGame.containsKey(player.uniqueId)) {
            return false
        }
        
        // Obtener o crear partida en lobby
        val game = lobbyGame ?: createLobbyGame()
        
        // Verificar si la partida está llena
        if (game.getTotalPlayers() >= maxPlayers) {
            // Crear nueva partida si la actual está llena
            val newGame = createLobbyGame()
            val result = addPlayerToGame(player, newGame)
            
            // Verificar si debe iniciar cuenta regresiva
            if (result) {
                minigame?.checkStartCountdown(newGame)
            }
            
            return result
        }
        
        val result = addPlayerToGame(player, game)
        
        // Verificar si debe iniciar cuenta regresiva
        if (result) {
            minigame?.checkStartCountdown(game)
        }
        
        return result
    }
    
    /**
     * Añade un jugador a una partida específica.
     * El jugador NO es añadido automáticamente a un equipo - debe elegir uno usando la UI.
     * Se encarga de teletransportar al jugador al lobby y entregarle la UI de selección.
     */
    private fun addPlayerToGame(player: Player, game: CadenaGame): Boolean {
        // Verificar si hay espacio en la partida (máximo 40 jugadores = 10 equipos × 4 jugadores)
        if (game.getTotalPlayers() >= 40) {
            return false
        }
        
        // Registrar jugador en la partida (SIN añadirlo a un equipo todavía)
        playerToGame[player.uniqueId] = game.id
        
        // Teletransportar al lobby (si está configurado)
        minigame?.arenaManager?.getLobbyLocation()?.let { lobbyLocation ->
            player.teleport(lobbyLocation)
        }
        
        // Limpiar inventario y entregar UI de selección de equipos
        player.inventory.clear()
        giveTeamSelectionItems(player, game)
        
        // Notificar al jugador
        player.sendMessage("${ChatColor.GREEN}¡Bienvenido al lobby de Cadena!")
        player.sendMessage("${ChatColor.YELLOW}Selecciona tu equipo haciendo clic en una lana de color.")
        
        return true
    }
    
    /**
     * Crea una nueva partida en estado LOBBY.
     */
    private fun createLobbyGame(): CadenaGame {
        val game = CadenaGame()
        
        // Asignar arena aleatoria si hay arenas disponibles
        minigame?.arenaManager?.getRandomArena()?.let { arena ->
            game.arena = arena
        }
        
        activeGames[game.id] = game
        lobbyGame = game
        return game
    }
    
    /**
     * Remueve un jugador de su partida actual.
     * 
     * @param player Jugador a remover
     * @return true si se removió exitosamente
     */
    fun removePlayer(player: Player): Boolean {
        val gameId = playerToGame.remove(player.uniqueId) ?: return false
        val game = activeGames[gameId] ?: return false
        
        // Destruir cadenas visuales del jugador si la partida está en curso
        if (game.state == GameState.IN_GAME) {
            chainVisualizerService?.destroyChainsForPlayer(player)
        }
        
        // Remover del equipo
        game.teams.forEach { team ->
            team.removePlayer(player)
        }
        
        // Limpiar equipos vacíos
        game.teams.removeIf { it.players.isEmpty() }
        
        // Si la partida está en lobby y se quedó vacía, eliminarla
        if (game.state == GameState.LOBBY && game.getTotalPlayers() == 0) {
            activeGames.remove(gameId)
            if (lobbyGame?.id == gameId) {
                lobbyGame = null
            }
        }
        
        return true
    }
    
    /**
     * Obtiene la partida en la que está un jugador.
     */
    fun getPlayerGame(player: Player): CadenaGame? {
        val gameId = playerToGame[player.uniqueId] ?: return null
        return activeGames[gameId]
    }
    
    /**
     * Obtiene el equipo de un jugador.
     */
    fun getPlayerTeam(player: Player): Team? {
        val game = getPlayerGame(player) ?: return null
        return game.teams.find { it.players.contains(player.uniqueId) }
    }
    
    /**
     * Inicia una partida (transición de LOBBY a COUNTDOWN).
     * 
     * @param game Partida a iniciar
     * @return true si se inició exitosamente
     */
    fun startGame(game: CadenaGame): Boolean {
        if (game.state != GameState.LOBBY) {
            return false
        }
        
        if (!game.hasMinimumPlayers()) {
            return false
        }
        
        game.state = GameState.COUNTDOWN
        
        // Si esta era la partida en lobby, limpiar referencia
        if (lobbyGame?.id == game.id) {
            lobbyGame = null
        }
        
        return true
    }
    
    /**
     * Finaliza una partida y limpia sus recursos.
     * 
     * @param game Partida a finalizar
     */
    fun endGame(game: CadenaGame) {
        game.state = GameState.FINISHED
        
        // Detener temporizador visual (BossBar)
        game.gameTimer?.stop()
        game.gameTimer = null
        
        // Limpiar todas las cadenas visuales de la partida
        chainVisualizerService?.clearAllChains()
        
        // PR3: Detener encadenamiento
        minigame?.chainService?.stopChaining(game)
        
        // --- INICIO: Limpieza de Game Loop y Auras ---
        // Detener game loop
        gameTask?.cancel()
        gameTask = null
        
        // Limpiar progreso de parkour
        minigame?.parkourService?.clearAllProgress()
        
        // Detener tarea de mantenimiento del scoreboard
        minigame?.lobbyManager?.clearAll()
        cleanupTeamGlowEffects(game)
        // --- FIN: Limpieza de Game Loop y Auras ---
        
        // Ocultar scoreboard dedicado y restaurar scoreboard global
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                minigame?.cadenaScoreboardService?.hideScoreboard(player)
            }
        }
        
        // Teletransportar jugadores al lobby global y limpiar
        game.teams.forEach { team ->
            team.players.forEach { playerId ->
                // Obtener el jugador online
                val player = org.bukkit.Bukkit.getPlayer(playerId)
                if (player != null && player.isOnline) {
                    // Restaurar modo de juego a supervivencia
                    player.gameMode = org.bukkit.GameMode.SURVIVAL
                    
                    // Limpiar inventario (eliminar lanas y cualquier item del juego)
                    player.inventory.clear()
                    
                    // Retornar al lobby global usando TournamentFlowManager
                    los5fantasticos.torneo.services.TournamentFlowManager.returnToLobby(player)
                }
                playerToGame.remove(playerId)
            }
        }
        
        // Remover de activas (pero NO limpiar la arena asignada)
        activeGames.remove(game.id)
        
        // Limpiar servicios
        minigame?.scoreService?.clearGame(game.id.toString())
        
        // Si era la partida de lobby, limpiar referencia
        if (lobbyGame?.id == game.id) {
            lobbyGame = null
        }
    }
    
    /**
     * Obtiene todas las partidas activas.
     */
    fun getActiveGames(): List<CadenaGame> {
        return activeGames.values.toList()
    }
    
    /**
     * Obtiene la partida actual en lobby.
     */
    fun getLobbyGame(): CadenaGame? {
        return lobbyGame
    }
    
    /**
     * Verifica si un jugador está en una partida.
     */
    fun isPlayerInGame(player: Player): Boolean {
        return playerToGame.containsKey(player.uniqueId)
    }
    
    /**
     * Limpia todas las partidas (para deshabilitación del plugin).
     */
    fun clearAll() {
        activeGames.clear()
        playerToGame.clear()
        lobbyGame = null
        gameScoreboard = null
    }
    
    /**
     * Establece el scoreboard temporal para la partida actual.
     */
    fun setGameScoreboard(scoreboard: Scoreboard) {
        this.gameScoreboard = scoreboard
    }
    
    /**
     * Obtiene el scoreboard temporal de la partida actual.
     */
    fun getGameScoreboard(): Scoreboard? {
        return gameScoreboard
    }
    
    /**
     * Inicia el game loop para detección de regiones.
     */
    fun startGameLoop(game: CadenaGame) {
        // Detener tarea anterior si existe
        gameTask?.cancel()
        
        // Resetear progreso de todos los jugadores
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                minigame?.parkourService?.resetProgress(player)
            }
        }
        
        // Iniciar el game loop
        gameTask = object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                // Verificar si la partida sigue activa
                if (game.state != GameState.IN_GAME) {
                    cancel()
                    return
                }
                
                val arena = game.arena ?: return
                
                // Procesar cada jugador vivo
                game.teams.forEach { team ->
                    team.getOnlinePlayers().forEach { player ->
                        val location = player.location
                        
                        // 0. Comprobar caída (CRÍTICO: debe ir primero)
                        minigame?.parkourService?.checkFall(player, game)
                        
                        // 1. Comprobar la Meta
                        val metaRegion = arena.meta
                        if (metaRegion != null && metaRegion.contains(location)) {
                            if (minigame?.parkourService?.hasCompletedAllCheckpoints(player, arena) == true) {
                                // ¡JUGADOR GANA!
                                minigame?.parkourService?.handlePlayerWin(player, game)
                                return // Salir del bucle, la partida ha terminado
                            }
                        }
                        
                        // 2. Comprobar el siguiente Checkpoint
                        val nextIndex = minigame?.parkourService?.getNextCheckpointIndex(player) ?: 0
                        val nextCheckpointRegion = arena.checkpoints.getOrNull(nextIndex)
                        if (nextCheckpointRegion != null && nextCheckpointRegion.contains(location)) {
                            minigame?.parkourService?.handleCheckpointReached(player, nextIndex)
                        }
                    }
                }
            }
        }.runTaskTimer(minigame?.plugin ?: return, 0L, 10L) // Cada 10 ticks (0.5 segundos)
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Game loop iniciado para detección de regiones")
    }
    
    /**
     * Limpia los efectos de brillo y restaura el scoreboard principal.
     */
    private fun cleanupTeamGlowEffects(game: CadenaGame) {
        val mainScoreboard = Bukkit.getScoreboardManager()?.mainScoreboard
        
        if (mainScoreboard == null) {
            minigame?.plugin?.logger?.severe("[${minigame?.gameName}] No se pudo obtener el scoreboard principal para limpieza")
            return
        }
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Limpiando auras de equipo...")
        
        // Iterar sobre TODOS los jugadores que participaron
        game.teams.forEach { team ->
            team.players.forEach { uuid ->
                Bukkit.getPlayer(uuid)?.let { player ->
                    // Desactivar el efecto de brillo
                    player.isGlowing = false
                    
                    // Restaurar el GlobalScoreboardService
                    minigame?.torneoPlugin?.scoreboardService?.showScoreboard(player)
                }
            }
        }
        
        // Desregistrar los equipos de Bukkit para liberar memoria
        gameScoreboard?.teams?.forEach { bukkitTeam ->
            try {
                bukkitTeam.unregister()
            } catch (e: Exception) {
                minigame?.plugin?.logger?.warning("[${minigame?.gameName}] Error desregistrando equipo ${bukkitTeam.name}: ${e.message}")
            }
        }
        
        // Limpiar la referencia
        gameScoreboard = null
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] ✓ Auras de equipo limpiadas")
    }
    
    /**
     * Asigna automáticamente jugadores sin equipo a equipos existentes.
     * Prioriza equipos con 2+ miembros y distribuye equitativamente.
     * 
     * @param players Lista de todos los jugadores que deben estar en la partida
     * @param game Partida donde asignar los jugadores
     */
    fun assignUnassignedPlayers(players: List<Player>, game: CadenaGame) {
        // 1. Obtener lista mutable de equipos
        val teams = game.teams
        
        // 2. Identificar jugadores ya asignados
        val assignedPlayersUUIDs = teams.flatMap { it.players }.toSet()
        
        // 3. Encontrar jugadores no asignados
        val unassignedPlayers = players.filter { it.uniqueId !in assignedPlayersUUIDs }
        
        if (unassignedPlayers.isEmpty()) {
            minigame?.plugin?.logger?.info("[${minigame?.gameName}] Todos los jugadores ya tienen equipo asignado")
            return
        }
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Asignando automáticamente ${unassignedPlayers.size} jugadores sin equipo")
        
        // 4. Caso de borde: Si no hay equipos con jugadores, crear uno por defecto
        if (teams.all { it.players.isEmpty() }) {
            minigame?.plugin?.logger?.info("[${minigame?.gameName}] No hay equipos con jugadores, usando equipos predefinidos")
        }
        
        // 5. Asignar cada jugador no asignado
        for (player in unassignedPlayers) {
            // REGLA: Priorizar equipos con 2 o más miembros, y de ellos, el más pequeño
            val preferredTeam = teams
                .filter { it.players.size >= 2 && it.players.size < 4 }
                .minByOrNull { it.players.size }
            
            // FALLBACK: Si no hay equipos "preferidos", buscar el equipo más pequeño (incluyendo equipos de 0 o 1)
            val targetTeam = preferredTeam ?: teams
                .filter { it.players.size < 4 }
                .minByOrNull { it.players.size }
            
            // Asignar al jugador y notificarle
            if (targetTeam != null) {
                targetTeam.players.add(player.uniqueId)
                player.sendMessage("${ChatColor.GREEN}¡Has sido asignado automáticamente al ${targetTeam.displayName}${ChatColor.GREEN}!")
                minigame?.plugin?.logger?.info("[${minigame?.gameName}] ${player.name} asignado a ${targetTeam.teamId}")
            } else {
                // Este caso no debería ocurrir si hay equipos predefinidos
                minigame?.plugin?.logger?.severe("[${minigame?.gameName}] No se pudo asignar equipo a ${player.name} - todos los equipos están llenos")
                player.sendMessage("${ChatColor.RED}Error: No hay equipos disponibles. Contacta a un administrador.")
            }
        }
        
        // 6. Actualizar inventarios de todos los jugadores en el lobby
        updateAllLobbyInventories(game)
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] ✓ Asignación automática completada")
    }
    
    /**
     * Asigna automáticamente jugadores sin equipo y redistribuye jugadores solitarios.
     * Asegura que nadie quede solo en un equipo.
     * 
     * @param game Partida donde asignar los jugadores
     */
    fun assignUnassignedPlayersInLobby(game: CadenaGame) {
        // Obtener todos los jugadores que están registrados en esta partida
        val playersInGame = playerToGame.filter { it.value == game.id }
            .mapNotNull { org.bukkit.Bukkit.getPlayer(it.key) }
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Iniciando asignación inteligente para ${playersInGame.size} jugadores")
        
        // FASE 1: Asignar jugadores sin equipo
        assignUnassignedPlayers(playersInGame, game)
        
        // FASE 2: Redistribuir jugadores solitarios
        redistributeSoloPlayers(game)
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] ✓ Asignación inteligente completada")
    }
    
    /**
     * Redistribuye jugadores que están solos en sus equipos.
     * Los mueve a equipos que tengan 1, 2 o 3 jugadores para que nadie quede solo.
     * 
     * @param game Partida donde redistribuir jugadores
     */
    private fun redistributeSoloPlayers(game: CadenaGame) {
        val teams = game.teams
        
        // Encontrar equipos con solo 1 jugador
        val soloTeams = teams.filter { it.players.size == 1 }
        
        if (soloTeams.isEmpty()) {
            minigame?.plugin?.logger?.info("[${minigame?.gameName}] No hay jugadores solitarios que redistribuir")
            return
        }
        
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Redistribuyendo ${soloTeams.size} jugadores solitarios")
        
        for (soloTeam in soloTeams) {
            val soloPlayerUUID = soloTeam.players.first()
            val soloPlayer = org.bukkit.Bukkit.getPlayer(soloPlayerUUID) ?: continue
            
            // Buscar el mejor equipo para este jugador solitario
            // PRIORIDAD: Equipos con 1 jugador (para formar parejas), luego 2, luego 3
            val targetTeam = teams
                .filter { it != soloTeam && it.players.size in 1..3 } // Excluir el equipo actual y equipos llenos
                .sortedBy { it.players.size } // Priorizar equipos más pequeños
                .firstOrNull()
            
            if (targetTeam != null) {
                // Mover el jugador del equipo solitario al equipo objetivo
                soloTeam.players.remove(soloPlayerUUID)
                targetTeam.players.add(soloPlayerUUID)
                
                // Notificar al jugador
                soloPlayer.sendMessage("${ChatColor.YELLOW}Has sido transferido al ${targetTeam.displayName}${ChatColor.YELLOW} para formar equipo!")
                
                minigame?.plugin?.logger?.info("[${minigame?.gameName}] ${soloPlayer.name} transferido de ${soloTeam.teamId} a ${targetTeam.teamId}")
            } else {
                // Si no hay equipos disponibles, intentar fusionar con otro jugador solitario
                val anotherSoloTeam = soloTeams.find { it != soloTeam && it.players.size == 1 }
                if (anotherSoloTeam != null) {
                    // Mover el jugador al equipo del otro solitario
                    soloTeam.players.remove(soloPlayerUUID)
                    anotherSoloTeam.players.add(soloPlayerUUID)
                    
                    soloPlayer.sendMessage("${ChatColor.YELLOW}Has sido transferido al ${anotherSoloTeam.displayName}${ChatColor.YELLOW} para formar equipo!")
                    
                    minigame?.plugin?.logger?.info("[${minigame?.gameName}] ${soloPlayer.name} fusionado con otro jugador solitario en ${anotherSoloTeam.teamId}")
                }
            }
        }
        
        // Actualizar inventarios después de la redistribución
        updateAllLobbyInventories(game)
        
        // Log final del estado de equipos
        val finalTeamSizes = teams.filter { it.players.isNotEmpty() }.map { "${it.teamId}:${it.players.size}" }
        minigame?.plugin?.logger?.info("[${minigame?.gameName}] Estado final de equipos: ${finalTeamSizes.joinToString(", ")}")
    }
    
    // ===== Funciones de UI del Lobby =====
    
    /**
     * Entrega los ítems de selección de equipo al jugador.
     * Cada ítem es una lana del color del equipo con información dinámica.
     */
    private fun giveTeamSelectionItems(player: Player, game: CadenaGame) {
        // Crear ítem para cada equipo
        game.teams.forEachIndexed { index, team ->
            val item = ItemStack(team.material)
            val meta = item.itemMeta
            
            // Nombre del equipo
            meta?.setDisplayName(team.displayName)
            
            // Lore con información de jugadores
            val playerCount = team.players.size
            val maxPlayers = 4
            val lore = mutableListOf<String>()
            
            lore.add("${ChatColor.GRAY}Jugadores: ${ChatColor.WHITE}$playerCount / $maxPlayers")
            lore.add("")
            
            if (team.players.isEmpty()) {
                lore.add("${ChatColor.YELLOW}¡Sé el primero en unirte!")
            } else {
                lore.add("${ChatColor.GRAY}Miembros:")
                team.getOnlinePlayers().forEach { p ->
                    lore.add("${ChatColor.WHITE}  • ${p.name}")
                }
            }
            
            lore.add("")
            if (team.isFull()) {
                lore.add("${ChatColor.RED}¡Equipo completo!")
            } else {
                lore.add("${ChatColor.GREEN}Click para unirte")
            }
            
            meta?.lore = lore
            item.itemMeta = meta
            
            // Colocar en el inventario (slots 0-8 para primera fila, 9-17 para segunda fila)
            // Distribuir los 10 equipos: primeros 9 en la primera fila, el décimo en la segunda
            val slot = if (index < 9) index else 9
            player.inventory.setItem(slot, item)
        }
    }
    
    /**
     * Actualiza la UI del inventario para todos los jugadores en el lobby.
     * Debe ser llamado cada vez que un jugador cambia de equipo.
     */
    fun updateAllLobbyInventories(game: CadenaGame) {
        if (game.state != GameState.LOBBY) {
            return
        }
        
        // Obtener todos los jugadores de esta partida (incluyendo los que no han elegido equipo)
        val playersInGame = playerToGame.filter { it.value == game.id }
            .mapNotNull { org.bukkit.Bukkit.getPlayer(it.key) }
        
        // Actualizar inventario de cada jugador
        playersInGame.forEach { player ->
            player.inventory.clear()
            giveTeamSelectionItems(player, game)
        }
    }
}
