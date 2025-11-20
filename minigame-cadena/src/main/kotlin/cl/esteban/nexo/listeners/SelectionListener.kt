package cl.esteban.nexo.listeners

import cl.esteban.nexo.utils.SelectionManager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SelectionListener : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        
        // Solo mano principal para evitar doble evento
        if (event.hand != EquipmentSlot.HAND) return
        
        if (SelectionManager.isSelectionWand(item)) {
            // Cancelar interacción normal (romper/poner bloques)
            event.isCancelled = true
            
            val clickedBlock = event.clickedBlock ?: return
            
            if (event.action == Action.LEFT_CLICK_BLOCK) {
                SelectionManager.setPos1(player, clickedBlock.location)
            } else if (event.action == Action.RIGHT_CLICK_BLOCK) {
                SelectionManager.setPos2(player, clickedBlock.location)
            }
        }
    }
}
