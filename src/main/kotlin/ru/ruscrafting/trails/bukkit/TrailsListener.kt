package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.trails.TrailsPlugin

class TrailsListener(
    private val plugin: TrailsPlugin,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (from.world !== to.world ||
            (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ)
        ) {
            return
        }
        val capturingRoad = plugin.captureRoadMovement(event.player, to)
        if (event.player.isFlying) return
        plugin.handleMovement(event.player, from.clone().subtract(0.0, 0.1, 0.0).block, createTrail = !capturingRoad)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        // BukkitEventProtection sends an unchanged BlockPlaceEvent as a compatibility probe.
        // Only a real replacement should invalidate persisted trail state.
        if (event.blockPlaced.blockData == event.blockReplacedState.blockData) return
        plugin.clearTrailData(event.blockPlaced)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        event.blocks.forEach { plugin.clearTrailData(it.block) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        event.blocks.forEach { plugin.clearTrailData(it.block) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Player) return
        plugin.clearTrailData(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().forEach(plugin::clearTrailData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().forEach(plugin::clearTrailData)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        plugin.moveTrailData(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        plugin.moveTrailData(event.blocks, event.direction.oppositeFace)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (event.newState.type == Material.GRASS_BLOCK && plugin.inspectTrail(event.block) != null) {
            event.isCancelled = plugin.decayBlock(event.block)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        val block = event.clickedBlock ?: return
        val toolKind = plugin.toolKind(item) ?: return
        // Tagged Trails tools are dedicated controls. Cancel their vanilla interaction even when
        // the holder lacks permission so an inspection stick cannot also open or toggle a block.
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        when (toolKind) {
            TrailToolKind.ADVANCE -> {
                if (!event.player.hasPermission("trails.trail-tool")) return
                plugin.forceTrail(event.player, block)
            }
            TrailToolKind.INSPECT -> {
                if (!event.player.hasPermission("trails.info-tool")) return
                plugin.showTrailInfo(event.player, block)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.discardRoadSession(event.player)
        plugin.restoreSpeed(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        plugin.discardRoadSession(event.player)
        plugin.restoreSpeed(event.player)
    }
}
