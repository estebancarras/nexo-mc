package yo.spray.robarCabeza.services

import yo.spray.robarCabeza.RobarCabezaManager
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Servicio de puntuación para el minijuego Robar Cabeza.
 * 
 * Responsabilidades:
 * - Otorgar puntos a los jugadores según sus acciones
 * - Gestionar puntuación dinámica por tiempo
 * - Registrar victorias y partidas jugadas
 * - Centralizar la lógica de puntuación del torneo
 * 
 * NOTA: Todos los valores de puntuación se leen desde robarcabeza.yml
 */
class ScoreService(
    private val manager: RobarCabezaManager
) {
    
    private val torneoManager = manager.torneoPlugin.torneoManager
    private val config = manager.getConfig()
    
    /**
     * Mapa de puntos acumulados por jugador en la partida actual.
     * Se usa para determinar el ranking final.
     */
    private val sessionScores = mutableMapOf<UUID, Int>()
    
    /**
     * Otorga puntos por retener la cabeza durante un intervalo.
     * Lee el valor desde robarcabeza.yml: puntuacion.puntos-intervalo-hold
     * 
     * @param player Jugador que retiene la cabeza
     */
    fun awardPointsForIntervalHolding(player: Player) {
        val points = config.getInt("puntuacion.puntos-intervalo-hold", 2)
        
        torneoManager.addScore(
            player.uniqueId,
            manager.gameName,
            points,
            "Retener cabeza"
        )
        
        // Actualizar puntos de sesión
        sessionScores[player.uniqueId] = (sessionScores[player.uniqueId] ?: 0) + points
    }
    
    /**
     * Otorga puntos bonus por robar una cabeza exitosamente.
     * Lee el valor desde robarcabeza.yml: puntuacion.bono-por-robo
     * 
     * @param player Jugador que robó la cabeza
     */
    fun awardPointsForSteal(player: Player) {
        val points = config.getInt("puntuacion.bono-por-robo", 2)
        
        torneoManager.addScore(
            player.uniqueId,
            manager.gameName,
            points,
            "Robo de cabeza"
        )
        
        // Actualizar puntos de sesión
        sessionScores[player.uniqueId] = (sessionScores[player.uniqueId] ?: 0) + points
    }
    
    /**
     * Otorga puntos bonus al finalizar el juego según el ranking.
     * Lee los valores desde robarcabeza.yml: puntuacion.bono-posicion-final
     * 
     * @param player Jugador
     * @param position Posición en el ranking (1, 2, 3)
     */
    fun awardPointsForFinalPosition(player: Player, position: Int) {
        val points = when (position) {
            1 -> config.getInt("puntuacion.bono-posicion-final.primero", 15)
            2 -> config.getInt("puntuacion.bono-posicion-final.segundo", 10)
            3 -> config.getInt("puntuacion.bono-posicion-final.tercero", 5)
            else -> 0
        }
        
        if (points > 0) {
            torneoManager.addScore(
                player.uniqueId,
                manager.gameName,
                points,
                "Posición #$position"
            )
            
            if (position == 1) {
                torneoManager.recordGameWon(player, manager.gameName)
            }
        }
    }
    
    /**
     * Otorga puntos por participar en la partida.
     * Lee el valor desde robarcabeza.yml: puntuacion.participacion
     * 
     * @param player Jugador participante
     */
    fun awardPointsForParticipation(player: Player) {
        val points = config.getInt("puntuacion.participacion", 10)
        
        torneoManager.addScore(
            player.uniqueId,
            manager.gameName,
            points,
            "Participación"
        )
        torneoManager.recordGamePlayed(player, manager.gameName)
    }
    
    /**
     * Obtiene el ranking de jugadores ordenado por puntos de sesión.
     * 
     * @return Lista de pares (UUID, puntos) ordenada de mayor a menor
     */
    fun getSessionRanking(): List<Pair<UUID, Int>> {
        return sessionScores.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }
    
    /**
     * Obtiene los puntos de sesión de un jugador.
     * 
     * @param playerId UUID del jugador
     * @return Puntos acumulados en la sesión
     */
    fun getSessionScore(playerId: UUID): Int {
        return sessionScores[playerId] ?: 0
    }
    
    /**
     * Reinicia los puntos de sesión para una nueva partida.
     */
    fun resetSessionScores() {
        sessionScores.clear()
    }
    
    /**
     * Registra que un jugador jugó una partida.
     * 
     * @param player Jugador
     */
    fun recordGamePlayed(player: Player) {
        torneoManager.recordGamePlayed(player, manager.gameName)
    }
}
