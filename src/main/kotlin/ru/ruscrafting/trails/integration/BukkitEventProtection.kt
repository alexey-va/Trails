package ru.ruscrafting.trails.integration

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.Event
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
    private val probes = java.util.IdentityHashMap<Event, Boolean>()

    fun isProbe(event: Event): Boolean = probes.containsKey(event)

    private fun dispatch(event: Event) {
        probes[event] = true
        try { pluginManager.callEvent(event) } finally { probes.remove(event) }
    }
    fun canChange(
        player: Player,
        block: Block,
        target: Material,
        blockPlaceCompatibility: Boolean = true,
    ): Boolean {
        val event = EntityChangeBlockEvent(player, block, target.createBlockData())
        dispatch(event)
        if (event.isCancelled) return false
        if (!blockPlaceCompatibility) return true

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
        dispatch(placeEvent)
        return !placeEvent.isCancelled && placeEvent.canBuild()
    }

    fun canDecay(
        block: Block,
        target: Material,
    ): Boolean {
        val state = block.state
        state.type = target
        val event = BlockFadeEvent(block, state)
        dispatch(event)
        return !event.isCancelled
    }
}
