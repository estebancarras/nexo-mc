package los5fantasticos.minigameColiseo.listeners

import los5fantasticos.minigameColiseo.game.GameState
import los5fantasticos.minigameColiseo.game.TeamType
import los5fantasticos.minigameColiseo.services.ColiseoScoreboardService
import los5fantasticos.minigameColiseo.services.GameManager
import los5fantasticos.minigameColiseo.services.KitService
import los5fantasticos.minigameColiseo.services.ScoreService
import los5fantasticos.minigameColiseo.services.TeamManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Zombie
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.entity.Item
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin

/**
 * Listener de eventos del Coliseo.
 * 
 * Responsabilidades:
 * - Manejar muertes (eliminación permanente, sin respawn)
 * - Controlar construcción (colocación/rotura de bloques)
 * - Manejar desconexiones
 */
class GameListener(
    private val plugin: Plugin,
    private val gameManager: GameManager,
    private val teamManager: TeamManager,
    private val kitService: KitService,
    private val scoreService: ScoreService,
    private val coliseoScoreboardService: ColiseoScoreboardService
) : Listener {
    
    /**
     * Maneja la muerte de un jugador.
     * TODOS los jugadores son eliminados permanentemente (sin respawn).
     */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si el jugador está en la partida
        if (!game.getAllPlayers().contains(player.uniqueId)) return
        
        // IMPORTANTE: Mantener los drops (NO limpiar event.drops)
        // Esto permite que los ítems caigan naturalmente
        event.keepInventory = false // Asegurar que los ítems caigan
        event.droppedExp = 0
        
        // Log para debug
        plugin.logger.info("[Coliseo] ${player.name} murió. Drops: ${event.drops.size} ítems")
        
        // Obtener killer para puntos y registro de kills
        val killer = player.killer
        if (killer != null && game.getAllPlayers().contains(killer.uniqueId)) {
            // Determinar equipos para puntuación diferenciada
            val killerTeam = if (game.isElite(killer.uniqueId)) TeamType.ELITE else TeamType.HORDE
            val victimTeam = if (game.isElite(player.uniqueId)) TeamType.ELITE else TeamType.HORDE
            
            // Otorgar puntos según el sistema V4.1
            scoreService.awardKillBonus(killer, player, killerTeam, victimTeam)
            coliseoScoreboardService.recordKill(killer)
        }
        
        // Determinar equipo para el mensaje
        val teamName = if (game.isElite(player.uniqueId)) {
            "${ChatColor.GOLD}[ÉLITE]"
        } else {
            "${ChatColor.WHITE}[HORDA]"
        }
        
        plugin.logger.info("[Coliseo] Jugador eliminado: ${player.name}")
        
        // Marcar como eliminado (pero NO remover de la lista principal)
        teamManager.markAsEliminated(player.uniqueId, game)
        
        // Anunciar eliminación a todos
        game.getAllPlayers().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.sendMessage(
                "$teamName ${ChatColor.WHITE}${player.name} ${ChatColor.RED}ha sido eliminado!"
            )
        }

        // Forzar respawn y convertir en espectador de forma inmediata en el siguiente tick.
        // Esto evita que el jugador quede en pantalla de muerte o con estado inconsistente.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable

            try {
                // Forzar respawn (Spigot API). Si no está disponible, el PlayerRespawnEvent manejará el cambio.
                player.spigot().respawn()
            } catch (e: NoSuchMethodError) {
                // Método no disponible en algunas APIs - ignorar y confiar en PlayerRespawnEvent
            } catch (t: Throwable) {
                plugin.logger.warning("[Coliseo] No se pudo forzar respawn para ${player.name}: ${t.message}")
            }

            // Teletransportar al spawn de espectadores y establecer modo espectador si está disponible
            game.arena?.getSpectatorSpawnLocation()?.let { spawn ->
                try {
                    player.teleport(spawn)
                } catch (_: Exception) {
                    // Teleport puede fallar si el jugador aún no está completamente respawneado; PlayerRespawnEvent lo fijará.
                }
            }

            // Establecer modo espectador
            try { player.gameMode = GameMode.SPECTATOR } catch (_: Exception) {}

            // Mostrar el scoreboard del Coliseo al espectador (si no lo tenía)
            try { coliseoScoreboardService.showScoreboard(player) } catch (_: Exception) {}
        })
    }
    
    /**
     * Maneja el respawn del jugador para ponerlo en modo espectador.
     * PRIORIDAD ALTA para evitar que otros listeners interfieran.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    fun onPlayerRespawn(event: org.bukkit.event.player.PlayerRespawnEvent) {
        val player = event.player
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si el jugador está en la partida y fue eliminado
        if (!game.getAllPlayers().contains(player.uniqueId)) return
        if (!game.eliminatedPlayers.contains(player.uniqueId)) return
        
        plugin.logger.info("[Coliseo] ===== RESPAWN INTERCEPTADO =====")
        plugin.logger.info("[Coliseo] Jugador: ${player.name}")
        plugin.logger.info("[Coliseo] Estado: Eliminado, convirtiéndolo en espectador")
        
        // IMPORTANTE: Establecer ubicación de respawn en el spawn de espectadores
        game.arena?.let { arena ->
            val spectatorSpawn = arena.getSpectatorSpawnLocation()
            if (spectatorSpawn != null) {
                event.respawnLocation = spectatorSpawn
                plugin.logger.info("[Coliseo] Respawn location establecida: ${spectatorSpawn.blockX}, ${spectatorSpawn.blockY}, ${spectatorSpawn.blockZ}")
            } else {
                plugin.logger.warning("[Coliseo] No se pudo obtener spawn de espectador")
            }
        }
        
        // Poner en modo espectador INMEDIATAMENTE (sin delay)
        // Esto previene que otros sistemas lo procesen
        player.gameMode = GameMode.SPECTATOR

    // Asegurar que el jugador tenga el scoreboard del Coliseo (espectador)
    try { coliseoScoreboardService.showScoreboard(player) } catch (_: Exception) {}
        
        // Mensaje y confirmación en el siguiente tick
        Bukkit.getScheduler().runTask(plugin, Runnable {
            player.sendMessage("")
            player.sendMessage("${ChatColor.RED}${ChatColor.BOLD}═══ ELIMINADO ═══")
            player.sendMessage("${ChatColor.GRAY}Has sido eliminado de la partida")
            player.sendMessage("${ChatColor.YELLOW}Ahora eres espectador hasta que termine el juego")
            player.sendMessage("")
            
            plugin.logger.info("[Coliseo] ${player.name} ahora es espectador en la arena")
        })
    }
    
    /**
     * Maneja la desconexión de jugadores.
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Si es de la Élite, contar como eliminación
        if (game.isElite(player.uniqueId)) {
            teamManager.markAsEliminated(player.uniqueId, game)
            
            game.getAllPlayers().forEach { playerId ->
                Bukkit.getPlayer(playerId)?.sendMessage(
                    "${ChatColor.GOLD}[ÉLITE] ${ChatColor.WHITE}${player.name} ${ChatColor.RED}se ha desconectado (eliminado)"
                )
            }
        }
    }
    
    /**
     * Maneja la colocación de bloques.
     */
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si el jugador está en la partida
        if (!game.getAllPlayers().contains(player.uniqueId)) return
        
        val block = event.block
        val material = block.type
        
        // Verificar si el bloque es permitido
        val allowedMaterials = if (game.isElite(player.uniqueId)) {
            setOf(Material.CYAN_TERRACOTTA)
        } else {
            setOf(Material.WHITE_WOOL, Material.LAVA)
        }
        
        if (material in allowedMaterials) {
            // Añadir a la lista de bloques colocados
            game.placedBlocks.add(block)
        } else {
            // Cancelar si no es un bloque permitido
            event.isCancelled = true
            player.sendMessage("${ChatColor.RED}¡No puedes colocar ese bloque!")
        }
    }
    
    /**
     * Maneja la rotura de bloques.
     */
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si el jugador está en la partida
        if (!game.getAllPlayers().contains(player.uniqueId)) return
        
        val block = event.block
        
        // Solo permitir romper bloques que fueron colocados durante la partida
        if (!game.placedBlocks.contains(block)) {
            event.isCancelled = true
            player.sendMessage("${ChatColor.RED}¡Solo puedes romper bloques colocados durante la partida!")
        } else {
            // Remover de la lista
            game.placedBlocks.remove(block)
        }
    }
    
    /**
     * Rastrea los ítems que aparecen en la arena durante la partida.
     */
    @EventHandler
    fun onItemSpawn(event: ItemSpawnEvent) {
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        val item = event.entity
        val arena = game.arena ?: return
        
        // Verificar si el ítem está dentro de la arena
        // Asumiendo que la arena tiene un método para verificar si una ubicación está dentro
        // Si no existe, simplemente rastreamos todos los ítems durante la partida
        game.droppedItems.add(item)
    }
    
    /**
     * Controla el PvP entre jugadores del mismo equipo.
     * 
     * Reglas:
     * - La HORDA no puede atacarse entre sí (trabajan en equipo)
     * - La ÉLITE SÍ puede atacarse entre sí (son egoístas, cada uno por su cuenta)
     */
    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val game = gameManager.getActiveGame() ?: return
        
        if (game.state != GameState.IN_GAME) return
        
        // Verificar que ambos sean jugadores
        val damager = event.damager as? Player ?: return
        val victim = event.entity as? Player ?: return
        
        // Verificar que ambos estén en la partida
        if (!game.getAllPlayers().contains(damager.uniqueId)) return
        if (!game.getAllPlayers().contains(victim.uniqueId)) return
        
        // Determinar equipos
        val damagerIsElite = game.isElite(damager.uniqueId)
        val victimIsElite = game.isElite(victim.uniqueId)
        
        // Si ambos son de la HORDA, cancelar el daño
        if (!damagerIsElite && !victimIsElite) {
            event.isCancelled = true
            damager.sendMessage("${ChatColor.RED}¡No puedes atacar a tu compañero de la Horda!")
            plugin.logger.info("[Coliseo] PvP bloqueado: ${damager.name} intentó atacar a ${victim.name} (ambos Horda)")
            return
        }
        
        // Si ambos son de la ÉLITE, permitir el daño (son egoístas)
        if (damagerIsElite && victimIsElite) {
            // Mensaje opcional para recordar la dinámica
            if (Math.random() < 0.1) { // 10% de probabilidad para no spamear
                damager.sendMessage("${ChatColor.GOLD}${ChatColor.ITALIC}La Élite no conoce la lealtad...")
            }
            plugin.logger.info("[Coliseo] PvP permitido: ${damager.name} atacó a ${victim.name} (ambos Élite)")
        }
        
        // Si son de equipos diferentes, siempre permitir (PvP normal)
    }
    
    /**
     * Restringe el movimiento de espectadores dentro de los límites de la arena.
     * Evita que los espectadores puedan viajar y ver otros juegos.
     */
    @EventHandler
    fun onSpectatorMove(event: org.bukkit.event.player.PlayerMoveEvent) {
        val player = event.player
        val game = gameManager.getActiveGame() ?: return
        
        // Solo aplicar durante la partida
        if (game.state != GameState.IN_GAME) return
        
        // Solo aplicar a espectadores (jugadores muertos en modo espectador)
        if (player.gameMode != GameMode.SPECTATOR) return
        
        // Verificar si el jugador está en la partida (como espectador)
        if (!game.getAllPlayers().contains(player.uniqueId)) return
        
        // Obtener la arena y su región de juego
        val arena = game.arena ?: return
        val playRegion = arena.playRegion ?: return
        
        val to = event.to ?: return
        
        // Si el espectador intenta salir de la región, cancelar movimiento
        if (!playRegion.contains(to)) {
            event.isCancelled = true
            player.sendActionBar(
                net.kyori.adventure.text.Component.text(
                    "✗ No puedes salir del área del Coliseo",
                    net.kyori.adventure.text.format.NamedTextColor.RED
                )
            )
        }
    }
    
    // ===== IA DE MOBS ALIADOS (HORDA) =====
    
    /**
     * Maneja el spawn de mobs aliados de la Horda.
     * Los mobs spawneados por la Horda atacarán solo a la Élite.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        
        // Solo procesar Zombis y Esqueletos
        if (entity !is Zombie && entity !is Skeleton) return
        
        // Solo procesar spawns por huevo (spawn eggs)
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return
        
        val game = gameManager.getActiveGame() ?: return
        if (game.state != GameState.IN_GAME) return
        
        // Buscar al jugador más cercano (quien spawneó el mob)
        val nearbyPlayers = entity.location.getNearbyPlayers(5.0)
        val spawner = nearbyPlayers.firstOrNull { game.hordePlayers.contains(it.uniqueId) }
        
        if (spawner == null) {
            // No fue spawneado por la Horda, cancelar
            event.isCancelled = true
            return
        }
        
        // Registrar el mob como aliado de la Horda
        game.hordeMobs.add(entity.uniqueId)
        
        // Añadir al equipo de Bukkit para el brillo blanco
        val hordeTeam = teamManager.getHordeTeam()
        hordeTeam?.addEntry(entity.uniqueId.toString())
        
        // Hacer que el mob brille
        entity.isGlowing = true
        
        // PROTECCIÓN SOLAR: Equipar casco de cuero para evitar que se quemen de día
        if (entity is Zombie || entity is Skeleton) {
            val helmet = org.bukkit.inventory.ItemStack(Material.LEATHER_HELMET)
            entity.equipment?.helmet = helmet
        }
        
        // Asignar objetivo inicial: un jugador Élite aleatorio
        val eliteTarget = teamManager.getAliveElitePlayers(game).randomOrNull()
        if (eliteTarget != null) {
            entity.target = eliteTarget
        }
        
        plugin.logger.info("[Coliseo] Mob aliado spawneado por ${spawner.name}: ${entity.type}")
    }
    
    /**
     * Controla el targeting de mobs aliados.
     * Los mobs de la Horda solo atacan a la Élite.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobTarget(event: EntityTargetEvent) {
        val entity = event.entity
        val target = event.target
        
        // Solo procesar mobs
        if (entity !is Monster) return
        
        val game = gameManager.getActiveGame() ?: return
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si es un mob aliado de la Horda
        if (!game.hordeMobs.contains(entity.uniqueId)) return
        
        // Si el target es un jugador
        if (target is Player) {
            // Prevenir que ataque a la Horda
            if (game.hordePlayers.contains(target.uniqueId)) {
                event.isCancelled = true
                
                // Re-target a un Élite
                val eliteTarget = teamManager.getAliveElitePlayers(game).randomOrNull()
                if (eliteTarget != null) {
                    event.target = eliteTarget
                }
                return
            }
        }
        
        // Si perdió su objetivo, buscar un nuevo Élite
        if (target == null || event.reason == EntityTargetEvent.TargetReason.FORGOT_TARGET || 
            event.reason == EntityTargetEvent.TargetReason.TARGET_DIED) {
            val eliteTarget = teamManager.getAliveElitePlayers(game).randomOrNull()
            if (eliteTarget != null) {
                event.target = eliteTarget
            }
        }
    }
    
    /**
     * Previene que la Horda pueda dañar a sus propios mobs aliados.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val victim = event.entity
        
        // Solo procesar si un jugador daña a un mob
        if (damager !is Player || victim !is Monster) return
        
        val game = gameManager.getActiveGame() ?: return
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si es un mob aliado de la Horda
        if (!game.hordeMobs.contains(victim.uniqueId)) return
        
        // Verificar si el atacante es de la Horda
        if (game.hordePlayers.contains(damager.uniqueId)) {
            // Cancelar daño de Horda a sus propios mobs
            event.isCancelled = true
            damager.sendActionBar(
                net.kyori.adventure.text.Component.text(
                    "✗ No puedes atacar a tus aliados",
                    net.kyori.adventure.text.format.NamedTextColor.RED
                )
            )
        }
    }
    
    /**
     * Maneja la muerte de mobs aliados.
     * NO otorga puntos - los mobs no cuentan como kills de jugadores.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onMobDeath(event: org.bukkit.event.entity.EntityDeathEvent) {
        val entity = event.entity
        
        // Solo procesar mobs
        if (entity !is Monster) return
        
        val game = gameManager.getActiveGame() ?: return
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si es un mob aliado de la Horda
        if (!game.hordeMobs.contains(entity.uniqueId)) return
        
        // Remover del tracking
        game.hordeMobs.remove(entity.uniqueId)
        
        // Log para debug
        val killer = entity.killer
        if (killer != null) {
            plugin.logger.info("[Coliseo] Mob aliado eliminado por ${killer.name} - NO se otorgan puntos")
            
            // Mensaje informativo al killer
            killer.sendActionBar(
                net.kyori.adventure.text.Component.text(
                    "Mob eliminado (sin puntos)",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY
                )
            )
        }
    }
    
    /**
     * Previene que los mobs aliados de la Horda se quemen por el sol o fuego.
     * Esto asegura que los mobs spawneados permanezcan útiles durante toda la partida.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMobCombust(event: EntityCombustEvent) {
        val entity = event.entity
        
        // Solo procesar mobs
        if (entity !is Monster) return
        
        val game = gameManager.getActiveGame() ?: return
        if (game.state != GameState.IN_GAME) return
        
        // Verificar si es un mob aliado de la Horda
        if (!game.hordeMobs.contains(entity.uniqueId)) return
        
        // Cancelar la combustión
        event.isCancelled = true
        
        plugin.logger.info("[Coliseo] Combustión prevenida para mob aliado: ${entity.type}")
    }
}
