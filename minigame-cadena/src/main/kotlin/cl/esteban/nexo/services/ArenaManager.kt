package cl.esteban.nexo.services

import cl.esteban.nexo.game.Arena
import cl.esteban.nexo.utils.Cuboid
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestiona las arenas del minijuego Cadena.
 * 
 * Responsabilidades:
 * - Crear y eliminar arenas
 * - Almacenar configuración de arenas
 * - Proporcionar arenas disponibles para partidas
 * - Gestionar arena en edición
 * - Persistencia de arenas en disco
 */
class ArenaManager {
    
    /**
     * Archivo de configuración donde se guardan las arenas.
     */
    private var configFile: File? = null
    
    /**
     * Mapa de arenas por nombre.
     */
    private val arenas = ConcurrentHashMap<String, Arena>()
    
    /**
     * Arena que está siendo editada actualmente.
     * Key: Nombre del jugador editor
     */
    private val editingArenas = ConcurrentHashMap<String, Arena>()
    
    /**
     * Ubicación del lobby donde los jugadores esperan antes de la partida.
     */
    private var lobbyLocation: Location? = null
    
    /**
     * Crea una nueva arena en modo edición.
     * 
     * @param name Nombre de la arena
     * @param spawnLocation Ubicación inicial del spawn
     * @return Arena creada
     */
    fun createArena(name: String, spawnLocation: Location): Arena {
        val arena = Arena(
            name = name,
            spawnLocation = spawnLocation.clone(),
            minHeight = spawnLocation.y - 10.0
        )
        
        return arena
    }
    
    /**
     * Guarda una arena en el manager.
     * 
     * @param arena Arena a guardar
     */
    fun saveArena(arena: Arena) {
        arenas[arena.name] = arena
    }
    
    /**
     * Elimina una arena.
     * 
     * @param name Nombre de la arena
     * @return true si se eliminó, false si no existía
     */
    fun deleteArena(name: String): Boolean {
        return arenas.remove(name) != null
    }
    
    /**
     * Obtiene una arena por nombre.
     * 
     * @param name Nombre de la arena
     * @return Arena o null si no existe
     */
    fun getArena(name: String): Arena? {
        return arenas[name]
    }
    
    /**
     * Obtiene todas las arenas.
     */
    fun getAllArenas(): List<Arena> {
        return arenas.values.toList()
    }
    
    /**
     * Obtiene una arena aleatoria disponible.
     */
    fun getRandomArena(): Arena? {
        return arenas.values.randomOrNull()
    }
    
    /**
     * Verifica si existe una arena.
     */
    fun arenaExists(name: String): Boolean {
        return arenas.containsKey(name)
    }
    
    /**
     * Inicia la edición de una arena para un jugador.
     * 
     * @param playerName Nombre del jugador
     * @param arena Arena a editar
     */
    fun startEditing(playerName: String, arena: Arena) {
        editingArenas[playerName] = arena
    }
    
    /**
     * Finaliza la edición de una arena para un jugador.
     * 
     * @param playerName Nombre del jugador
     */
    fun stopEditing(playerName: String) {
        editingArenas.remove(playerName)
    }
    
    /**
     * Obtiene la arena que está editando un jugador.
     * 
     * @param playerName Nombre del jugador
     * @return Arena en edición o null
     */
    fun getEditingArena(playerName: String): Arena? {
        return editingArenas[playerName]
    }
    
    /**
     * Verifica si un jugador está editando una arena.
     */
    fun isEditing(playerName: String): Boolean {
        return editingArenas.containsKey(playerName)
    }
    
    /**
     * Obtiene el número de arenas configuradas.
     */
    fun getArenaCount(): Int {
        return arenas.size
    }
    
    /**
     * Actualiza el spawn principal de una arena en edición.
     */
    fun updateSpawnLocation(playerName: String, location: Location) {
        val arena = editingArenas[playerName] ?: return
        // Crear una copia profunda preservando todas las listas
        val newArena = Arena(
            name = arena.name,
            spawnLocation = location,
            spawnLocations = arena.spawnLocations.toMutableList(), // Copia profunda
            checkpoints = arena.checkpoints.toMutableList(), // Copia profunda
            minHeight = arena.minHeight,
            detectionRadius = arena.detectionRadius
        )
        newArena.meta = arena.meta
        editingArenas[playerName] = newArena
    }
    
    // Método updateFinishLocation eliminado - ahora se usa meta como Cuboid directamente
    
    /**
     * Actualiza la altura mínima de una arena en edición.
     */
    fun updateMinHeight(playerName: String, height: Double) {
        val arena = editingArenas[playerName] ?: return
        // Crear una copia profunda preservando todas las listas
        val newArena = Arena(
            name = arena.name,
            spawnLocation = arena.spawnLocation,
            spawnLocations = arena.spawnLocations.toMutableList(), // Copia profunda
            checkpoints = arena.checkpoints.toMutableList(), // Copia profunda
            minHeight = height,
            detectionRadius = arena.detectionRadius
        )
        newArena.meta = arena.meta
        editingArenas[playerName] = newArena
    }
    
    /**
     * Establece la ubicación del lobby.
     */
    fun setLobbyLocation(location: Location) {
        lobbyLocation = location.clone()
    }
    
    /**
     * Obtiene la ubicación del lobby.
     */
    fun getLobbyLocation(): Location? {
        return lobbyLocation?.clone()
    }
    
    /**
     * Limpia todas las arenas (para reinicio).
     */
    fun clearAll() {
        arenas.clear()
        editingArenas.clear()
        lobbyLocation = null
    }
    
    // ===== Persistencia =====
    
    /**
     * Inicializa el sistema de persistencia.
     */
    fun initialize(dataFolder: File) {
        configFile = File(dataFolder, "arenas.yml")
        loadArenas()
    }
    
    /**
     * Guarda todas las arenas en el archivo de configuración.
     */
    fun saveArenas() {
        val file = configFile ?: return
        val config = YamlConfiguration()
        
        // Guardar lobby location
        lobbyLocation?.let { loc ->
            config.set("lobby.world", loc.world?.name)
            config.set("lobby.x", loc.x)
            config.set("lobby.y", loc.y)
            config.set("lobby.z", loc.z)
            config.set("lobby.yaw", loc.yaw)
            config.set("lobby.pitch", loc.pitch)
        }
        
        // Guardar cada arena
        arenas.forEach { (name, arena) ->
            val path = "arenas.$name"
            
            // Spawn location (principal - para compatibilidad)
            config.set("$path.spawn.world", arena.spawnLocation.world?.name)
            config.set("$path.spawn.x", arena.spawnLocation.x)
            config.set("$path.spawn.y", arena.spawnLocation.y)
            config.set("$path.spawn.z", arena.spawnLocation.z)
            config.set("$path.spawn.yaw", arena.spawnLocation.yaw)
            config.set("$path.spawn.pitch", arena.spawnLocation.pitch)
            
            // Spawn locations (múltiples spawns para equipos)
            arena.spawnLocations.forEachIndexed { index, spawnLoc ->
                config.set("$path.spawns.$index.world", spawnLoc.world?.name)
                config.set("$path.spawns.$index.x", spawnLoc.x)
                config.set("$path.spawns.$index.y", spawnLoc.y)
                config.set("$path.spawns.$index.z", spawnLoc.z)
                config.set("$path.spawns.$index.yaw", spawnLoc.yaw)
                config.set("$path.spawns.$index.pitch", spawnLoc.pitch)
            }
            
            // Meta region (Cuboid)
            arena.meta?.let { meta ->
                config.set("$path.meta.world", meta.world?.name)
                config.set("$path.meta.minX", meta.minX)
                config.set("$path.meta.minY", meta.minY)
                config.set("$path.meta.minZ", meta.minZ)
                config.set("$path.meta.maxX", meta.maxX)
                config.set("$path.meta.maxY", meta.maxY)
                config.set("$path.meta.maxZ", meta.maxZ)
            }
            
            // Min height
            config.set("$path.minHeight", arena.minHeight)
            
            // Detection radius
            config.set("$path.detectionRadius", arena.detectionRadius)
            
            // Checkpoints (Cuboids)
            arena.checkpoints.forEachIndexed { index, checkpoint ->
                config.set("$path.checkpoints.$index.world", checkpoint.world?.name)
                config.set("$path.checkpoints.$index.minX", checkpoint.minX)
                config.set("$path.checkpoints.$index.minY", checkpoint.minY)
                config.set("$path.checkpoints.$index.minZ", checkpoint.minZ)
                config.set("$path.checkpoints.$index.maxX", checkpoint.maxX)
                config.set("$path.checkpoints.$index.maxY", checkpoint.maxY)
                config.set("$path.checkpoints.$index.maxZ", checkpoint.maxZ)
            }
        }
        
        try {
            config.save(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Carga todas las arenas desde el archivo de configuración.
     */
    private fun loadArenas() {
        val file = configFile ?: return
        
        if (!file.exists()) {
            return
        }
        
        val config = YamlConfiguration.loadConfiguration(file)
        
        // Cargar lobby location
        if (config.contains("lobby")) {
            val worldName = config.getString("lobby.world") ?: return
            val world = Bukkit.getWorld(worldName) ?: return
            
            lobbyLocation = Location(
                world,
                config.getDouble("lobby.x"),
                config.getDouble("lobby.y"),
                config.getDouble("lobby.z"),
                config.getDouble("lobby.yaw").toFloat(),
                config.getDouble("lobby.pitch").toFloat()
            )
        }
        
        // Cargar arenas
        val arenasSection = config.getConfigurationSection("arenas") ?: return
        
        for (arenaName in arenasSection.getKeys(false)) {
            val path = "arenas.$arenaName"
            
            // Spawn location
            val spawnWorldName = config.getString("$path.spawn.world") ?: continue
            val spawnWorld = Bukkit.getWorld(spawnWorldName) ?: continue
            
            val spawnLocation = Location(
                spawnWorld,
                config.getDouble("$path.spawn.x"),
                config.getDouble("$path.spawn.y"),
                config.getDouble("$path.spawn.z"),
                config.getDouble("$path.spawn.yaw").toFloat(),
                config.getDouble("$path.spawn.pitch").toFloat()
            )
            
            // Meta region (Cuboid) - opcional
            var metaRegion: Cuboid? = null
            val metaWorldName = config.getString("$path.meta.world")
            if (metaWorldName != null) {
                val metaWorld = Bukkit.getWorld(metaWorldName)
                if (metaWorld != null) {
                    metaRegion = Cuboid(
                        world = metaWorld,
                        minX = config.getInt("$path.meta.minX"),
                        minY = config.getInt("$path.meta.minY"),
                        minZ = config.getInt("$path.meta.minZ"),
                        maxX = config.getInt("$path.meta.maxX"),
                        maxY = config.getInt("$path.meta.maxY"),
                        maxZ = config.getInt("$path.meta.maxZ")
                    )
                }
            }
            
            // Min height
            val minHeight = config.getDouble("$path.minHeight", 0.0)
            
            // Detection radius
            val detectionRadius = config.getDouble("$path.detectionRadius", 2.0)
            
            // Crear arena
            val arena = Arena(
                name = arenaName,
                spawnLocation = spawnLocation,
                minHeight = minHeight,
                detectionRadius = detectionRadius
            )
            arena.meta = metaRegion
            
            // Cargar spawn locations (múltiples spawns para equipos)
            val spawnsSection = config.getConfigurationSection("$path.spawns")
            if (spawnsSection != null) {
                val spawnIndices = spawnsSection.getKeys(false).mapNotNull { it.toIntOrNull() }.sorted()
                
                for (index in spawnIndices) {
                    val spWorldName = config.getString("$path.spawns.$index.world") ?: continue
                    val spWorld = Bukkit.getWorld(spWorldName) ?: continue
                    
                    val spawnLoc = Location(
                        spWorld,
                        config.getDouble("$path.spawns.$index.x"),
                        config.getDouble("$path.spawns.$index.y"),
                        config.getDouble("$path.spawns.$index.z"),
                        config.getDouble("$path.spawns.$index.yaw").toFloat(),
                        config.getDouble("$path.spawns.$index.pitch").toFloat()
                    )
                    
                    arena.addSpawnLocation(spawnLoc)
                }
            }
            
            // Cargar checkpoints (Cuboids)
            val checkpointsSection = config.getConfigurationSection("$path.checkpoints")
            if (checkpointsSection != null) {
                val checkpointIndices = checkpointsSection.getKeys(false).mapNotNull { it.toIntOrNull() }.sorted()
                
                for (index in checkpointIndices) {
                    val cpWorldName = config.getString("$path.checkpoints.$index.world") ?: continue
                    val cpWorld = Bukkit.getWorld(cpWorldName) ?: continue
                    
                    val checkpoint = Cuboid(
                        world = cpWorld,
                        minX = config.getInt("$path.checkpoints.$index.minX"),
                        minY = config.getInt("$path.checkpoints.$index.minY"),
                        minZ = config.getInt("$path.checkpoints.$index.minZ"),
                        maxX = config.getInt("$path.checkpoints.$index.maxX"),
                        maxY = config.getInt("$path.checkpoints.$index.maxY"),
                        maxZ = config.getInt("$path.checkpoints.$index.maxZ")
                    )
                    
                    arena.addCheckpoint(checkpoint)
                }
            }
            
            arenas[arenaName] = arena
        }
    }
}
