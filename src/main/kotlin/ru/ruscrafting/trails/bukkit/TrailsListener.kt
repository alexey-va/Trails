package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.trails.TrailsPlugin

class TrailsListener(
    private val plugin: TrailsPlugin,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (event.player.isFlying ||
            from.world !== to.world ||
            (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ)
        ) {
            return
        }
        plugin.handleMovement(event.player, from.clone().subtract(0.0, 0.1, 0.0).block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (event.newState.type == Material.GRASS_BLOCK && plugin.inspectTrail(event.block) != null) {
            plugin.decayBlock(event.block)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        val block = event.clickedBlock ?: return
        when (plugin.toolKind(item)) {
            TrailToolKind.ADVANCE -> {
                if (!event.player.hasPermission("trails.trail-tool")) return
                plugin.forceTrail(event.player, block)
                if (item.type.name.endsWith("_SHOVEL")) event.isCancelled = true
            }
            TrailToolKind.INSPECT -> {
                if (!event.player.hasPermission("trails.info-tool")) return
                plugin.showTrailInfo(event.player, block)
                if (item.type.name.endsWith("_SHOVEL")) event.isCancelled = true
            }
            null -> Unit
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.restoreSpeed(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        plugin.restoreSpeed(event.player)
    }
}
