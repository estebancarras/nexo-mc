package cl.esteban.nexo

import cl.esteban.nexo.commands.CadenaCommand
import cl.esteban.nexo.listeners.LobbyListener
import cl.esteban.nexo.listeners.ParkourListener
import cl.esteban.nexo.listeners.PlayerQuitListener
import cl.esteban.nexo.listeners.SelectionListener
import cl.esteban.nexo.services.ArenaManager
import cl.esteban.nexo.services.CadenaScoreboardService
import cl.esteban.nexo.services.ChainService
import cl.esteban.nexo.services.ChainVisualizerService
import cl.esteban.nexo.services.GameManager
import cl.esteban.nexo.services.LobbyManager
import cl.esteban.nexo.services.ParkourService
import cl.esteban.nexo.services.ScoreService
import cl.esteban.nexo.game.CadenaGame
import org.bukkit.plugin.java.JavaPlugin

/**
 * Plugin principal Nexo.
 *
 * Sistema cooperativo competitivo donde equipos de 2-4 jugadores
 * están permanentemente unidos por una cadena invisible y deben
 * completar un recorrido de parkour coordinadamente.
 */
class NexoPlugin : JavaPlugin() {

    lateinit var gameManager: GameManager
        private set

    lateinit var lobbyManager: LobbyManager
        private set

    lateinit var chainService: ChainService
        private set

    lateinit var parkourService: ParkourService
        private set

    lateinit var arenaManager: ArenaManager
        private set

    lateinit var scoreService: ScoreService
        private set

    lateinit var chainVisualizerService: ChainVisualizerService
        private set

    lateinit var cadenaScoreboardService: CadenaScoreboardService
        private set

    override fun onEnable() {
        // Cargar configuración
        saveDefaultConfig()
        reloadConfig()

        // Inicializar ChainVisualizerService ANTES del GameManager (DI)
        chainVisualizerService = ChainVisualizerService(this)

        // Inicializar GameManager y LobbyManager
        gameManager = GameManager(this)
        lobbyManager = LobbyManager(this, gameManager)

        // Inicializar ChainService
        chainService = ChainService(this)

        // Inicializar ParkourService y ArenaManager
        parkourService = ParkourService(this)
        arenaManager = ArenaManager()
        arenaManager.initialize(dataFolder)

        // Inicializar ScoreService (Standalone: sin TorneoManager)
        scoreService = ScoreService(this)

        // Inicializar CadenaScoreboardService (Standalone: sin ScoreboardService externo)
        cadenaScoreboardService = CadenaScoreboardService(this)

        // Registrar listeners
        server.pluginManager.registerEvents(PlayerQuitListener(this), this)
        server.pluginManager.registerEvents(ParkourListener(this), this)
        server.pluginManager.registerEvents(LobbyListener(this), this)
        server.pluginManager.registerEvents(SelectionListener(), this)

        // Registrar comandos
        getCommand("nexo")?.setExecutor(CadenaCommand(this))

        logger.info("✓ Nexo v${description.version} habilitado")
    }

    override fun onDisable() {
        // Guardar arenas antes de limpiar
        if (::arenaManager.isInitialized) {
            arenaManager.saveArenas()
        }

        // Limpiar todos los managers
        if (::gameManager.isInitialized) {
            gameManager.clearAll()
        }
        if (::lobbyManager.isInitialized) {
            lobbyManager.clearAll()
        }
        if (::chainService.isInitialized) {
            chainService.clearAll()
        }
        if (::arenaManager.isInitialized) {
            arenaManager.clearAll()
        }
        if (::chainVisualizerService.isInitialized) {
            chainVisualizerService.clearAllChains()
        }

        logger.info("✓ Nexo deshabilitado")
    }
    
    /**
     * Verifica si se puede iniciar la cuenta atrás para una partida.
     */
    fun checkStartCountdown(game: CadenaGame) {
        lobbyManager.checkAndStartCountdown(game)
    }
}
