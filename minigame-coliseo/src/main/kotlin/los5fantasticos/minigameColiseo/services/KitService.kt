package los5fantasticos.minigameColiseo.services

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * Servicio de kits del Coliseo V2 (Tácticos).
 * 
 * Soporta formato complejo: "MATERIAL:ENCANTAMIENTO:NIVEL:CANTIDAD:slot=X"
 * 
 * Responsabilidades:
 * - Parsear kits complejos con encantamientos y slots específicos
 * - Aplicar kits a jugadores
 * - Gestionar efectos permanentes
 */
class KitService(private val config: FileConfiguration) {
    
    /**
     * Aplica el kit de Élite a un jugador.
     */
    fun applyEliteKit(player: Player) {
        player.inventory.clear()
        
        // Armadura
        player.inventory.helmet = parseArmor("kits.elite.armor.helmet")
        player.inventory.chestplate = parseArmor("kits.elite.armor.chestplate")
        player.inventory.leggings = parseArmor("kits.elite.armor.leggings")
        player.inventory.boots = parseArmor("kits.elite.armor.boots")
        
        // Items con slots específicos
        val items = config.getStringList("kits.elite.items")
        items.forEach { itemStr ->
            val (item, slot) = parseItemWithSlot(itemStr)
            if (item != null) {
                if (slot == 40) {
                    // Off-hand
                    player.inventory.setItemInOffHand(item)
                } else if (slot >= 0) {
                    player.inventory.setItem(slot, item)
                } else {
                    player.inventory.addItem(item)
                }
            }
        }
        
        // Efectos permanentes
        val effects = config.getStringList("kits.elite.effects")
        effects.forEach { effectStr ->
            parseEffect(effectStr)?.let { player.addPotionEffect(it) }
        }
    }
    
    /**
     * Aplica el kit de Horda a un jugador.
     */
    fun applyHordeKit(player: Player) {
        player.inventory.clear()
        
        // Armadura
        player.inventory.helmet = parseArmor("kits.horde.armor.helmet")
        player.inventory.chestplate = parseArmor("kits.horde.armor.chestplate")
        player.inventory.leggings = parseArmor("kits.horde.armor.leggings")
        player.inventory.boots = parseArmor("kits.horde.armor.boots")
        
        // Items con slots específicos
        val items = config.getStringList("kits.horde.items")
        items.forEach { itemStr ->
            val (item, slot) = parseItemWithSlot(itemStr)
            if (item != null) {
                if (slot == 40) {
                    // Off-hand
                    player.inventory.setItemInOffHand(item)
                } else if (slot >= 0) {
                    player.inventory.setItem(slot, item)
                } else {
                    player.inventory.addItem(item)
                }
            }
        }
    }
    
    /**
     * Parsea una pieza de armadura desde la configuración.
     * Formato: "MATERIAL:ENCANTAMIENTO:NIVEL" o "MATERIAL"
     */
    private fun parseArmor(path: String): ItemStack? {
        val armorStr = config.getString(path) ?: return null
        val parts = armorStr.split(":")
        
        val material = Material.getMaterial(parts[0]) ?: return null
        val item = ItemStack(material)
        
        // Aplicar encantamiento si existe
        if (parts.size >= 3) {
            val enchantName = parts[1]
            val level = parts[2].toIntOrNull() ?: 1
            val enchant = getEnchantmentByName(enchantName)
            if (enchant != null) {
                item.addUnsafeEnchantment(enchant, level)
            }
        }
        
        return item
    }
    
    /**
     * Parsea un item con slot desde la configuración.
     * Formato: "MATERIAL:ENCANTAMIENTO:NIVEL:CANTIDAD:slot=X"
     * 
     * Ejemplos:
     * - "DIAMOND_SWORD:DAMAGE_ALL:1:slot=0"
     * - "GOLDEN_APPLE:1:slot=1"
     * - "SHIELD:slot=40"
     * 
     * @return Pair<ItemStack?, Int> donde Int es el slot (-1 si no especificado)
     */
    private fun parseItemWithSlot(itemStr: String): Pair<ItemStack?, Int> {
        val parts = itemStr.split(":")
        if (parts.isEmpty()) return Pair(null, -1)
        
        // Parsear material
        val material = Material.getMaterial(parts[0]) ?: return Pair(null, -1)
        
        var amount = 1
        var enchantName: String? = null
        var enchantLevel = 1
        var slot = -1
        
        // Parsear el resto de partes
        var i = 1
        while (i < parts.size) {
            val part = parts[i]
            
            when {
                // Slot
                part.startsWith("slot=") -> {
                    slot = part.substringAfter("slot=").toIntOrNull() ?: -1
                }
                // Cantidad (solo números)
                part.toIntOrNull() != null -> {
                    // Si ya tenemos un encantamiento, esto es el nivel
                    if (enchantName != null && enchantLevel == 1) {
                        enchantLevel = part.toInt()
                    } else {
                        amount = part.toInt()
                    }
                }
                // Encantamiento (texto)
                else -> {
                    enchantName = part
                }
            }
            i++
        }
        
        // Crear item
        val item = ItemStack(material, amount)
        
        // Aplicar encantamiento si existe
        if (enchantName != null) {
            val enchant = getEnchantmentByName(enchantName)
            if (enchant != null) {
                item.addUnsafeEnchantment(enchant, enchantLevel)
            }
        }
        
        return Pair(item, slot)
    }
    
    /**
     * Parsea un efecto de poción desde la configuración.
     * Formato: "EFECTO:NIVEL"
     */
    private fun parseEffect(effectStr: String): PotionEffect? {
        val parts = effectStr.split(":")
        val effectName = parts[0]
        val level = parts.getOrNull(1)?.toIntOrNull() ?: 0
        
        val effectType = PotionEffectType.getByName(effectName) ?: return null
        return PotionEffect(effectType, Int.MAX_VALUE, level, false, false)
    }
    
    /**
     * Obtiene un encantamiento por nombre.
     * Soporta nombres comunes y nombres de Bukkit.
     */
    private fun getEnchantmentByName(name: String): Enchantment? {
        return when (name.uppercase()) {
            "DAMAGE_ALL", "SHARPNESS" -> Enchantment.DAMAGE_ALL
            "PROTECTION_ENVIRONMENTAL", "PROTECTION" -> Enchantment.PROTECTION_ENVIRONMENTAL
            "PIERCING" -> Enchantment.PIERCING
            "ARROW_DAMAGE", "POWER" -> Enchantment.ARROW_DAMAGE
            "ARROW_FIRE", "FLAME" -> Enchantment.ARROW_FIRE
            "ARROW_INFINITE", "INFINITY" -> Enchantment.ARROW_INFINITE
            "ARROW_KNOCKBACK", "PUNCH" -> Enchantment.ARROW_KNOCKBACK
            else -> Enchantment.getByName(name)
        }
    }
}
