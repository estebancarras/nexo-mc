package cl.esteban.nexo.services

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.visuals.VisualChain
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID

/**
 * Servicio para visualizar las cadenas.
 */
class ChainVisualizerService(private val plugin: NexoPlugin) {
    
    // Mapa de cadenas visuales: "UUID1-UUID2" -> VisualChain
    private val activeChains = mutableMapOf<String, VisualChain>()
    
    init {
        startUpdateTask()
    }
    
    private fun startUpdateTask() {
        // Tarea periódica para asegurar que las cadenas visuales coincidan con los vínculos lógicos
        object : BukkitRunnable() {
            override fun run() {
                updateChains()
            }
        }.runTaskTimer(plugin, 40L, 40L) // Cada 2 segundos sincronización completa
    }
    
    /**
     * Sincroniza las cadenas visuales con el LinkManager.
     */
    fun updateChains() {
        val links = plugin.linkManager.getAllLinks()
        val activePairs = mutableSetOf<String>()
        
        // 1. Crear cadenas nuevas
        for ((uuid1, targets) in links) {
            val player1 = Bukkit.getPlayer(uuid1) ?: continue
            
            for (uuid2 in targets) {
                val player2 = Bukkit.getPlayer(uuid2) ?: continue
                
                val pairId = getPairId(uuid1, uuid2)
                activePairs.add(pairId)
                
                if (!activeChains.containsKey(pairId)) {
                    createChain(player1, player2, pairId)
                }
            }
        }
        
        // 2. Eliminar cadenas obsoletas
        val toRemove = mutableListOf<String>()
        for (pairId in activeChains.keys) {
            if (!activePairs.contains(pairId)) {
                toRemove.add(pairId)
            }
        }
        
        for (pairId in toRemove) {
            activeChains[pairId]?.destroy()
            activeChains.remove(pairId)
        }
    }
    
    private fun createChain(player1: Player, player2: Player, pairId: String) {
        val chain = VisualChain(plugin, player1, player2)
        chain.create()
        activeChains[pairId] = chain
    }
    
    private fun getPairId(uuid1: UUID, uuid2: UUID): String {
        return if (uuid1.toString() < uuid2.toString()) "$uuid1-$uuid2" else "$uuid2-$uuid1"
    }
    
    /**
     * Destruye las cadenas visuales de un jugador.
     */
    fun destroyChainsForPlayer(player: Player) {
        val uuid = player.uniqueId.toString()
        val toRemove = mutableListOf<String>()
        
        for (pairId in activeChains.keys) {
            if (pairId.contains(uuid)) {
                toRemove.add(pairId)
            }
        }
        
        for (pairId in toRemove) {
            activeChains[pairId]?.destroy()
            activeChains.remove(pairId)
        }
    }
    
    fun clearAllChains() {
        activeChains.values.forEach { it.destroy() }
        activeChains.clear()
    }
}
