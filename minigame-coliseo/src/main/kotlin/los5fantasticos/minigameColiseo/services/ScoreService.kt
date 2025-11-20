package los5fantasticos.minigameColiseo.services

import los5fantasticos.torneo.TorneoPlugin
import los5fantasticos.minigameColiseo.game.ColiseoGame
import los5fantasticos.minigameColiseo.game.TeamType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Servicio de puntuación del Coliseo V4.1 (Balanceado).
 * 
 * Sistema de puntuación:
 * - Si Horda gana: +60 pts a todos (vivos y muertos), -25 pts a Élite
 * - Si Élite gana: +20 pts solo a supervivientes
 * - Por kill: Horda mata Élite = +40 pts, Élite mata Horda = +10 pts
 */
class ScoreService(
    private val torneoPlugin: TorneoPlugin,
    private val config: FileConfiguration,
    private val gameName: String = "Coliseo"
) {
    
    // Valores de puntuación V4.1
    private val hordeWinBonus = config.getInt("scoring.horde-win-bonus", 60)
    private val eliteWinBonus = config.getInt("scoring.elite-win-bonus", 20)
    private val eliteLossPenalty = config.getInt("scoring.elite-loss-penalty", 25)
    private val hordeKillElite = config.getInt("scoring.horde-kill-elite", 40)
    private val eliteKillHorde = config.getInt("scoring.elite-kill-horde", 10)
    
    /**
     * Otorga puntos por kill en tiempo real.
     * Llamado por GameListener cuando un jugador mata a otro.
     */
    fun awardKillBonus(killer: Player, victim: Player, killerTeam: TeamType, victimTeam: TeamType) {
        when {
            killerTeam == TeamType.HORDE && victimTeam == TeamType.ELITE -> {
                // Horda mata Élite: +40 puntos
                torneoPlugin.torneoManager.addScore(
                    killer.uniqueId,
                    gameName,
                    hordeKillElite,
                    "Eliminó a un Élite"
                )
                killer.sendMessage(
                    Component.text("¡Has eliminado a un Élite! ", NamedTextColor.GOLD)
                        .append(Component.text("+$hordeKillElite pts", NamedTextColor.YELLOW))
                )
            }
            killerTeam == TeamType.ELITE && victimTeam == TeamType.HORDE -> {
                // Élite mata Horda: +10 puntos
                torneoPlugin.torneoManager.addScore(
                    killer.uniqueId,
                    gameName,
                    eliteKillHorde,
                    "Eliminó a un Horda"
                )
                killer.sendMessage(
                    Component.text("Has eliminado a un miembro de la Horda. ", NamedTextColor.GRAY)
                        .append(Component.text("+$eliteKillHorde pts", NamedTextColor.YELLOW))
                )
            }
        }
    }
    
    /**
     * Finaliza la puntuación al terminar la partida.
     * Llamado por GameManager.endGame().
     */
    fun finalizeScores(game: ColiseoGame, winner: TeamType) {
        when (winner) {
            TeamType.HORDE -> {
                // Horda gana: +60 pts a todos (vivos y muertos)
                game.hordePlayers.forEach { playerUUID ->
                    torneoPlugin.torneoManager.addScore(
                        playerUUID,
                        gameName,
                        hordeWinBonus,
                        "Victoria de la Horda"
                    )
                    torneoPlugin.torneoManager.recordGameWon(
                        Bukkit.getPlayer(playerUUID) ?: return@forEach,
                        gameName
                    )
                    torneoPlugin.torneoManager.recordGamePlayed(
                        Bukkit.getPlayer(playerUUID) ?: return@forEach,
                        gameName
                    )
                }
                
                // Élite pierde: -25 pts a todos (usando puntos negativos)
                val penaltyPoints = -eliteLossPenalty
                Bukkit.getLogger().info("[Coliseo] Aplicando penalización a Élite: $penaltyPoints puntos (eliteLossPenalty=$eliteLossPenalty)")
                
                game.elitePlayers.forEach { playerUUID ->
                    val player = Bukkit.getPlayer(playerUUID)
                    Bukkit.getLogger().info("[Coliseo] Penalizando a ${player?.name ?: playerUUID}: $penaltyPoints puntos")
                    
                    torneoPlugin.torneoManager.addScore(
                        playerUUID,
                        gameName,
                        penaltyPoints,  // Puntos negativos (-25)
                        "Derrota de la Élite"
                    )
                    
                    if (player != null) {
                        torneoPlugin.torneoManager.recordGamePlayed(player, gameName)
                    }
                }
            }
            TeamType.ELITE -> {
                // Élite gana: +20 pts solo a supervivientes
                game.elitePlayers.forEach { playerUUID ->
                    val player = Bukkit.getPlayer(playerUUID)
                    if (player != null && player.isOnline && !player.isDead) {
                        torneoPlugin.torneoManager.addScore(
                            playerUUID,
                            gameName,
                            eliteWinBonus,
                            "Victoria de la Élite (superviviente)"
                        )
                        torneoPlugin.torneoManager.recordGameWon(player, gameName)
                    }
                    // Registrar partida jugada para todos
                    if (player != null) {
                        torneoPlugin.torneoManager.recordGamePlayed(player, gameName)
                    }
                }
                
                // Horda: solo registrar partida jugada
                game.hordePlayers.forEach { playerUUID ->
                    val player = Bukkit.getPlayer(playerUUID)
                    if (player != null) {
                        torneoPlugin.torneoManager.recordGamePlayed(player, gameName)
                    }
                }
            }
        }
    }
}
