package los5fantasticos.minigameCarrerabarcos.services

import los5fantasticos.minigameCarrerabarcos.game.Carrera
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
 * Servicio de scoreboard dedicado para el minijuego Carrera de Barcos.
 * 
 * Muestra información en tiempo real durante la carrera:
 * - Arena actual
 * - Progreso de checkpoints del jugador
 * - Jugadores finalizados
 * - Posición del jugador (si ya finalizó)
 * - Jugadores restantes
 * 
 * Este scoreboard reemplaza temporalmente al scoreboard global del torneo.
 */
class CarreraScoreboardService(
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
     * Muestra el scoreboard de Carrera de Barcos a un jugador.
     * Oculta el scoreboard global y comienza a actualizar el scoreboard de la carrera.
     */
    fun showScoreboard(player: Player, carrera: Carrera) {
        // CRÍTICO: Ocultar scoreboard global PRIMERO para añadir a excludedPlayers
        globalScoreboardService.hideScoreboard(player)
        
        // Crear scoreboard de Bukkit
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        
        val objective = scoreboard.registerNewObjective(
            "carrera",
            "dummy",
            Component.text("CARRERA BARCOS", NamedTextColor.AQUA, TextDecoration.BOLD)
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        // Asignar scoreboard al jugador
        player.scoreboard = scoreboard
        playerBoards[player.uniqueId] = scoreboard
        
        // Iniciar tarea de actualización cada segundo (20 ticks)
        // El GlobalScoreboardService ya no interferirá porque el jugador está en excludedPlayers
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateScoreboard(player, carrera)
        }, 0L, 20L)
        
        updateTasks[player.uniqueId] = task
    }
    
    /**
     * Actualiza el contenido del scoreboard de un jugador.
     * Llamado cada segundo por el BukkitRunnable.
     */
    private fun updateScoreboard(player: Player, carrera: Carrera) {
        val scoreboard = playerBoards[player.uniqueId] ?: return
        val objective = scoreboard.getObjective("carrera") ?: return
        
        // Limpiar entradas anteriores
        scoreboard.entries.forEach { entry ->
            scoreboard.resetScores(entry)
        }
        
        // Obtener datos de la carrera
        val racePlayer = carrera.getRacePlayer(player)
        val progreso = carrera.getProgreso(player)
        val totalCheckpoints = carrera.arena.checkpoints.size
        val finalizados = carrera.getJugadoresFinalizados().size
        val totalJugadores = carrera.getCantidadJugadores()
        val restantes = totalJugadores - finalizados
        val posicion = racePlayer?.finalPosition
        
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
            objective.getScore("§e⚡ En carrera...").score = line--
            objective.getScore("§bCheckpoints: §f$progreso§7/§f$totalCheckpoints").score = line--
        }
        
        objective.getScore("§7§m                    ").score = line--
        
        // Estadísticas de la carrera
        objective.getScore("§6Jugadores:").score = line--
        objective.getScore("§f  Finalizados: §a$finalizados").score = line--
        objective.getScore("§f  Restantes: §e$restantes").score = line--
        
        objective.getScore("§6§m                    ").score = line--
        
        // Arena
        objective.getScore("§dArena: §f${carrera.arena.nombre}").score = line--
        
        objective.getScore("§9§m                    ").score = line--
    }
    
    /**
     * Oculta el scoreboard de Carrera de Barcos y restaura el scoreboard global.
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
