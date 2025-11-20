package los5fantasticos.minigameCadena.services

import los5fantasticos.minigameCadena.MinigameCadena
import los5fantasticos.minigameCadena.game.CadenaGame
import los5fantasticos.minigameCadena.game.GameState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Sound
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team as BukkitTeam

/**
 * Gestiona el lobby y la cuenta atrás antes de iniciar una partida.
 * 
 * Responsabilidades:
 * - Monitorear partidas en lobby
 * - Iniciar cuenta atrás cuando hay suficientes jugadores
 * - Notificar a los jugadores del progreso
 * - Iniciar la partida al finalizar la cuenta atrás
 */
class LobbyManager(
    private val plugin: MinigameCadena,
    private val gameManager: GameManager
) {
    
    /**
     * Tiempo de cuenta atrás en segundos.
     */
    private val countdownTime = 10
    
    /**
     * Tareas de cuenta atrás activas por partida.
     */
    private val countdownTasks = mutableMapOf<java.util.UUID, BukkitTask>()
    
    /**
     * Inicia el monitoreo de una partida en lobby.
     * Si hay suficientes jugadores, inicia la cuenta atrás.
     * 
     * @param game Partida a monitorear
     */
    fun checkAndStartCountdown(game: CadenaGame) {
        // Verificar que la partida esté en lobby
        if (game.state != GameState.LOBBY) {
            return
        }
        
        // Verificar si ya hay una cuenta atrás activa
        if (countdownTasks.containsKey(game.id)) {
            return
        }
        
        // Verificar si hay suficientes jugadores
        if (!game.hasMinimumPlayers()) {
            return
        }
        
        // TAREA 3: Desactivar inicio automático - solo notificar que está listo
        plugin.plugin.logger.info("[${plugin.gameName}] Partida lista para iniciar - esperando comando de admin")
        
        // Notificar a los admins online que la partida está lista
        org.bukkit.Bukkit.getOnlinePlayers().forEach { player ->
            if (player.hasPermission("cadena.admin")) {
                player.sendMessage("${ChatColor.GOLD}[Cadena] ${ChatColor.GREEN}La partida está lista para iniciar!")
                player.sendMessage("${ChatColor.YELLOW}Usa ${ChatColor.WHITE}/cadena admin forcestart ${ChatColor.YELLOW}para comenzar.")
            }
        }
    }
    
    /**
     * Inicia la cuenta atrás para una partida manualmente (llamado por comando de admin).
     * Ahora es público para que pueda ser llamado desde el comando.
     */
    fun startCountdown(game: CadenaGame) {
    
        // Cambiar estado a COUNTDOWN
        if (!gameManager.startGame(game)) {
            return
        }
        
        // Notificar a todos los jugadores
        broadcastToGame(game, Component.text("¡La partida comenzará en $countdownTime segundos!", NamedTextColor.GREEN, TextDecoration.BOLD))
        playSound(game, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
        
        // Crear tarea de cuenta atrás
        val task = object : BukkitRunnable() {
            var timeLeft = countdownTime
            
            override fun run() {
                when {
                    // Verificar si la partida sigue existiendo
                    game.state != GameState.COUNTDOWN -> {
                        cancel()
                        countdownTasks.remove(game.id)
                        return
                    }
                    
                    // Verificar si hay suficientes jugadores
                    !game.hasMinimumPlayers() -> {
                        broadcastToGame(game, Component.text("No hay suficientes jugadores. Cuenta atrás cancelada.", NamedTextColor.RED))
                        game.state = GameState.LOBBY
                        cancel()
                        countdownTasks.remove(game.id)
                        return
                    }
                    
                    // Tiempo agotado - iniciar partida
                    timeLeft <= 0 -> {
                        startGameplay(game)
                        cancel()
                        countdownTasks.remove(game.id)
                        return
                    }
                    
                    // Notificaciones en momentos clave
                    timeLeft in listOf(30, 20, 10, 5, 4, 3, 2, 1) -> {
                        val color = when {
                            timeLeft <= 3 -> NamedTextColor.RED
                            timeLeft <= 5 -> NamedTextColor.GOLD
                            else -> NamedTextColor.YELLOW
                        }
                        broadcastToGame(game, Component.text("$timeLeft...", color, TextDecoration.BOLD))
                        
                        val pitch = when {
                            timeLeft <= 3 -> 2.0f
                            timeLeft <= 5 -> 1.5f
                            else -> 1.0f
                        }
                        playSound(game, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch)
                    }
                }
                
                timeLeft--
            }
        }
        
        // Ejecutar cada segundo
        val bukkitTask = task.runTaskTimer(plugin.getPlugin(), 0L, 20L)
        countdownTasks[game.id] = bukkitTask
    }
    
    /**
     * Inicia el gameplay de una partida.
     */
    private fun startGameplay(game: CadenaGame) {
        game.state = GameState.IN_GAME
        
        // Configurar jugadores
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                // Forzar modo aventura
                player.gameMode = org.bukkit.GameMode.ADVENTURE
                
                // Limpiar inventario (quitar lanas de selección)
                player.inventory.clear()
                
                // Restaurar salud y hambre
                player.health = 20.0
                player.foodLevel = 20
            }
        }
        
        // --- INICIO: Lógica de Aura de Equipo ---
        setupTeamGlowEffects(game)
        // --- FIN: Lógica de Aura de Equipo ---
        
        // Notificar inicio
        broadcastToGame(game, Component.text("¡LA PARTIDA HA COMENZADO!", NamedTextColor.GREEN, TextDecoration.BOLD))
        broadcastToGame(game, Component.text("¡Mantén la cadena unida y completa el parkour!", NamedTextColor.YELLOW))
        playSound(game, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f)
        
        // PR4: Teletransportar jugadores al spawn de la arena
        if (game.arena != null) {
            plugin.parkourService.teleportAllTeamsToSpawn(game)
            broadcastToGame(game, Component.text("✓ Teletransportados al spawn de la arena", NamedTextColor.AQUA))
        } else {
            // Si no hay arena configurada, usar ubicación actual como arena temporal
            broadcastToGame(game, Component.text("⚠ No hay arena configurada - Usando ubicación actual", NamedTextColor.YELLOW))
            broadcastToGame(game, Component.text("Usa /cadena admin para configurar una arena", NamedTextColor.GRAY))
        }
        
        // PR3: Activar ChainService para esta partida
        plugin.chainService.startChaining(game)
        broadcastToGame(game, Component.text("✓ Encadenamiento activado - Distancia máxima: ${plugin.chainService.maxDistance} bloques", NamedTextColor.AQUA))
        
        // Crear cadenas visuales para todos los equipos
        game.teams.forEach { team ->
            plugin.chainVisualizerService.createChainsForTeam(team)
        }
        broadcastToGame(game, Component.text("✓ Cadenas visuales activadas", NamedTextColor.AQUA))
        
        // Iniciar game loop para detección de regiones
        plugin.gameManager.startGameLoop(game)
        broadcastToGame(game, Component.text("✓ Sistema de detección de regiones activado", NamedTextColor.AQUA))
        
        // Reiniciar puntos del juego para el scoreboard
        plugin.scoreService.resetGamePoints()
        
        // Mostrar scoreboard dedicado a todos los jugadores
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                plugin.cadenaScoreboardService.showScoreboard(player, game)
            }
        }
        
        // Crear e iniciar temporizador visual con BossBar
        startGameTimer(game)
    }
    
    /**
     * Cancela la cuenta atrás de una partida.
     */
    fun cancelCountdown(game: CadenaGame) {
        val task = countdownTasks.remove(game.id)
        task?.cancel()
        
        if (game.state == GameState.COUNTDOWN) {
            game.state = GameState.LOBBY
        }
    }
    
    /**
     * Envía un mensaje a todos los jugadores de una partida.
     */
    private fun broadcastToGame(game: CadenaGame, message: Component) {
        val fullMessage = Component.text("[Cadena] ", NamedTextColor.GOLD).append(message)
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                player.sendMessage(fullMessage)
            }
        }
    }
    
    /**
     * Reproduce un sonido para todos los jugadores de una partida.
     */
    private fun playSound(game: CadenaGame, sound: Sound, volume: Float, pitch: Float) {
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                player.playSound(player.location, sound, volume, pitch)
            }
        }
    }
    
    /**
     * Inicia el temporizador visual de la partida.
     */
    private fun startGameTimer(game: CadenaGame) {
        // Leer duración desde configuración (por defecto 1200 segundos = 20 minutos)
        val gameDuration = plugin.plugin.config.getInt("partida.duracion-segundos", 1200)
        
        val timer = los5fantasticos.torneo.util.GameTimer(
            plugin = plugin.torneoPlugin,
            durationInSeconds = gameDuration,
            title = "§6§l⏱ Parkour en Cadena",
            onFinish = {
                // Cuando el tiempo se agota, finalizar la partida
                handleTimeUp(game)
            },
            onTick = { secondsLeft ->
                // Reproducir sonido en los últimos 10 segundos
                if (secondsLeft <= 10 && secondsLeft > 0) {
                    playSound(game, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f)
                }
            }
        )
        
        // Añadir todos los jugadores al temporizador
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                timer.addPlayer(player)
            }
        }
        
        // Guardar referencia y iniciar
        game.gameTimer = timer
        timer.start()
        
        // Mostrar duración en formato legible
        val minutes = gameDuration / 60
        val seconds = gameDuration % 60
        val timeText = if (seconds == 0) {
            "${minutes} minuto${if (minutes != 1) "s" else ""}"
        } else {
            "${minutes}:${seconds.toString().padStart(2, '0')}"
        }
        
        broadcastToGame(game, Component.text("✓ Temporizador iniciado - Tiempo límite: $timeText", NamedTextColor.AQUA))
    }
    
    /**
     * Maneja cuando se acaba el tiempo de una partida.
     */
    private fun handleTimeUp(game: CadenaGame) {
        // Verificar que la partida sigue activa
        if (game.state != GameState.IN_GAME) {
            return
        }
        
        broadcastToGame(game, Component.empty())
        broadcastToGame(game, Component.text("⏰ ¡TIEMPO AGOTADO!", NamedTextColor.RED, TextDecoration.BOLD))
        broadcastToGame(game, Component.text("La partida ha terminado por límite de tiempo.", NamedTextColor.YELLOW))
        broadcastToGame(game, Component.empty())
        
        // Reproducir sonido de finalización
        playSound(game, Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f)
        
        // Finalizar la partida a través del ParkourService
        plugin.parkourService.handleTimeUp(game)
    }
    
    /**
     * Configura los efectos de brillo (aura) para cada equipo.
     * Crea un scoreboard temporal y asigna colores de equipo a los jugadores.
     */
    private fun setupTeamGlowEffects(game: CadenaGame) {
        // 1. Crear un scoreboard nuevo y limpio para esta partida
        val gameScoreboard = Bukkit.getScoreboardManager()?.newScoreboard
        if (gameScoreboard == null) {
            plugin.plugin.logger.severe("[${plugin.gameName}] ¡Error crítico! No se pudo crear el scoreboard para auras de equipo.")
            return
        }
        
        // Guardar referencia en el GameManager
        plugin.gameManager.setGameScoreboard(gameScoreboard)
        
        plugin.plugin.logger.info("[${plugin.gameName}] Configurando auras de equipo...")
        
        // 2. Iterar sobre nuestros equipos y crear los equipos de Bukkit
        var teamsCreated = 0
        for (team in game.teams) {
            if (team.players.isEmpty()) continue // Omitir equipos vacíos
            
            // Registrar el equipo en el scoreboard de Bukkit
            val bukkitTeam = gameScoreboard.registerNewTeam(team.teamId)
            
            // 3. Mapear el ID del equipo al color correspondiente
            val teamColor = when (team.teamId.uppercase()) {
                "ROJO" -> NamedTextColor.RED
                "AZUL" -> NamedTextColor.BLUE
                "VERDE" -> NamedTextColor.GREEN
                "AMARILLO" -> NamedTextColor.YELLOW
                "NARANJA" -> NamedTextColor.GOLD
                "MORADO" -> NamedTextColor.DARK_PURPLE
                "CIAN" -> NamedTextColor.AQUA
                "ROSADO" -> NamedTextColor.LIGHT_PURPLE
                "GRIS" -> NamedTextColor.DARK_GRAY
                "BLANCO" -> NamedTextColor.WHITE
                else -> NamedTextColor.GRAY // Fallback
            }
            
            // 4. Aplicar el color y el brillo (aura)
            bukkitTeam.color(teamColor)
            bukkitTeam.setOption(BukkitTeam.Option.COLLISION_RULE, BukkitTeam.OptionStatus.NEVER)
            bukkitTeam.setOption(BukkitTeam.Option.NAME_TAG_VISIBILITY, BukkitTeam.OptionStatus.ALWAYS)
            
            // 5. Añadir a los jugadores a este equipo de Bukkit
            for (playerUUID in team.players) {
                Bukkit.getPlayer(playerUUID)?.let { player ->
                    bukkitTeam.addEntry(player.name)
                    // Aplicar el efecto de brillo
                    player.isGlowing = true
                }
            }
            
            teamsCreated++
            plugin.plugin.logger.info("[${plugin.gameName}] Equipo ${team.teamId} configurado con ${team.players.size} jugadores (color: $teamColor)")
        }
        
        // 6. Asignar este scoreboard a todos los jugadores en la partida
        game.teams.forEach { team ->
            team.getOnlinePlayers().forEach { player ->
                // Excluir del GlobalScoreboardService para evitar interferencias
                plugin.torneoPlugin.scoreboardService.hideScoreboard(player)
                
                // Asignar nuestro scoreboard
                player.scoreboard = gameScoreboard
            }
        }
        
        plugin.plugin.logger.info("[${plugin.gameName}] ✓ Auras de equipo configuradas: $teamsCreated equipos activos")
        broadcastToGame(game, Component.text("✓ Auras de equipo activadas", NamedTextColor.AQUA))
        
        // NOTA: No se necesita tarea de mantenimiento del scoreboard
        // El CadenaScoreboardService maneja su propio scoreboard independiente
        // y el gameScoreboard solo se usa para las auras de equipo (glow effect)
    }
    
    /**
     * Limpia todas las cuentas atrás activas.
     */
    fun clearAll() {
        countdownTasks.values.forEach { it.cancel() }
        countdownTasks.clear()
    }
    
    /**
     * Obtiene el plugin asociado (necesario para BukkitRunnable).
     */
    private fun MinigameCadena.getPlugin(): org.bukkit.plugin.Plugin {
        return this.plugin
    }
}
