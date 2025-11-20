package cl.esteban.nexo.listeners

import cl.esteban.nexo.NexoPlugin
import cl.esteban.nexo.game.CadenaGame
import cl.esteban.nexo.game.GameState
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.entity.Player

/**
 * Listener que maneja protecciones durante el parkour.
 * 
 * Responsabilidades:
 * - Prevenir daño por caída durante el juego
 * - Prevenir PvP durante el juego
 * - Prevenir otros tipos de daño no deseados
 */
class ParkourListener(private val minigame: NexoPlugin) : Listener {
    
    /**
     * Previene daño por caída durante el juego.
     */
    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        // Solo procesar jugadores
        val player = event.entity as? Player ?: return
        
        // Verificar si el jugador está en una partida
        val game = minigame.gameManager.getPlayerGame(player) ?: return
        
        // Solo proteger durante LOBBY, COUNTDOWN e IN_GAME
        if (game.state != GameState.LOBBY && 
            game.state != GameState.COUNTDOWN && 
            game.state != GameState.IN_GAME) {
            return
        }
        
        // Cancelar daño por caída
        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            event.isCancelled = true
            return
        }
        
        // Cancelar daño por void (por si acaso)
        if (event.cause == EntityDamageEvent.DamageCause.VOID) {
            event.isCancelled = true
            return
        }
    }
    
    /**
     * Previene PvP durante el juego.
     */
    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        // Solo procesar si ambos son jugadores
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: return
        
        // Verificar si el jugador está en una partida
        val game = minigame.gameManager.getPlayerGame(victim) ?: return
        
        // Solo proteger durante LOBBY, COUNTDOWN e IN_GAME
        if (game.state != GameState.LOBBY && 
            game.state != GameState.COUNTDOWN && 
            game.state != GameState.IN_GAME) {
            return
        }
        
        // Cancelar PvP
        event.isCancelled = true
    }
    
    // COMENTADO: Ahora usamos un BukkitRunnable en GameManager para detección de regiones
    // Este método era muy costoso (lag) y se ejecutaba en cada movimiento
    /*
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        
        // Verificar si el jugador está en una partida
        val game = minigame.gameManager.getPlayerGame(player) ?: return
        
        // Solo procesar si la partida está en curso
        if (game.state != GameState.IN_GAME) {
            return
        }
        
        // Verificar si hay arena configurada
        if (game.arena == null) {
            return
        }
        
        // Optimización: Solo procesar si hubo movimiento significativo
        val from = event.from
        val to = event.to ?: return
        
        // Si no se movió (solo rotación de cabeza), ignorar
        if (from.x == to.x && from.y == to.y && from.z == to.z) {
            return
        }
        
        // Verificar caída
        minigame.parkourService.checkFall(player, game)
        
        // Verificar checkpoint (solo si se movió horizontalmente)
        if (from.x != to.x || from.z != to.z) {
            minigame.parkourService.checkCheckpoint(player, game)
        }
    }
    */
}
