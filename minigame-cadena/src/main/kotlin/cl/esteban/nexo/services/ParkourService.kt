package cl.esteban.nexo.services

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.game.Arena
import cl.esteban.nexo.game.CadenaGame
import cl.esteban.nexo.game.GameState
import cl.esteban.nexo.game.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Servicio que gestiona la lógica de parkour del minijuego Cadena.
 * 
 * Responsabilidades:
 * - Detectar caídas de jugadores
 * - Detectar cuando un jugador alcanza un checkpoint
 * - Reiniciar equipos al último checkpoint
 * - Detectar cuando un equipo completa el parkour
 */
class ParkourService(private val minigame: NexoPlugin) {
    
    /**
     * Mapa de progreso de jugadores.
     * UUID del jugador -> Índice del último checkpoint alcanzado (-1 = spawn)
     */
    private val playerProgress = mutableMapOf<UUID, Int>()
    
    /**
     * Verifica si un jugador ha caído por debajo de la altura mínima.
     * Si es así, reinicia todo el equipo al último checkpoint.
     * 
     * @param player Jugador a verificar
     * @param game Partida actual
     */
    fun checkFall(player: Player, game: CadenaGame) {
        val arena = game.arena ?: return
        
        // Verificar si el jugador cayó
        if (!arena.isBelowMinHeight(player.location)) {
            return
        }
        
        // Obtener el equipo del jugador
        val team = minigame.gameManager.getPlayerTeam(player) ?: return
        
        // Reiniciar todo el equipo
        respawnTeam(team, game, arena)
        
        // Notificar
        team.getOnlinePlayers().forEach { p ->
            p.sendMessage("${ChatColor.RED}¡${player.name} cayó! El equipo ha sido reiniciado.")
            p.playSound(p.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f)
        }
    }
    
    // COMENTADO: Ahora usamos game loop con regiones Cuboid
    /*
    /**
     * Verifica si un jugador ha alcanzado un checkpoint.
     * 
     * @param player Jugador a verificar
     * @param game Partida actual
     */
    fun checkCheckpoint(player: Player, game: CadenaGame) {
        val arena = game.arena ?: return
        val team = minigame.gameManager.getPlayerTeam(player) ?: return
        
        // Obtener el checkpoint actual del equipo
        val currentCheckpoint = game.teamCheckpoints.getOrDefault(team.teamId, -1)
        
        // Verificar el siguiente checkpoint
        val nextCheckpointIndex = currentCheckpoint + 1
        
        // Si ya alcanzaron todos los checkpoints, verificar la meta
        if (nextCheckpointIndex >= arena.getCheckpointCount()) {
            checkFinish(player, game)
            return
        }
        
        // Obtener la ubicación del siguiente checkpoint
        val checkpointLocation = arena.getCheckpoint(nextCheckpointIndex) ?: return
        
        // Verificar si el jugador está cerca del checkpoint
        if (!arena.isNearCheckpoint(player.location, checkpointLocation)) {
            return
        }
        
        // Verificar si TODO el equipo está cerca del checkpoint
        val allNear = team.getOnlinePlayers().all { p ->
            arena.isNearCheckpoint(p.location, checkpointLocation)
        }
        
        if (!allNear) {
            // Notificar que deben estar todos juntos
            player.sendMessage("${ChatColor.YELLOW}¡Espera a tu equipo! Todos deben llegar juntos al checkpoint.")
            return
        }
        
        // Actualizar checkpoint del equipo
        game.teamCheckpoints[team.teamId] = nextCheckpointIndex
        
        // Notificar al equipo
        team.getOnlinePlayers().forEach { p ->
            p.sendMessage("${ChatColor.GREEN}${ChatColor.BOLD}✓ Checkpoint ${nextCheckpointIndex + 1}/${arena.getCheckpointCount()} alcanzado!")
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f)
        }
    }
    
    /**
     * Verifica si un equipo ha alcanzado la meta.
     * 
     * @param player Jugador a verificar
     * @param game Partida actual
     */
    private fun checkFinish(player: Player, game: CadenaGame) {
        val arena = game.arena ?: return
        val team = minigame.gameManager.getPlayerTeam(player) ?: return
        
        // Verificar si el jugador está cerca de la meta
        if (!arena.isNearCheckpoint(player.location, arena.finishLocation)) {
            return
        }
        
        // Verificar si TODO el equipo está cerca de la meta
        val allNear = team.getOnlinePlayers().all { p ->
            arena.isNearCheckpoint(p.location, arena.finishLocation)
        }
        
        if (!allNear) {
            // Notificar que deben estar todos juntos
            player.sendMessage("${ChatColor.YELLOW}¡Espera a tu equipo! Todos deben cruzar la meta juntos.")
            return
        }
        
        // PR5: Registrar finalización y asignar puntos
        val position = minigame.scoreService.registerFinish(game, team)
        val points = minigame.scoreService.getPointsForPosition(position)
        
        // Verificar si todos los equipos han terminado
        if (minigame.scoreService.allTeamsFinished(game)) {
            // Finalizar la partida
            finishGame(game)
        }
    }
    */
    
    /**
     * Reinicia un equipo a su último checkpoint o al spawn inicial.
     * 
     * @param team Equipo a reiniciar
     * @param game Partida actual
     * @param arena Arena de la partida
     */
    private fun respawnTeam(team: Team, game: CadenaGame, arena: Arena) {
        // Obtener el último checkpoint del equipo
        val checkpointIndex = game.teamCheckpoints.getOrDefault(team.teamId, -1)
        
        // Determinar ubicación de respawn
        val respawnLocation = if (checkpointIndex == -1) {
            // Respawn inicial
            arena.spawnLocation
        } else {
            // Último checkpoint - usar el centro de la región
            val checkpoint = arena.getCheckpoint(checkpointIndex)
            checkpoint?.getCenter() ?: arena.spawnLocation
        }
        
        // Teletransportar a todos los jugadores del equipo
        team.getOnlinePlayers().forEach { player ->
            player.teleport(respawnLocation)
            
            // Resetear velocidad para evitar que sigan cayendo
            player.velocity = org.bukkit.util.Vector(0, 0, 0)
            
            // Efecto visual
            player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
        }
    }
    
    /**
     * Teletransporta un equipo a una ubicación específica.
     * 
     * @param team Equipo a teletransportar
     * @param location Ubicación destino
     */
    fun teleportTeam(team: Team, location: Location) {
        team.getOnlinePlayers().forEach { player ->
            player.teleport(location)
            player.velocity = org.bukkit.util.Vector(0, 0, 0)
        }
    }
    
    /**
     * Teletransporta todos los equipos de una partida al spawn de la arena.
     * TAREA 4: Ahora soporta múltiples spawns, uno por equipo.
     * 
     * @param game Partida actual
     */
    fun teleportAllTeamsToSpawn(game: CadenaGame) {
        val arena = game.arena ?: return
        
        // Filtrar solo equipos con jugadores
        val teamsWithPlayers = game.teams.filter { it.players.isNotEmpty() }
        
        teamsWithPlayers.forEachIndexed { index, team ->
            // Obtener spawn específico para este equipo (o usar el principal si no hay suficientes)
            val spawnLocation = arena.getSpawnLocation(index)
            teleportTeam(team, spawnLocation)
            
            // Inicializar checkpoint del equipo
            game.teamCheckpoints[team.teamId] = -1
        }
    }
    
    /**
     * Maneja cuando se acaba el tiempo de una partida.
     * Método público llamado por el temporizador.
     */
    fun handleTimeUp(game: CadenaGame) {
        finishGame(game)
    }
    
    /**
     * Finaliza una partida cuando todos los equipos han completado o se acabó el tiempo.
     */
    private fun finishGame(game: CadenaGame) {
        // Cambiar estado
        game.state = GameState.FINISHED
        
        // Detener temporizador visual (BossBar)
        game.gameTimer?.stop()
        
        // Limpiar inventarios de todos los jugadores
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                player.inventory.clear()
            }
        }
        
        // NOTA: Ya no se calculan puntos al final de la partida.
        // Los puntos se otorgan progresivamente por cada checkpoint alcanzado.
        
        // Desactivar servicios
        minigame.chainService.stopChaining(game)
        
        // Registrar partidas jugadas para todos los jugadores
        // Registrar partidas jugadas para todos los jugadores
        // Standalone: No hay TorneoManager
        // game.teams.forEach { team -> ... }
        
        // Limpiar después de 10 segundos
        minigame.server.scheduler.runTaskLater(minigame, Runnable {
            minigame.gameManager.endGame(game)
        }, 200L)
    }
    
    // ===== NUEVOS MÉTODOS PARA SISTEMA DE REGIONES =====
    
    /**
     * Reinicia el progreso de un jugador al spawn inicial.
     */
    fun resetProgress(player: Player) {
        playerProgress[player.uniqueId] = -1 // -1 significa que están en el spawn
    }
    
    /**
     * Obtiene el índice del siguiente checkpoint que debe alcanzar el jugador.
     */
    fun getNextCheckpointIndex(player: Player): Int {
        return playerProgress.getOrDefault(player.uniqueId, -1) + 1
    }
    
    /**
     * Verifica si un jugador ha completado todos los checkpoints.
     */
    fun hasCompletedAllCheckpoints(player: Player, arena: Arena): Boolean {
        return playerProgress.getOrDefault(player.uniqueId, -1) == arena.checkpoints.size - 1
    }
    
    /**
     * Maneja cuando un jugador alcanza un checkpoint.
     */
    fun handleCheckpointReached(player: Player, checkpointIndex: Int) {
        // Asegurarse de que el jugador está alcanzando el checkpoint correcto en orden
        if (checkpointIndex != getNextCheckpointIndex(player)) return
        
        playerProgress[player.uniqueId] = checkpointIndex
        
        // Actualizar el checkpoint del equipo para respawn y puntuación
        val game: CadenaGame? = minigame.gameManager.getPlayerGame(player)
        val team: Team? = minigame.gameManager.getPlayerTeam(player)
        if (game != null && team != null) {
            // Obtener el checkpoint actual del equipo ANTES de actualizar
            val previousTeamCheckpoint = game.teamCheckpoints.getOrDefault(team.teamId, -1)
            
            // Actualizar el checkpoint del equipo al mínimo progreso de todos los miembros
            val minProgress = team.players.mapNotNull { playerId ->
                playerProgress[playerId]
            }.minOrNull() ?: -1
            game.teamCheckpoints[team.teamId] = minProgress
            
            // NUEVO: Si el equipo avanzó a un nuevo checkpoint, otorgar puntos
            // Solo se otorgan puntos cuando TODO el equipo alcanza el checkpoint
            if (minProgress > previousTeamCheckpoint) {
                minigame.scoreService.awardTeamPointsForCheckpoint(game, team, minProgress)
            }
        }
        
        // Feedback al jugador (solo notificación individual, la puntuación se anuncia por equipo)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        val title = Title.title(
            Component.text("¡CHECKPOINT!", NamedTextColor.GREEN),
            Component.text("Has alcanzado el checkpoint ${checkpointIndex + 1}", NamedTextColor.GRAY)
        )
        player.showTitle(title)
        
        // Notificar al equipo
        team?.getOnlinePlayers()?.forEach { teamPlayer ->
            if (teamPlayer != player) {
                teamPlayer.sendMessage("${ChatColor.GREEN}${player.name} alcanzó el checkpoint ${checkpointIndex + 1}!")
            }
        }
    }
    
    /**
     * Maneja cuando un jugador alcanza la meta.
     * Otorga bonos por completar el parkour según la posición de llegada.
     */
    fun handlePlayerWin(player: Player, game: CadenaGame) {
        // Verificar que realmente completó todos los checkpoints
        val arena = game.arena ?: return
        if (!hasCompletedAllCheckpoints(player, arena)) return
        
        // Obtener el equipo del jugador
        val team = minigame.gameManager.getPlayerTeam(player) ?: return
        
        // Otorgar puntos por completar el parkour
        val position = minigame.scoreService.awardTeamPointsForMeta(game, team)
        
        // Feedback al jugador
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f)
        val title = Title.title(
            Component.text("¡VICTORIA!", NamedTextColor.GOLD),
            Component.text("¡Has completado el parkour!", NamedTextColor.YELLOW)
        )
        player.showTitle(title)
        
        // Registrar victoria (para estadísticas)
        // Registrar victoria (para estadísticas)
        // Standalone: No hay TorneoManager
        // minigame.torneoPlugin.torneoManager.recordGameWon(player, minigame.gameName)
        
        // Finalizar partida
        finishGame(game)
    }
    
    /**
     * Limpia todo el progreso de jugadores.
     */
    fun clearAllProgress() {
        playerProgress.clear()
    }
    
    // ===== MÉTODOS PARA SCOREBOARD =====
    
    /**
     * Obtiene el progreso actual de un equipo (número de checkpoints completados).
     * 
     * @param team Equipo
     * @param game Partida actual
     */
    fun getTeamProgress(team: Team, game: CadenaGame): Int {
        return game.teamCheckpoints.getOrDefault(team.teamId, -1) + 1 // +1 porque -1 es spawn
    }
    
    /**
     * Obtiene el ranking de equipos ordenados por progreso.
     * Los equipos con más checkpoints completados aparecen primero.
     */
    fun getTeamRankings(game: CadenaGame): List<Team> {
        return game.teams
            .filter { it.players.isNotEmpty() }
            .sortedByDescending { team ->
                game.teamCheckpoints.getOrDefault(team.teamId, -1)
            }
    }
}
