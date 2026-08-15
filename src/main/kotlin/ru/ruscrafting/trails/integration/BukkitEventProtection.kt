package ru.ruscrafting.trails.integration

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.PluginManager

/**
 * Lets installed protection plugins veto an actual Trails material transition.
 * Modern listeners receive the exact transition event; classic claim plugins
 * that guard player building receive the conventional block-place probe.
 */
class BukkitEventProtection(
    private val pluginManager: PluginManager,
) {
    fun canChange(
        player: Player,
        block: Block,
        target: Material,
    ): Boolean {
        val event = EntityChangeBlockEvent(player, block, target.createBlockData())
        pluginManager.callEvent(event)
        if (event.isCancelled) return false

        val itemMaterial = target.takeIf(Material::isItem) ?: block.type.takeIf(Material::isItem) ?: Material.DIRT
        val placeEvent =
            BlockPlaceEvent(
                block,
                block.state,
                block.getRelative(BlockFace.DOWN),
                ItemStack(itemMaterial),
                player,
                true,
                EquipmentSlot.HAND,
            )
        pluginManager.callEvent(placeEvent)
        return !placeEvent.isCancelled && placeEvent.canBuild()
    }
}
