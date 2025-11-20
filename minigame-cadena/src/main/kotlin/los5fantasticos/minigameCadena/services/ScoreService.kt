package los5fantasticos.minigameCadena.services

import los5fantasticos.minigameCadena.MinigameCadena
import los5fantasticos.minigameCadena.game.CadenaGame
import los5fantasticos.minigameCadena.game.Team
import los5fantasticos.torneo.core.TorneoManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio de puntuación para el minijuego Cadena.
 * 
 * Sistema de puntos basado en puntajesjuegosv2.xlsx:
 * - Se otorga puntuación CADA VEZ que un equipo alcanza un nuevo checkpoint
 * - Puntos base por checkpoint: configurable en cadena.yml
 * - Bonos por posición (1er, 2do, 3er equipo en alcanzar cada checkpoint)
 * - Bonos adicionales por completar el parkour (llegar a la meta)
 * - Bonos por posición final (1er, 2do, 3er en completar + bono por completar)
 * 
 * Utiliza TorneoManager.addScore() como único punto de entrada para puntos.
 */
class ScoreService(
    private val minigame: MinigameCadena,
    private val torneoManager: TorneoManager
) {
    
    /**
     * Mapa que rastrea el orden de llegada por CADA checkpoint en CADA partida activa.
     * Key: gameId (String)
     * Value: Mapa de Checkpoints (Key: checkpointIndex (Int), Value: Lista de Teams en orden)
     */
    private val checkpointFinishOrder = ConcurrentHashMap<String, ConcurrentHashMap<Int, MutableList<Team>>>()
    
    /**
     * Mapa que rastrea el orden de llegada a la META por partida.
     * Key: gameId (String)
     * Value: Lista de Teams en orden de llegada a la meta
     */
    private val metaFinishOrder = ConcurrentHashMap<String, MutableList<Team>>()
    
    /**
     * Mapa que rastrea los puntos acumulados en la partida por equipo.
     * Key: teamName (String)
     * Value: Puntos acumulados en la partida actual
     */
    private val gamePoints = mutableMapOf<String, Int>()
    
    /**
     * Otorga puntos a un equipo completo por alcanzar un nuevo checkpoint.
     * Esta es la lógica central de puntuación según puntajesjuegosv2.xlsx.
     * 
     * DEBE SER LLAMADO por ParkourService SÓLO CUANDO se confirma que
     * este es un checkpoint NUEVO para el equipo.
     * 
     * @param game La partida actual
     * @param team El equipo que alcanzó el checkpoint
     * @param checkpointIndex El índice del checkpoint (0, 1, 2...)
     */
    fun awardTeamPointsForCheckpoint(game: CadenaGame, team: Team, checkpointIndex: Int) {
        // 1. Obtener los valores del config.yml (usando los nuevos paths)
        val config = minigame.plugin.config
        val basePoints = config.getInt("puntuacion.por-checkpoint.base", 15)
        val bonus1st = config.getInt("puntuacion.por-checkpoint.bono-1er-lugar", 6)
        val bonus2nd = config.getInt("puntuacion.por-checkpoint.bono-2do-lugar", 4)
        val bonus3rd = config.getInt("puntuacion.por-checkpoint.bono-3er-lugar", 2)
        
        // 2. Determinar la posición de este equipo para este checkpoint
        val gameCheckpointMap = checkpointFinishOrder.getOrPut(game.id.toString()) { ConcurrentHashMap<Int, MutableList<Team>>() }
        val orderList = gameCheckpointMap.getOrPut(checkpointIndex) { mutableListOf<Team>() }
        
        val position = synchronized(orderList) {
            if (orderList.contains(team)) {
                // Salvaguarda: El equipo ya ha sido registrado para este checkpoint.
                Bukkit.getLogger().warning("[Cadena] El equipo ${team.displayName} ya había puntuado en el CP $checkpointIndex.")
                return
            }
            orderList.add(team)
            orderList.size // La posición es el nuevo tamaño (1, 2, 3...)
        }
        
        // 3. Calcular el bono
        val bonusPoints = when (position) {
            1 -> bonus1st
            2 -> bonus2nd
            3 -> bonus3rd
            else -> 0
        }
        
        val totalPoints = basePoints + bonusPoints
        if (totalPoints <= 0) return // No asignar 0 puntos
        
        // 4. Asignar puntos a cada jugador del equipo
        team.players.forEach { playerUUID ->
            torneoManager.addScore(
                playerUUID,
                minigame.gameName,
                totalPoints,
                "Checkpoint ${checkpointIndex + 1} ($position° lugar)" // +1 para que sea user-friendly
            )
        }
        
        // 4.5. Actualizar puntos del juego para el scoreboard
        gamePoints[team.displayName] = (gamePoints[team.displayName] ?: 0) + totalPoints
        
        // 5. Notificar a todos los jugadores de la partida
        broadcastCheckpoint(game, team, position, checkpointIndex, totalPoints)
    }
    
    /**
     * Notifica a todos los jugadores de la partida que un equipo alcanzó un checkpoint.
     */
    private fun broadcastCheckpoint(game: CadenaGame, team: Team, position: Int, checkpointIndex: Int, points: Int) {
        val positionComponent = when (position) {
            1 -> Component.text("1er LUGAR", NamedTextColor.GOLD, TextDecoration.BOLD)
            2 -> Component.text("2do LUGAR", NamedTextColor.GRAY, TextDecoration.BOLD)
            3 -> Component.text("3er LUGAR", NamedTextColor.GOLD, TextDecoration.BOLD) // Usamos GOLD para 3ro (bronce)
            else -> Component.text("${position}º LUGAR", NamedTextColor.WHITE, TextDecoration.BOLD)
        }
        
        val teamNameComponent = Component.text(team.displayName, team.color)
        
        val header = Component.text("--- CHECKPOINT ${checkpointIndex + 1} ---", NamedTextColor.GREEN, TextDecoration.BOLD)
        val announcement = positionComponent
            .append(Component.space())
            .append(Component.text("¡Equipo ", NamedTextColor.YELLOW))
            .append(teamNameComponent)
            .append(Component.text(" alcanzó el checkpoint!", NamedTextColor.YELLOW))
        val pointsComponent = Component.text("Puntos ganados: ", NamedTextColor.GOLD)
            .append(Component.text(points, NamedTextColor.YELLOW, TextDecoration.BOLD))
        val footer = Component.text("-----------------------", NamedTextColor.GREEN, TextDecoration.BOLD)
        
        // Sonido de recompensa
        val rewardSound = Sound.sound(
            org.bukkit.Sound.ENTITY_PLAYER_LEVELUP,
            Sound.Source.MASTER,
            0.7f,
            1.2f
        )
        val normalSound = Sound.sound(
            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING,
            Sound.Source.MASTER,
            1.0f,
            1.5f
        )
        
        // Notificar a todos los jugadores de todos los equipos
        game.teams.forEach { t ->
            t.getOnlinePlayers().forEach { player ->
                player.sendMessage(Component.empty())
                player.sendMessage(header)
                player.sendMessage(announcement)
                player.sendMessage(pointsComponent)
                player.sendMessage(footer)
                player.sendMessage(Component.empty())
                
                if (position <= 3) {
                    player.playSound(rewardSound)
                } else {
                    player.playSound(normalSound)
                }
            }
        }
    }
    
    /**
     * Otorga puntos a un equipo por completar el parkour (llegar a la meta).
     * 
     * @param game La partida actual
     * @param team El equipo que completó el parkour
     * @return La posición en la que terminó (1, 2, 3, etc.)
     */
    fun awardTeamPointsForMeta(game: CadenaGame, team: Team): Int {
        // 1. Obtener los valores del config.yml
        val config = minigame.plugin.config
        val bonus1st = config.getInt("puntuacion.por-meta.bono-1er-lugar", 50)
        val bonus2nd = config.getInt("puntuacion.por-meta.bono-2do-lugar", 30)
        val bonus3rd = config.getInt("puntuacion.por-meta.bono-3er-lugar", 20)
        val bonusComplete = config.getInt("puntuacion.por-meta.bono-completar", 10)
        
        // 2. Determinar la posición de este equipo en la meta
        val orderList = metaFinishOrder.getOrPut(game.id.toString()) { mutableListOf<Team>() }
        
        val position = synchronized(orderList) {
            if (orderList.contains(team)) {
                // Salvaguarda: El equipo ya ha sido registrado en la meta
                Bukkit.getLogger().warning("[Cadena] El equipo ${team.displayName} ya había completado la meta.")
                return orderList.indexOf(team) + 1
            }
            orderList.add(team)
            orderList.size // La posición es el nuevo tamaño (1, 2, 3...)
        }
        
        // 3. Calcular el bono según posición
        val bonusPoints = when (position) {
            1 -> bonus1st
            2 -> bonus2nd
            3 -> bonus3rd
            else -> bonusComplete
        }
        
        if (bonusPoints <= 0) return position // No asignar 0 puntos
        
        // 4. Asignar puntos a cada jugador del equipo
        team.players.forEach { playerUUID ->
            val positionText = when (position) {
                1 -> "1er lugar"
                2 -> "2do lugar"
                3 -> "3er lugar"
                else -> "${position}° lugar"
            }
            torneoManager.addScore(
                playerUUID,
                minigame.gameName,
                bonusPoints,
                "Completó parkour ($positionText)"
            )
        }
        
        // 4.5. Actualizar puntos del juego para el scoreboard
        gamePoints[team.displayName] = (gamePoints[team.displayName] ?: 0) + bonusPoints
        
        // 5. Notificar a todos los jugadores de la partida
        broadcastMeta(game, team, position, bonusPoints)
        
        return position
    }
    
    /**
     * Notifica a todos los jugadores de la partida que un equipo completó el parkour.
     */
    private fun broadcastMeta(game: CadenaGame, team: Team, position: Int, points: Int) {
        val positionComponent = when (position) {
            1 -> Component.text("🥇 1er LUGAR", NamedTextColor.GOLD, TextDecoration.BOLD)
            2 -> Component.text("🥈 2do LUGAR", NamedTextColor.GRAY, TextDecoration.BOLD)
            3 -> Component.text("🥉 3er LUGAR", NamedTextColor.GOLD, TextDecoration.BOLD)
            else -> Component.text("✓ COMPLETADO", NamedTextColor.GREEN, TextDecoration.BOLD)
        }
        
        val teamNameComponent = Component.text(team.displayName, team.color)
        
        val header = Component.text("═══════════════════════════", NamedTextColor.GOLD, TextDecoration.BOLD)
        val announcement = positionComponent
            .append(Component.space())
            .append(Component.text("¡Equipo ", NamedTextColor.YELLOW))
            .append(teamNameComponent)
            .append(Component.text(" completó el parkour!", NamedTextColor.YELLOW))
        val pointsComponent = Component.text("Bono por completar: ", NamedTextColor.GOLD)
            .append(Component.text("+$points pts", NamedTextColor.YELLOW, TextDecoration.BOLD))
        val footer = Component.text("═══════════════════════════", NamedTextColor.GOLD, TextDecoration.BOLD)
        
        // Sonido de victoria
        val victorySound = Sound.sound(
            org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE,
            Sound.Source.MASTER,
            1.0f,
            1.0f
        )
        
        // Notificar a todos los jugadores de todos los equipos
        game.teams.forEach { t ->
            t.getOnlinePlayers().forEach { player ->
                player.sendMessage(Component.empty())
                player.sendMessage(header)
                player.sendMessage(announcement)
                player.sendMessage(pointsComponent)
                player.sendMessage(footer)
                player.sendMessage(Component.empty())
                
                player.playSound(victorySound)
            }
        }
    }
    
    /**
     * Limpia los registros de puntuación de una partida finalizada.
     */
    fun clearGame(gameId: String) {
        checkpointFinishOrder.remove(gameId)
        metaFinishOrder.remove(gameId)
    }
    
    // ===== MÉTODOS PARA SCOREBOARD =====
    
    /**
     * Obtiene los puntos acumulados en la partida actual por un equipo.
     */
    fun getGamePoints(teamName: String): Int {
        return gamePoints[teamName] ?: 0
    }
    
    /**
     * Reinicia los puntos del juego al inicio de una nueva partida.
     */
    fun resetGamePoints() {
        gamePoints.clear()
    }
}
