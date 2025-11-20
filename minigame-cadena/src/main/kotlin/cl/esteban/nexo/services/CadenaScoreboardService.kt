package cl.esteban.nexo.services

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.game.CadenaGame
import cl.esteban.nexo.game.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

/**
 * Servicio de scoreboard dedicado para el minijuego Cadena.
 * 
 * Muestra información en tiempo real durante la partida:
 * - Tiempo restante
 * - Progreso de checkpoints
 * - Posición del equipo
 * - Puntos acumulados en la partida
 * 
 * Este scoreboard reemplaza temporalmente al scoreboard global del torneo.
 */
class CadenaScoreboardService(
    private val minigame: NexoPlugin
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
     * Muestra el scoreboard de Cadena a un jugador.
     */
    fun showScoreboard(player: Player, game: CadenaGame) {
        // Crear scoreboard de Bukkit
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        
        // IMPORTANTE: Copiar los equipos del gameScoreboard para mantener las auras
        copyTeamsFromGameScoreboard(scoreboard)
        
        val objective = scoreboard.registerNewObjective(
            "cadena",
            "dummy",
            Component.text("NEXO", NamedTextColor.GOLD, TextDecoration.BOLD)
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        // Asignar scoreboard al jugador
        player.scoreboard = scoreboard
        playerBoards[player.uniqueId] = scoreboard
        
        // Iniciar tarea de actualización cada segundo (20 ticks)
        val task = Bukkit.getScheduler().runTaskTimer(minigame, Runnable {
            updateScoreboard(player, game)
        }, 0L, 20L)
        
        updateTasks[player.uniqueId] = task
    }
    
    /**
     * Actualiza el contenido del scoreboard de un jugador.
     * Llamado cada segundo por el BukkitRunnable.
     */
    private fun updateScoreboard(player: Player, game: CadenaGame) {
        val scoreboard = playerBoards[player.uniqueId] ?: return
        val objective = scoreboard.getObjective("cadena") ?: return
        
        // Obtener equipo del jugador
        val team = minigame.gameManager.getPlayerTeam(player) ?: return
        val arena = game.arena ?: return
        
        // Limpiar entradas anteriores
        scoreboard.entries.forEach { entry ->
            scoreboard.resetScores(entry)
        }
        
        // Obtener datos
        val progreso = minigame.parkourService.getTeamProgress(team, game)
        val totalCheckpoints = arena.checkpoints.size
        val ranking = minigame.parkourService.getTeamRankings(game)
        val posicion = ranking.indexOf(team) + 1
        val totalEquipos = game.teams.filter { it.players.isNotEmpty() }.size
        val puntosJuego = minigame.scoreService.getGamePoints(team.displayName)
        
        // Construir scoreboard (de abajo hacia arriba por el sistema de scores)
        var line = 13
        
        objective.getScore("§7Nexo Standalone").score = line--
        objective.getScore("§8§m                    ").score = line--
        objective.getScore("§6Puntos: §e$puntosJuego").score = line--
        objective.getScore("§7§m                    ").score = line--
        objective.getScore("§bPosición: §f$posicion§7/§f$totalEquipos").score = line--
        objective.getScore("§aProgreso: §f$progreso§7/§f$totalCheckpoints").score = line--
        objective.getScore("§eEquipo: ${team.displayName}").score = line--
        objective.getScore("§6§m                    ").score = line--
        objective.getScore("§7Arena: §f${arena.name}").score = line--
        objective.getScore("§9§m                    ").score = line--
    }
    
    /**
     * Oculta el scoreboard de Cadena.
     */
    fun hideScoreboard(player: Player) {
        // Cancelar tarea de actualización
        updateTasks[player.uniqueId]?.cancel()
        updateTasks.remove(player.uniqueId)
        
        // Limpiar scoreboard
        playerBoards.remove(player.uniqueId)
        
        // Restaurar scoreboard por defecto (vacío)
        player.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
    }
    
    /**
     * Limpia todos los scoreboards activos.
     */
    fun clearAll() {
        updateTasks.values.forEach { it.cancel() }
        updateTasks.clear()
        playerBoards.clear()
    }
    
    /**
     * Copia los equipos del gameScoreboard al scoreboard del jugador.
     * Esto preserva las auras de equipo (glow effect) mientras se muestra información del juego.
     */
    private fun copyTeamsFromGameScoreboard(targetScoreboard: Scoreboard) {
        // Standalone: No hay gameScoreboard centralizado
        return
        /*
        val gameScoreboard = minigame.gameManager.getActiveGames()
            .firstOrNull()
            ?.let { minigame.gameManager.getGameScoreboard() }
            ?: return
        
        // Copiar cada equipo del gameScoreboard
        gameScoreboard.teams.forEach { sourceTeam ->
            try {
                // Crear equipo en el scoreboard destino
                val targetTeam = targetScoreboard.registerNewTeam(sourceTeam.name)
                
                // Copiar propiedades (con cast seguro para el color)
                val teamColor = sourceTeam.color()
                if (teamColor is NamedTextColor) {
                    targetTeam.color(teamColor)
                }
                targetTeam.prefix(sourceTeam.prefix())
                targetTeam.suffix(sourceTeam.suffix())
                targetTeam.displayName(sourceTeam.displayName())
                
                // Copiar opciones
                targetTeam.setOption(
                    org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                    sourceTeam.getOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE)
                )
                targetTeam.setOption(
                    org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY,
                    sourceTeam.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY)
                )
                
                // Copiar entradas (jugadores)
                sourceTeam.entries.forEach { entry ->
                    targetTeam.addEntry(entry)
                }
            } catch (e: Exception) {
                minigame.logger.warning("Error copiando equipo ${sourceTeam.name}: ${e.message}")
            }
        }
        */
    }
    
    /**
     * Obtiene el gameScoreboard desde el GameManager.
     */
    private fun getGameScoreboard(): Scoreboard? {
        // Standalone: No hay gameScoreboard centralizado
        return null
        // return minigame.gameManager.getActiveGames()
        //     .firstOrNull()
        //     ?.let { minigame.gameManager.getGameScoreboard() }
    }
}
