package cl.esteban.nexo.services

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.game.CadenaGame
import cl.esteban.nexo.game.GameState
import cl.esteban.nexo.game.Team
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
 * - Gestionar el flujo del juego
 */
class GameManager(private val minigame: NexoPlugin?) {
    
    /**
     * Mapa de partidas activas por ID.
     */
    private val games = ConcurrentHashMap<UUID, CadenaGame>()
    
    /**
     * Mapa de jugadores a partidas.
     * Key: UUID del jugador, Value: ID de la partida
     */
    private val playerToGame = ConcurrentHashMap<UUID, UUID>()
    
    /**
     * Obtiene la partida de un jugador.
     */
    fun getPlayerGame(player: Player): CadenaGame? {
        val gameId = playerToGame[player.uniqueId] ?: return null
        return games[gameId]
    }
    
    /**
     * Obtiene el equipo de un jugador en su partida actual.
     */
    fun getPlayerTeam(player: Player): Team? {
        val game = getPlayerGame(player) ?: return null
        return game.teams.find { team ->
            team.players.contains(player.uniqueId)
        }
    }
    
    /**
     * Verifica si un jugador está en una partida.
     */
    fun isPlayerInGame(player: Player): Boolean {
        return playerToGame.containsKey(player.uniqueId)
    }
    
    /**
     * Añade un jugador a una partida.
     */
    fun addPlayer(player: Player, game: CadenaGame) {
        playerToGame[player.uniqueId] = game.id
    }
    
    /**
     * Remueve un jugador de su partida actual.
     */
    fun removePlayer(player: Player) {
        val game = getPlayerGame(player)
        if (game != null) {
            // Remover del equipo
            game.teams.forEach { team ->
                team.players.remove(player.uniqueId)
            }
        }
        playerToGame.remove(player.uniqueId)
    }
    
    /**
     * Finaliza una partida.
     */
    fun endGame(game: CadenaGame) {
        // Remover todos los jugadores de la partida
        game.teams.forEach { team ->
            team.players.toList().forEach { playerUUID ->
                playerToGame.remove(playerUUID)
            }
            team.players.clear()
        }
        
        // Remover la partida
        games.remove(game.id)
        
        // Limpiar progreso del parkour
        minigame?.parkourService?.clearAllProgress()
        
        // Limpiar puntuación
        minigame?.scoreService?.clearGame(game.id.toString())
        minigame?.scoreService?.resetGamePoints()
    }
    
    /**
     * Obtiene la partida en estado LOBBY.
     */
    fun getLobbyGame(): CadenaGame? {
        return games.values.firstOrNull { it.state == GameState.LOBBY }
    }
    
    /**
     * Crea una nueva partida si no existe una en LOBBY.
     */
    private fun getOrCreateLobbyGame(): CadenaGame {
        // Buscar partida existente en lobby
        val existingGame = getLobbyGame()
        if (existingGame != null) {
            return existingGame
        }
        
        // Crear nueva partida
        val newGame = CadenaGame()
        games[newGame.id] = newGame
        
        minigame?.logger?.info("[Nexo] Nueva partida creada: ${newGame.id}")
        
        return newGame
    }
    
    /**
     * Añade un jugador a una partida en LOBBY.
     */
    fun addPlayer(player: Player): Boolean {
        // Verificar si ya está en una partida
        if (isPlayerInGame(player)) {
            return false
        }
        
        // Obtener o crear partida en lobby
        val game = getOrCreateLobbyGame()
        
        // Añadir jugador a la partida
        addPlayer(player, game)
        
        // Dar ítems de selección de equipo
        updateAllLobbyInventories(game)
        
        return true
    }
    
    /**
     * Limpia todas las partidas.
     */
    fun clearAll() {
        games.values.toList().forEach { game ->
            endGame(game)
        }
        games.clear()
        playerToGame.clear()
    }
    
    /**
     * Asigna automáticamente jugadores sin equipo.
     */
    private fun assignUnassignedPlayers(unassignedPlayers: List<Player>, game: CadenaGame) {
        val teams = game.teams
        
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
                minigame?.logger?.info("[${minigame?.description?.name}] ${player.name} asignado a ${targetTeam.teamId}")
            } else {
                // Este caso no debería ocurrir si hay equipos predefinidos
                minigame?.logger?.severe("[${minigame?.description?.name}] No se pudo asignar equipo a ${player.name} - todos los equipos están llenos")
                player.sendMessage("${ChatColor.RED}Error: No hay equipos disponibles. Contacta a un administrador.")
            }
        }
        
        // 6. Actualizar inventarios de todos los jugadores en el lobby
        updateAllLobbyInventories(game)
        
        minigame?.logger?.info("[${minigame?.description?.name}] ✓ Asignación automática completada")
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
        
        minigame?.logger?.info("[${minigame?.description?.name}] Iniciando asignación inteligente para ${playersInGame.size} jugadores")
        
        // FASE 1: Asignar jugadores sin equipo
        assignUnassignedPlayers(playersInGame, game)
        
        // FASE 2: Redistribuir jugadores solitarios
        redistributeSoloPlayers(game)
        
        minigame?.logger?.info("[${minigame?.description?.name}] ✓ Asignación inteligente completada")
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
            minigame?.logger?.info("[${minigame?.description?.name}] No hay jugadores solitarios que redistribuir")
            return
        }
        
        minigame?.logger?.info("[${minigame?.description?.name}] Redistribuyendo ${soloTeams.size} jugadores solitarios")
        
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
                
                minigame?.logger?.info("[${minigame?.description?.name}] ${soloPlayer.name} transferido de ${soloTeam.teamId} a ${targetTeam.teamId}")
            } else {
                // Si no hay equipos disponibles, intentar fusionar con otro jugador solitario
                val anotherSoloTeam = soloTeams.find { it != soloTeam && it.players.size == 1 }
                if (anotherSoloTeam != null) {
                    // Mover el jugador al equipo del otro solitario
                    soloTeam.players.remove(soloPlayerUUID)
                    anotherSoloTeam.players.add(soloPlayerUUID)
                    
                    soloPlayer.sendMessage("${ChatColor.YELLOW}Has sido transferido al ${anotherSoloTeam.displayName}${ChatColor.YELLOW} para formar equipo!")
                    
                    minigame?.logger?.info("[${minigame?.description?.name}] ${soloPlayer.name} fusionado con otro jugador solitario en ${anotherSoloTeam.teamId}")
                }
            }
        }
        
        // Actualizar inventarios después de la redistribución
        updateAllLobbyInventories(game)
        
        // Log final del estado de equipos
        val finalTeamSizes = teams.filter { it.players.isNotEmpty() }.map { "${it.teamId}:${it.players.size}" }
        minigame?.logger?.info("[${minigame?.description?.name}] Estado final de equipos: ${finalTeamSizes.joinToString(", ")}")
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
