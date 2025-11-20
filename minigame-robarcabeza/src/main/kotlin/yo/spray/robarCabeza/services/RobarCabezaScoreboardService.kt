package yo.spray.robarCabeza.services

import los5fantasticos.torneo.services.GlobalScoreboardService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import yo.spray.robarCabeza.game.RobarCabezaGame
import java.util.UUID

/**
 * Servicio de scoreboard dedicado para el minijuego Robar Cabeza.
 * 
 * Muestra información en tiempo real durante la partida:
 * - Arena actual
 * - Puntos de la sesión del jugador
 * - Estado de la cabeza (si la tiene o no)
 * - Jugadores con cabeza actualmente
 * - Top 3 del ranking de la sesión
 * 
 * Este scoreboard reemplaza temporalmente al scoreboard global del torneo.
 */
class RobarCabezaScoreboardService(
    private val plugin: Plugin,
    private val globalScoreboardService: GlobalScoreboardService,
    private val scoreService: ScoreService
) {
    
    /**
     * Mapa de scoreboards por jugador.
     */
    private val playerBoards = mutableMapOf<UUID, Scoreboard>()
    
    /**
     * Mapa de tareas de actualización por jugador.
     */
    private val updateTasks = mutableMapOf<UUID, BukkitTask>()
    
    /**
     * Muestra el scoreboard de Robar Cabeza a un jugador.
     * Oculta el scoreboard global y comienza a actualizar el scoreboard del juego.
     */
    fun showScoreboard(player: Player, game: RobarCabezaGame) {
        // CRÍTICO: Ocultar scoreboard global PRIMERO para añadir a excludedPlayers
        globalScoreboardService.hideScoreboard(player)
        
        // Crear scoreboard de Bukkit
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        
        val objective = scoreboard.registerNewObjective(
            "robarcabeza",
            "dummy",
            Component.text("👤 ROBAR CABEZA 👤", NamedTextColor.RED, TextDecoration.BOLD)
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        // Asignar scoreboard al jugador
        player.scoreboard = scoreboard
        playerBoards[player.uniqueId] = scoreboard
        
        // Iniciar tarea de actualización cada segundo (20 ticks)
        // El GlobalScoreboardService ya no interferirá porque el jugador está en excludedPlayers
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateScoreboard(player, game)
        }, 0L, 20L)
        
        updateTasks[player.uniqueId] = task
    }
    
    /**
     * Actualiza el contenido del scoreboard de un jugador.
     * Llamado cada segundo por el BukkitRunnable.
     */
    private fun updateScoreboard(player: Player, game: RobarCabezaGame) {
        val scoreboard = playerBoards[player.uniqueId] ?: return
        val objective = scoreboard.getObjective("robarcabeza") ?: return
        
        // Limpiar entradas anteriores
        scoreboard.entries.forEach { entry ->
            scoreboard.resetScores(entry)
        }
        
        // Obtener datos del juego
        val misPuntos = scoreService.getSessionScore(player.uniqueId)
        val tengoCabeza = game.playersWithTail.contains(player.uniqueId)
        val jugadoresConCabeza = game.playersWithTail.size
        val ranking = scoreService.getSessionRanking().take(3)
        
        // Construir scoreboard (de abajo hacia arriba por el sistema de scores)
        var line = 15
        
        // Línea inferior: Servidor
        objective.getScore("§7Torneo MMT").score = line--
        objective.getScore("§8§m                    ").score = line--
        
        // Top 3
        objective.getScore("§6⭐ Top 3:").score = line--
        ranking.forEachIndexed { index, (playerId, points) ->
            val rankPlayer = Bukkit.getPlayer(playerId)
            val name = rankPlayer?.name ?: "Desconocido"
            val shortName = if (name.length > 10) name.substring(0, 10) else name
            val medal = when (index) {
                0 -> "🥇"
                1 -> "🥈"
                2 -> "🥉"
                else -> ""
            }
            objective.getScore("§f$medal $shortName: §e$points").score = line--
        }
        
        objective.getScore("§7§m                    ").score = line--
        
        // Jugadores con cabeza
        objective.getScore("§c👤 Con cabeza: §f$jugadoresConCabeza").score = line--
        
        objective.getScore("§6§m                    ").score = line--
        
        // Estado del jugador
        if (tengoCabeza) {
            objective.getScore("§a✓ ¡Tienes cabeza!").score = line--
            objective.getScore("§7+2 pts cada 10s").score = line--
        } else {
            objective.getScore("§c✗ Sin cabeza").score = line--
        }
        
        objective.getScore("§9§m                    ").score = line--
        
        // Puntos del jugador
        objective.getScore("§eTus puntos: §f$misPuntos").score = line--
        
        objective.getScore("§d§m                    ").score = line--
    }
    
    /**
     * Oculta el scoreboard de Robar Cabeza y restaura el scoreboard global.
     */
    fun hideScoreboard(player: Player) {
        // Cancelar tarea de actualización
        updateTasks[player.uniqueId]?.cancel()
        updateTasks.remove(player.uniqueId)
        
        // Limpiar scoreboard
        playerBoards.remove(player.uniqueId)
        
        // Restaurar scoreboard global
        globalScoreboardService.showScoreboard(player)
    }
    
    /**
     * Limpia todos los scoreboards activos.
     */
    fun clearAll() {
        updateTasks.values.forEach { it.cancel() }
        updateTasks.clear()
        playerBoards.clear()
    }
}
