package los5fantasticos.minigameCadena.services

import los5fantasticos.minigameCadena.MinigameCadena
import los5fantasticos.minigameCadena.game.Team
import los5fantasticos.minigameCadena.visuals.VisualChain
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Servicio centralizado para gestionar el ciclo de vida de las cadenas visuales.
 * 
 * Este servicio actúa como orquestador, asegurando que:
 * - Se creen cadenas lineales (A-B-C-D) en lugar de mallas completas
 * - Las cadenas se limpien correctamente cuando un jugador se desconecta
 * - Todos los recursos visuales se liberen al finalizar una partida
 * 
 * @property plugin Instancia del plugin
 */
class ChainVisualizerService(private val plugin: MinigameCadena) {
    
    /**
     * Lista de cadenas visuales activas.
     * Almacenadas en una lista simple ya que cada cadena es única entre dos jugadores.
     */
    private val activeChains = mutableListOf<VisualChain>()
    
    /**
     * Crea cadenas visuales lineales para un equipo.
     * 
     * Los jugadores se conectan en orden lineal: A <-> B <-> C <-> D
     * En lugar de una malla completa donde todos están conectados con todos.
     * 
     * @param team Equipo para el cual crear las cadenas
     */
    fun createChainsForTeam(team: Team) {
        val players = team.getOnlinePlayers()
        
        // Se necesitan al menos 2 jugadores para crear una cadena
        if (players.size < 2) {
            // Caso normal: equipos con 1 jugador no necesitan cadenas
            return
        }
        
        var chainsCreated = 0
        
        // LÓGICA LINEAL: Conectar jugador[i] con jugador[i+1]
        for (i in 0 until players.size - 1) {
            val playerA = players[i]
            val playerB = players[i + 1]
            
            // Crear la cadena visual
            val chain = VisualChain(plugin, playerA, playerB)
            chain.create()
            activeChains.add(chain)
            chainsCreated++
            
            plugin.plugin.logger.info("[ChainVisualizerService] Cadena creada: ${playerA.name} <-> ${playerB.name}")
        }
        
        plugin.plugin.logger.info("[ChainVisualizerService] ${chainsCreated} cadenas lineales creadas. Total de cadenas activas: ${activeChains.size}")
    }
    
    /**
     * Destruye todas las cadenas visuales asociadas a un jugador.
     * 
     * Se llama cuando un jugador se desconecta o es eliminado de la partida.
     * 
     * @param player Jugador cuyas cadenas deben destruirse
     */
    fun destroyChainsForPlayer(player: Player) {
        // Filtrar cadenas que involucran a este jugador
        val chainsToRemove = activeChains.filter { chain ->
            // Necesitamos acceder a los jugadores de la cadena
            // Como VisualChain no expone los jugadores, destruimos todas las cadenas
            // y las recreamos sin este jugador (esto se maneja en el GameManager)
            true // Por ahora destruimos todas, el equipo las recreará sin este jugador
        }
        
        // Destruir las cadenas
        chainsToRemove.forEach { chain ->
            chain.destroy()
        }
        
        // Remover de la lista
        activeChains.removeAll(chainsToRemove)
    }
    
    /**
     * Limpia todas las cadenas visuales activas.
     * 
     * Se llama al finalizar una partida o al desactivar el módulo.
     */
    fun clearAllChains() {
        // Destruir todas las cadenas
        activeChains.forEach { chain ->
            chain.destroy()
        }
        
        // Limpiar la lista
        activeChains.clear()
    }
    
    /**
     * Obtiene el número de cadenas visuales activas.
     * Útil para debugging y monitoreo.
     * 
     * @return Cantidad de cadenas activas
     */
    fun getActiveChainCount(): Int {
        return activeChains.size
    }
}
