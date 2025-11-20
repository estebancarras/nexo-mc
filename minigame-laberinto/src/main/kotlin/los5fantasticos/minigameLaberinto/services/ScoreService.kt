package los5fantasticos.minigameLaberinto.services

import los5fantasticos.minigameLaberinto.MinigameLaberinto
import los5fantasticos.torneo.core.TorneoManager
import org.bukkit.entity.Player

/**
 * Servicio de puntuación del minijuego Laberinto.
 * 
 * Responsable de:
 * - Otorgar puntos por completar el laberinto según posición
 * - Otorgar puntos de participación
 * - Registrar estadísticas de juego
 */
class ScoreService(
    private val minigame: MinigameLaberinto,
    private val torneoManager: TorneoManager
) {
    
    /**
     * Puntos otorgados por posición al completar el laberinto.
     */
    private val firstPlacePoints = 100
    private val secondPlacePoints = 80
    private val thirdPlacePoints = 60
    private val completionPoints = 40
    
    /**
     * Puntos otorgados por participar (no completar).
     */
    private val participationPoints = 10
    
    /**
     * Otorga puntos a un jugador por completar el laberinto según su posición.
     * 
     * @param player Jugador que completó el laberinto
     * @param position Posición en la que finalizó (1 = primero, 2 = segundo, etc.)
     */
    fun awardCompletionPoints(player: Player, position: Int) {
        val points = when (position) {
            1 -> firstPlacePoints
            2 -> secondPlacePoints
            3 -> thirdPlacePoints
            else -> completionPoints
        }
        
        val positionText = when (position) {
            1 -> "1er lugar"
            2 -> "2do lugar"
            3 -> "3er lugar"
            else -> "Completó el laberinto"
        }
        
        torneoManager.addScore(
            player.uniqueId,
            minigame.gameName,
            points,
            positionText
        )
        
        // Registrar victoria
        torneoManager.recordGameWon(player, minigame.gameName)
        
        minigame.plugin.logger.info("${player.name} recibió $points puntos por $positionText en el laberinto")
    }
    
    /**
     * Otorga puntos de participación a un jugador.
     * 
     * @param player Jugador que participó pero no completó
     */
    fun awardParticipationPoints(player: Player) {
        torneoManager.addScore(
            player.uniqueId,
            minigame.gameName,
            participationPoints,
            "Participó en el laberinto"
        )
        
        // Registrar partida jugada
        torneoManager.recordGamePlayed(player, minigame.gameName)
        
        minigame.plugin.logger.info("${player.name} recibió $participationPoints puntos por participar en el laberinto")
    }
    
    
    /**
     * Limpia el servicio de puntuación.
     */
    fun clearAll() {
        // No hay datos persistentes que limpiar
        minigame.plugin.logger.info("ScoreService limpiado")
    }
}
