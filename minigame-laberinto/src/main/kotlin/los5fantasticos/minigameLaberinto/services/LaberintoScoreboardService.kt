package los5fantasticos.minigameLaberinto.services

import los5fantasticos.minigameLaberinto.game.LaberintoGame
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
import java.util.UUID

/**
 * Servicio de scoreboard dedicado para el minijuego Laberinto.
 * 
 * Muestra información en tiempo real durante la partida:
 * - Arena actual
 * - Jugadores finalizados
 * - Posición del jugador (si ya finalizó)
 * - Jugadores restantes
 * 
 * Este scoreboard reemplaza temporalmente al scoreboard global del torneo.
 */
class LaberintoScoreboardService(
    private val plugin: Plugin,
    private val globalScoreboardService: GlobalScoreboardService
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
     * Muestra el scoreboard de Laberinto a un jugador.
     * Oculta el scoreboard global y comienza a actualizar el scoreboard del juego.
     */
    fun showScoreboard(player: Player, game: LaberintoGame) {
        // CRÍTICO: Ocultar scoreboard global PRIMERO para añadir a excludedPlayers
        globalScoreboardService.hideScoreboard(player)
        
        // Crear scoreboard de Bukkit
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        
        val objective = scoreboard.registerNewObjective(
            "laberinto",
            "dummy",
            Component.text("LABERINTO", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
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
    private fun updateScoreboard(player: Player, game: LaberintoGame) {
        val scoreboard = playerBoards[player.uniqueId] ?: return
        val objective = scoreboard.getObjective("laberinto") ?: return
        
        // Limpiar entradas anteriores
        scoreboard.entries.forEach { entry ->
            scoreboard.resetScores(entry)
        }
        
        // Obtener datos del juego
        val finalizados = game.getFinishedPlayers().size
        val totalJugadores = game.players.size
        val restantes = totalJugadores - finalizados
        val posicion = game.getPlayerFinishPosition(player)
        
        // Construir scoreboard (de abajo hacia arriba por el sistema de scores)
        var line = 13
        
        // Línea inferior: Servidor
        objective.getScore("§7Torneo MMT").score = line--
        objective.getScore("§8§m                    ").score = line--
        
        // Información del jugador
        if (posicion != null) {
            // El jugador ya finalizó
            val emoji = when (posicion) {
                1 -> "🥇"
                2 -> "🥈"
                3 -> "🥉"
                else -> "✓"
            }
            objective.getScore("§a$emoji Posición: §f#$posicion").score = line--
        } else {
            // El jugador aún está corriendo
            objective.getScore("§eEn carrera...").score = line--
        }
        
        objective.getScore("§7§m                    ").score = line--
        
        // Estadísticas de la partida
        objective.getScore("§6Jugadores:").score = line--
        objective.getScore("§f  Finalizados: §a$finalizados").score = line--
        objective.getScore("§f  Restantes: §e$restantes").score = line--
        
        objective.getScore("§6§m                    ").score = line--
        
        // Arena
        objective.getScore("§dArena: §f${game.arena.name}").score = line--
        
        objective.getScore("§9§m                    ").score = line--
    }
    
    /**
     * Oculta el scoreboard de Laberinto y restaura el scoreboard global.
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
