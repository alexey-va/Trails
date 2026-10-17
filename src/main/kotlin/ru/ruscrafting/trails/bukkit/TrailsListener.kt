package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.block.BlockFace
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
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.trails.TrailsPlugin
import kotlin.math.abs

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
        plugin.handleMovement(
            event.player,
            from.clone().subtract(0.0, 0.1, 0.0).block,
            createTrail = !capturingRoad,
            movementDirection = horizontalDirection(from, to),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        // BukkitEventProtection sends an unchanged BlockPlaceEvent as a compatibility probe.
        // Only a real replacement should invalidate persisted trail state.
        if (event.blockPlaced.blockData == event.blockReplacedState.blockData) return
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.blockPlaced)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) {
        if (!plugin.clearTrailData(event.blocks) { it.block }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        if (!plugin.clearTrailData(event.blocks) { it.block }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Player) return
        if (!plugin.isProtectionProbe(event) && !plugin.clearTrailData(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        if (!plugin.clearTrailData(event.blockList()) { it }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        if (!plugin.clearTrailData(event.blockList()) { it }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        // Keep the atomic warm event seam: pinned Purpur 1.21.11 with push limit 12
        // bounds moved+broken graph references (not just moved blocks). No new list cap.
        if (!plugin.moveTrailData(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (!plugin.moveTrailData(event.blocks, event.direction.oppositeFace)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun preparePistonExtend(event: BlockPistonExtendEvent) {
        if (!plugin.canMoveTrailData(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun preparePistonRetract(event: BlockPistonRetractEvent) {
        if (!plugin.canMoveTrailData(event.blocks, event.direction.oppositeFace)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        plugin.forgetTrailActivity(event.chunk)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        if (event.newState.type != Material.GRASS_BLOCK) return
        if (!plugin.trailDataReady(event.block)) {
            plugin.prepareTrailData(event.block)
            event.isCancelled = true
            return
        }
        if (plugin.inspectTrail(event.block) != null) {
            event.isCancelled = plugin.decayBlock(event.block)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        val block = event.clickedBlock ?: return
        val toolKind = plugin.toolKind(item) ?: return
        val player = event.player
        // Tagged Trails tools are dedicated controls. Cancel their vanilla interaction even when
        // the holder lacks permission so an inspection stick cannot also open or toggle a block.
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        when (toolKind) {
            TrailToolKind.ADVANCE -> {
                if (!event.player.hasPermission("trails.trail-tool")) return
                plugin.forceTrail(player, block) { player.hasPermission("trails.trail-tool") }
            }
            TrailToolKind.INSPECT -> {
                if (!event.player.hasPermission("trails.info-tool")) return
                plugin.showTrailInfo(player, block) { player.hasPermission("trails.info-tool") }
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
        clearTransientPlayerState(event.player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        clearTransientPlayerState(event.player)
    }

    @EventHandler
    fun onWorldChanged(event: PlayerChangedWorldEvent) {
        clearTransientPlayerState(event.player)
    }

    private fun clearTransientPlayerState(player: Player) {
        plugin.discardRoadSession(player)
        plugin.restoreSpeed(player)
    }

    private fun horizontalDirection(from: Location, to: Location): BlockFace? {
        val x = to.x - from.x
        val z = to.z - from.z
        if (x == 0.0 && z == 0.0) return null
        return if (abs(x) >= abs(z)) {
            if (x >= 0.0) BlockFace.EAST else BlockFace.WEST
        } else {
            if (z >= 0.0) BlockFace.SOUTH else BlockFace.NORTH
        }
    }
}
