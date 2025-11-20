package los5fantasticos.memorias

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
 * Servicio de scoreboard dedicado para el minijuego Memorias.
 * 
 * Muestra información en tiempo real durante el duelo:
 * - Nombre del oponente
 * - Pares encontrados por cada jugador
 * - Tiempo restante de cada jugador
 * - Indicador de turno actual
 * 
 * Este scoreboard reemplaza temporalmente al scoreboard global del torneo.
 */
class MemoriasScoreboardService(
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
     * Muestra el scoreboard de Memorias a un jugador.
     * Oculta el scoreboard global y comienza a actualizar el scoreboard del duelo.
     */
    fun showScoreboard(player: Player, duelo: DueloMemorias) {
        // CRÍTICO: Ocultar scoreboard global PRIMERO para añadir a excludedPlayers
        globalScoreboardService.hideScoreboard(player)
        
        // Crear scoreboard de Bukkit
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        
        val objective = scoreboard.registerNewObjective(
            "memorias",
            "dummy",
            Component.text("MEMORIAS", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        // Asignar scoreboard al jugador
        player.scoreboard = scoreboard
        playerBoards[player.uniqueId] = scoreboard
        
        // Iniciar tarea de actualización cada segundo (20 ticks)
        // El GlobalScoreboardService ya no interferirá porque el jugador está en excludedPlayers
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            updateScoreboard(player, duelo)
        }, 0L, 20L)
        
        updateTasks[player.uniqueId] = task
    }
    
    /**
     * Actualiza el contenido del scoreboard de un jugador.
     * Llamado cada segundo por el BukkitRunnable.
     */
    private fun updateScoreboard(player: Player, duelo: DueloMemorias) {
        val scoreboard = playerBoards[player.uniqueId] ?: return
        val objective = scoreboard.getObjective("memorias") ?: return
        
        // Obtener datos del duelo
        val oponente = if (duelo.player1 == player) duelo.player2 else duelo.player1
        val paresJugador = duelo.getPuntuacion(player)
        val paresOponente = duelo.getPuntuacion(oponente)
        val estado = duelo.getEstado()
        
        // Limpiar entradas anteriores
        scoreboard.entries.forEach { entry ->
            scoreboard.resetScores(entry)
        }
        
        // Construir scoreboard (de abajo hacia arriba por el sistema de scores)
        var line = 13
        
        // Línea inferior: Servidor
        objective.getScore("§7Torneo MMT").score = line--
        objective.getScore("§8§m                    ").score = line--
        
        // Estado del duelo
        when (estado) {
            DueloEstado.MEMORIZANDO -> {
                objective.getScore("§e⏱ Memorizando...").score = line--
                objective.getScore("§7Observa el tablero").score = line--
            }
            DueloEstado.JUGANDO -> {
                // Indicador de turno
                val turnoActual = duelo.getTurnoActual()
                if (turnoActual == player.uniqueId) {
                    objective.getScore("§a➤ TU TURNO").score = line--
                } else {
                    objective.getScore("§7Esperando turno...").score = line--
                }
            }
            DueloEstado.FINALIZADO -> {
                objective.getScore("§6¡Duelo Finalizado!").score = line--
            }
        }
        
        objective.getScore("§7§m                    ").score = line--
        
        // Pares encontrados
        objective.getScore("§d⭐ Pares Encontrados:").score = line--
        objective.getScore("§f  ${player.name}: §b$paresJugador").score = line--
        objective.getScore("§f  ${oponente.name}: §c$paresOponente").score = line--
        
        objective.getScore("§6§m                    ").score = line--
        
        // Oponente
        objective.getScore("§eVS: §f${oponente.name}").score = line--
        
        objective.getScore("§9§m                    ").score = line--
    }
    
    /**
     * Oculta el scoreboard de Memorias y restaura el scoreboard global.
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
