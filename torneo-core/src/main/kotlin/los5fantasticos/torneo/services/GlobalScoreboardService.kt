package los5fantasticos.torneo.services

import los5fantasticos.torneo.core.TorneoManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team

/**
 * Servicio global de scoreboard para el torneo.
 * 
 * Responsabilidades:
 * - Crear y mantener un scoreboard único para todos los jugadores
 * - Actualizar el ranking top 10 cada 4 segundos
 * - Usar técnica de Team para evitar parpadeo en las actualizaciones
 * - Usar Adventure API exclusivamente (CERO ChatColor)
 * - Detectar jugadores en mapas de SkyWars para no interferir con su scoreboard
 * 
 * Arquitectura:
 * - Un único Scoreboard compartido
 * - Teams invisibles pre-registrados para cada línea
 * - Actualización mediante modificación de prefix/suffix de Teams
 * - Todos los textos son Component (Adventure API)
 */
class GlobalScoreboardService(
    private val plugin: Plugin,
    private val torneoManager: TorneoManager
) {
    
    private lateinit var scoreboard: Scoreboard
    private lateinit var objective: Objective
    private val lineTeams = mutableMapOf<Int, Team>()
    private var updateTask: BukkitTask? = null
    
    /**
     * Set de jugadores que están usando scoreboards personalizados de minijuegos.
     * Estos jugadores no recibirán el scoreboard global automáticamente.
     */
    private val excludedPlayers = mutableSetOf<java.util.UUID>()
    
    /**
     * Inicializa el servicio de scoreboard.
     * Crea el scoreboard, objetivo y teams pre-configurados.
     */
    fun initialize() {
        // Crear scoreboard único
        scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
        
        // Crear objetivo con título estilizado usando Adventure API
        val title = Component.text("⭐ ", NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text("TorneoMMT ", NamedTextColor.YELLOW, TextDecoration.BOLD))
            .append(Component.text("⭐", NamedTextColor.GOLD, TextDecoration.BOLD))
        
        objective = scoreboard.registerNewObjective(
            "torneo_global",
            Criteria.DUMMY,
            title
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        
        // Pre-registrar teams para cada línea (10 líneas de ranking + espacios)
        for (i in 0..12) {
            val team = scoreboard.registerNewTeam("line_$i")
            team.addEntry(getColorCode(i))
            lineTeams[i] = team
        }
        
        plugin.logger.info("✓ GlobalScoreboardService inicializado")
    }
    
    /**
     * Asigna el scoreboard a un jugador.
     * No asigna si el jugador está excluido, en un mapa de SkyWars o en un duelo de Memorias.
     * 
     * @param player Jugador que verá el scoreboard
     */
    fun showToPlayer(player: Player) {
        try {
            
            // No asignar si el jugador está excluido
            if (excludedPlayers.contains(player.uniqueId)) {
                return
            }
            
            // No asignar si el jugador está en un mapa de SkyWars
            val inSkyWars = isPlayerInSkyWars(player)
            if (inSkyWars) {
                return
            }
            
            // No asignar si el jugador está en un duelo de Memorias
            val inMemorias = isPlayerInMemorias(player)
            if (inMemorias) {
                return
            }
            
            player.scoreboard = scoreboard
        } catch (e: Exception) {
            plugin.logger.warning("Error al asignar scoreboard a ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Asigna el scoreboard a todos los jugadores online.
     * Útil para recuperación masiva.
     */
    fun showToAllPlayers() {
        Bukkit.getOnlinePlayers().forEach { player ->
            showToPlayer(player)
        }
        plugin.logger.info("Scoreboard asignado a ${Bukkit.getOnlinePlayers().size} jugadores")
    }
    
    /**
     * Inicia la tarea repetitiva de actualización.
     * Se ejecuta cada 4 segundos (80 ticks).
     * Incluye re-asignación automática cada 30 segundos para prevenir desaparición.
     */
    fun startUpdating() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                updateScoreboard()
                
                // Cada 30 segundos (cada 7-8 actualizaciones), re-asignar a todos
                // Esto previene que el scoreboard desaparezca por conflictos
                if (System.currentTimeMillis() % 30000 < 4000) {
                    reassignToAllPlayers()
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error al actualizar scoreboard: ${e.message}")
                e.printStackTrace()
            }
        }, 0L, 80L) // 80 ticks = 4 segundos
        
        plugin.logger.info("✓ Tarea de actualización de scoreboard iniciada")
    }
    
    /**
     * Re-asigna silenciosamente el scoreboard a todos los jugadores.
     * Solo lo hace si el jugador no tiene el scoreboard correcto.
     * Respeta a los jugadores excluidos (en minijuegos).
     * También respeta a los jugadores que están en mapas de SkyWars o duelos de Memorias.
     */
    private fun reassignToAllPlayers() {
        Bukkit.getOnlinePlayers().forEach { player ->
            
            // No re-asignar si el jugador está excluido (usando scoreboard de minijuego)
            if (excludedPlayers.contains(player.uniqueId)) {
                return@forEach
            }
            
            // No re-asignar si el jugador está en un mapa de SkyWars
            if (isPlayerInSkyWars(player)) {
                return@forEach
            }
            
            // No re-asignar si el jugador está en un duelo de Memorias
            if (isPlayerInMemorias(player)) {
                return@forEach
            }
            
            // Re-asignar solo si el jugador no tiene el scoreboard correcto
            if (player.scoreboard != scoreboard) {
                showToPlayer(player)
            } else {
            }
        }
    }
    
    /**
     * Actualiza el contenido del scoreboard con el top 10.
     * Usa la técnica de Teams para evitar parpadeo.
     * Todos los textos usan Adventure API.
     */
    private fun updateScoreboard() {
        val ranking = torneoManager.getGlobalRanking(10)
        
        // Línea 12: Espacio superior
        setLine(12, Component.empty())
        
        // Líneas 11-2: Top 10 jugadores
        ranking.forEachIndexed { index, playerScore ->
            val position = index + 1
            val medal = when (position) {
                1 -> Component.text("1°", NamedTextColor.GOLD, TextDecoration.BOLD)
                2 -> Component.text("2°", NamedTextColor.GRAY, TextDecoration.BOLD)
                3 -> Component.text("3°", NamedTextColor.GOLD, TextDecoration.BOLD)
                else -> Component.text("${position}°", NamedTextColor.WHITE)
            }
            
            val name = playerScore.playerName.let {
                if (it.length > 12) it.substring(0, 12) else it
            }
            
            val line = Component.text("  ")
                .append(medal)
                .append(Component.text(" "))
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text("${playerScore.totalPoints}pts", NamedTextColor.YELLOW))
            
            setLine(11 - index, line)
        }
        
        // Limpiar líneas no usadas si hay menos de 10 jugadores
        for (i in (10 - ranking.size) downTo 2) {
            setLine(i, Component.empty())
        }
        
        // Línea 1: Espacio inferior
        setLine(1, Component.empty())
        
        // Línea 0: Pie de página
        val footer = Component.text("FiveDevStudios", NamedTextColor.GRAY, TextDecoration.ITALIC)
        setLine(0, footer)
    }
    
    /**
     * Establece el contenido de una línea usando Teams con Adventure API.
     * 
     * @param line Número de línea (0-12)
     * @param content Contenido de la línea (Component)
     */
    private fun setLine(line: Int, content: Component) {
        val team = lineTeams[line] ?: return
        val entry = getColorCode(line)
        
        // Establecer el score para posicionar la línea
        objective.getScore(entry).score = line
        
        // Actualizar el contenido mediante prefix usando Adventure API
        team.prefix(content)
    }
    
    /**
     * Obtiene un código de color único para cada línea.
     * Usado como entrada invisible del team.
     */
    private fun getColorCode(line: Int): String {
        val colors = listOf(
            "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7",
            "§8", "§9", "§a", "§b", "§c"
        )
        return colors.getOrElse(line) { "§f" } + "§r"
    }
    
    /**
     * Oculta el scoreboard global de un jugador.
     * Útil cuando un minijuego quiere mostrar su propio scoreboard.
     * Añade al jugador a la lista de excluidos para evitar re-asignación automática.
     */
    fun hideScoreboard(player: Player) {
        try {
            // Añadir a la lista de excluidos
            excludedPlayers.add(player.uniqueId)
            
            // Crear un scoreboard vacío temporal
            val emptyScoreboard = Bukkit.getScoreboardManager()!!.newScoreboard
            player.scoreboard = emptyScoreboard
        } catch (e: Exception) {
            plugin.logger.warning("Error al ocultar scoreboard de ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Muestra el scoreboard global a un jugador y lo remueve de la lista de excluidos.
     */
    fun showScoreboard(player: Player) {
        // Remover de la lista de excluidos
        excludedPlayers.remove(player.uniqueId)
        
        // Mostrar el scoreboard global
        showToPlayer(player)
    }
    
    /**
     * Detiene el servicio y cancela la tarea de actualización.
     */
    fun shutdown() {
        updateTask?.cancel()
        updateTask = null
        plugin.logger.info("✓ GlobalScoreboardService detenido")
    }

    /**
     * Verifica si un jugador está en un duelo de Memorias.
     * Utiliza reflexión para acceder al módulo de Memorias de forma segura.
     * 
     * @param player El jugador a verificar
     * @return true si el jugador está en un duelo de Memorias, false en caso contrario
     */
    private fun isPlayerInMemorias(player: Player): Boolean {
        try {
            // Intentar obtener el plugin torneo-mmt
            val torneoPlugin = Bukkit.getPluginManager().getPlugin("torneo-mmt")
            if (torneoPlugin == null || !torneoPlugin.isEnabled) {
                return false
            }
            
            // Usar reflexión para acceder al MemoriasManager
            val torneoClass = Class.forName("los5fantasticos.torneo.TorneoPlugin")
            val getModuleMethod = torneoClass.getMethod("getModule", String::class.java)
            val memoriasModule = getModuleMethod.invoke(torneoPlugin, "Memorias")
            
            if (memoriasModule != null) {
                // Llamar al método isPlayerInDuel
                val isPlayerInDuelMethod = memoriasModule.javaClass.getMethod("isPlayerInDuel", Player::class.java)
                val inDuel = isPlayerInDuelMethod.invoke(memoriasModule, player) as? Boolean
                
                return inDuel ?: false
            }
        } catch (e: Exception) {
            // Si hay algún error, asumimos que el jugador no está en Memorias
            // No logueamos para evitar spam en consola
        }
        
        return false
    }
    
    /**
     * Verifica si un jugador está en un mapa de SkyWars.
     * Utiliza reflexión para acceder al módulo de SkyWars de forma segura.
     * 
     * @param player El jugador a verificar
     * @return true si el jugador está en un mapa de SkyWars, false en caso contrario
     */
    private fun isPlayerInSkyWars(player: Player): Boolean {
        try {
            // Intentar obtener el plugin Skywars (nombre real del plugin)
            val skyWarsPlugin = Bukkit.getPluginManager().getPlugin("Skywars")
            
            if (skyWarsPlugin == null) {
                return false
            }
            
            if (!skyWarsPlugin.isEnabled) {
                return false
            }
            
            
            // Usar reflexión para llamar a SkyWarsReloaded.getGameMapMgr().getMapsCopy()
            val skyWarsClass = Class.forName("com.walrusone.skywarsreloaded.SkyWarsReloaded")
            val getGameMapMgrMethod = skyWarsClass.getMethod("getGameMapMgr")
            val gameMapMgr = getGameMapMgrMethod.invoke(null)
            
            if (gameMapMgr != null) {
                // Obtener la lista de mapas
                val getMapsCopyMethod = gameMapMgr.javaClass.getMethod("getMapsCopy")
                val maps = getMapsCopyMethod.invoke(gameMapMgr) as? List<*>
                
                
                if (maps != null) {
                    // Iterar sobre cada mapa y verificar si el jugador está en él
                    for (gameMap in maps) {
                        if (gameMap != null) {
                            val getNameMethod = gameMap.javaClass.getMethod("getName")
                            val mapName = getNameMethod.invoke(gameMap) as? String
                            
                            val getAllPlayersMethod = gameMap.javaClass.getMethod("getAllPlayers")
                            val playersInMap = getAllPlayersMethod.invoke(gameMap) as? List<*>
                            
                            
                            if (playersInMap != null && playersInMap.contains(player)) {
                                return true
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            // Si hay algún error (clase no encontrada, método no existe, etc.), 
            // asumimos que el jugador no está en SkyWars
            e.printStackTrace()
        }
        
        return false
    }
}
