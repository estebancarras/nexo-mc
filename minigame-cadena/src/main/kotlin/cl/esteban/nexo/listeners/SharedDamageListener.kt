package cl.esteban.nexo.listeners

import cl.esteban.nexo.NexoPlugin
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import java.util.UUID

class SharedDamageListener(private val plugin: NexoPlugin) : Listener {

    // Evita bucles infinitos de daño
    private val processingDamage = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageEvent) {
        if (event.entity !is Player) return
        val player = event.entity as Player

        // 1. Verificar configuración
        if (!plugin.config.getBoolean("settings.share-damage", false)) return

        // 2. Verificar si ya estamos procesando este daño (evitar bucle)
        if (processingDamage.contains(player.uniqueId)) return

        // 3. Obtener vinculados
        val linkedPlayers = plugin.linkManager.getLinkedPlayers(player)
        if (linkedPlayers.isEmpty()) return

        val damage = event.finalDamage
        if (damage <= 0) return

        // Marcar al jugador original como procesado para no reflejarle el daño de vuelta
        // (aunque la lógica de abajo no le aplica daño a él, es bueno por seguridad)
        processingDamage.add(player.uniqueId)

        try {
            for (linkedUUID in linkedPlayers) {
                val linkedPlayer = plugin.server.getPlayer(linkedUUID) ?: continue
                
                // Evitar dañar si ya está muerto o es el mismo jugador
                if (linkedPlayer.isDead || linkedPlayer.uniqueId == player.uniqueId) continue

                // Marcar al vinculado para que su evento de daño no dispare otra cadena
                processingDamage.add(linkedUUID)
                
                // Aplicar daño
                // Usamos damage() para que respete armadura si es posible, o setHealth si queremos daño puro.
                // El requerimiento dice "Aplicar el MISMO daño".
                // Si usamos player.damage(damage), Bukkit aplicará reducción de armadura de nuevo.
                // Para "compartir daño" usualmente se quiere que si A recibe 5 corazones, B reciba 5 corazones.
                // Pero si B tiene armadura de diamante y A no, ¿debería B recibir menos?
                // "Aplicar el MISMO daño" suele implicar daño directo o raw.
                // Sin embargo, player.damage(damage) es lo más estándar.
                // Si queremos que sea "True Damage" (ignora armadura), usamos setHealth (con cuidado de no bajar de 0).
                // Vamos a usar player.damage(damage) para que sea consistente con la fuente de daño,
                // pero como ya estamos en processingDamage, el evento disparado por esto será ignorado por este listener.
                linkedPlayer.damage(damage)
                
                // Desmarcar inmediatamente después (o al final del bloque try)
                // En realidad, el evento se dispara sincrónicamente dentro de .damage(),
                // así que al volver de .damage(), el evento ya ocurrió y fue ignorado.
                processingDamage.remove(linkedUUID)
            }
        } finally {
            processingDamage.remove(player.uniqueId)
        }
    }
}
