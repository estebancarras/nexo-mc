package los5fantasticos.minigameCarrerabarcos.services

import los5fantasticos.minigameCarrerabarcos.game.Carrera
import los5fantasticos.torneo.TorneoPlugin
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

/**
 * Servicio de puntuación simplificado para Carrera de Barcos.
 * 
 * RESPONSABILIDADES:
 * - Gestionar puntos por posición final
 * - Registrar partidas jugadas y victorias
 * 
 * SISTEMA SIMPLIFICADO (Tier Bajo, PME ~70):
 * - Solo se otorgan puntos al cruzar la meta
 * - No hay puntos por checkpoints intermedios
 * - No hay bonus por tiempo
 * 
 * @author Los 5 Fantásticos
 * @since 3.0 (Simplificado)
 */
class ScoreService(
    private val plugin: Plugin,
    private val torneoPlugin: TorneoPlugin
) {
    
    companion object {
        private const val GAME_NAME = "Carrera de Barcos"
    }
    
    /**
     * Configuración de puntuación cargada desde carrerabarcos.yml
     */
    private var config: YamlConfiguration
    
    /**
     * Registro de jugadores que ya finalizaron (para evitar puntos duplicados).
     * Set<UUID>
     */
    private val finishedPlayers = mutableSetOf<UUID>()
    
    init {
        // Cargar configuración
        val configFile = File(plugin.dataFolder, "carrerabarcos.yml")
        
        if (!configFile.exists()) {
            plugin.saveResource("carrerabarcos.yml", false)
        }
        
        config = YamlConfiguration.loadConfiguration(configFile)
        plugin.logger.info("[Carrera de Barcos] Configuración de puntuación cargada")
    }
    
    /**
     * Reinicia el tracking para una nueva carrera.
     */
    fun resetForNewRace(carrera: Carrera) {
        finishedPlayers.clear()
        plugin.logger.info("[Carrera de Barcos] ScoreService reiniciado para nueva carrera")
    }
    

    /**
     * Calcula y otorga puntos cuando un jugador cruza la meta.
     * 
     * SISTEMA SIMPLIFICADO:
     * - Solo puntos por posición final
     * - Sin bonus por tiempo
     * - Sin puntos por checkpoints
     * 
     * @param player Jugador que cruzó la meta
     * @param posicionFinal Posición final (1 = primero)
     * @return Total de puntos otorgados
     */
    fun onPlayerFinished(player: Player, posicionFinal: Int): Int {
        val uuid = player.uniqueId
        
        // PROTECCIÓN: Si el jugador ya finalizó, no otorgar puntos de nuevo
        if (finishedPlayers.contains(uuid)) {
            plugin.logger.warning("[Carrera de Barcos] ScoreService: ${player.name} ya había finalizado - no se otorgan puntos")
            return 0
        }
        
        // Marcar como finalizado
        finishedPlayers.add(uuid)
        
        // Puntos por posición en meta
        val puntosPosicion = when (posicionFinal) {
            1 -> config.getInt("puntuacion.por-meta-primer-lugar", 60)
            2 -> config.getInt("puntuacion.por-meta-segundo-lugar", 40)
            3 -> config.getInt("puntuacion.por-meta-tercer-lugar", 25)
            else -> config.getInt("puntuacion.por-participacion", 10)
        }
        
        torneoPlugin.torneoManager.addScore(
            uuid,
            GAME_NAME,
            puntosPosicion,
            "Posición #$posicionFinal"
        )
        
        // Registrar victoria si es primer lugar
        if (posicionFinal == 1) {
            torneoPlugin.torneoManager.recordGameWon(player, GAME_NAME)
        }
        
        // Registrar partida jugada
        torneoPlugin.torneoManager.recordGamePlayed(player, GAME_NAME)
        
        plugin.logger.info("[Carrera de Barcos] ${player.name} finalizó en posición #$posicionFinal (+$puntosPosicion puntos)")
        
        return puntosPosicion
    }
    

    /**
     * Recarga la configuración desde el archivo.
     */
    fun reloadConfig() {
        val configFile = File(plugin.dataFolder, "carrerabarcos.yml")
        config = YamlConfiguration.loadConfiguration(configFile)
        plugin.logger.info("[Carrera de Barcos] Configuración recargada")
    }
    
    /**
     * Obtiene un valor de configuración.
     */
    fun getConfigValue(path: String, default: Any): Any {
        return when (default) {
            is Int -> config.getInt(path, default)
            is Double -> config.getDouble(path, default)
            is Boolean -> config.getBoolean(path, default)
            is String -> config.getString(path, default) ?: default
            else -> default
        }
    }
}
