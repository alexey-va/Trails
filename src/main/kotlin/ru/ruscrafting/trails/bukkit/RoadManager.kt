package ru.ruscrafting.trails.bukkit

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.ruscrafting.trails.TrailsPlugin
import ru.ruscrafting.trails.config.RoadProfile
import ru.ruscrafting.trails.config.RoadSettings
import ru.ruscrafting.trails.domain.RoadGeometry
import ru.ruscrafting.trails.domain.RoadPoint
import ru.ruscrafting.trails.storage.RoadBlockRecord
import ru.ruscrafting.trails.storage.RoadCommitRecord
import ru.ruscrafting.trails.storage.RoadHistoryStore
import ru.ruscrafting.trails.storage.TrailBlockState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class RoadNotice(
    val message: String,
    val replacements: Map<String, String> = emptyMap(),
)

data class RoadResult(
    val message: String,
    val replacements: Map<String, String> = emptyMap(),
    val notices: List<RoadNotice> = emptyList(),
)

class RoadManager internal constructor(
    private val plugin: TrailsPlugin,
    initialSettings: RoadSettings,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val previewSelector: RoadPreviewSelector = RoadPreviewSelector(),
) {
    private var settings = initialSettings
    private val sessions = mutableMapOf<UUID, Session>()
    private val historyStore = RoadHistoryStore(plugin.dataFolder.toPath())
    private val history =
        runCatching(historyStore::load).getOrElse { error ->
            plugin.logger.warning("Could not load road-history.yml; undo history starts empty: ${error.message}")
            linkedMapOf()
        }

    fun reconfigure(newSettings: RoadSettings) {
        sessions.entries.toList().forEach { (uuid, session) ->
            plugin.server.getPlayer(uuid)?.let { cancelPreview(it, session) }
        }
        sessions.clear()
        settings = newSettings
    }

    fun profiles(): Collection<String> = settings.profiles.keys

    fun isCapturing(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    fun start(
        player: Player,
        profileName: String,
    ): RoadResult {
        if (!settings.enabled) return RoadResult("messages.roadDisabled")
        if (!settings.worldEnabled(player.world.name)) return RoadResult("messages.roadWorldDisabled")
        val profile = settings.profiles[profileName.lowercase()] ?: return RoadResult("messages.roadUnknownProfile")
        sessions.remove(player.uniqueId)?.let { cancelPreview(player, it) }
        val center = surfaceAt(player.world, player.location.blockX, player.location.blockZ, player.location.blockY - 1)
            ?: return RoadResult("messages.roadNoSurface")
        sessions[player.uniqueId] =
            Session(
                worldId = player.world.uid,
                profile = profile,
                lastCenter = Surface(center.x, center.y, center.z),
                startedAt = clockMillis(),
            )
        val substitutedMaterials =
            profile.lanes
                .distinct()
                .filter { material -> previewSelector.select(material.createBlockData(), center.location).substituted }
        val notices =
            if (substitutedMaterials.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    RoadNotice(
                        "messages.roadPreviewFallback",
                        mapOf("%materials%" to substitutedMaterials.joinToString { it.name }),
                    ),
                )
            }
        return RoadResult(
            "messages.roadStarted",
            mapOf("%profile%" to profile.name, "%limit%" to settings.maxPlannedBlocks.toString()),
            notices,
        )
    }

    fun capture(
        player: Player,
        location: Location,
    ): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        if (expired(session) || location.world?.uid != session.worldId) {
            cancel(player)
            return false
        }
        val center = surfaceAt(player.world, location.blockX, location.blockZ, location.blockY - 1) ?: return true
        val current = Surface(center.x, center.y, center.z)
        val previous = session.lastCenter
        if (current.x == previous.x && current.z == previous.z) return true
        if (!withinSegmentLimits(previous, current)) {
            session.lastCenter = current
            return true
        }
        val cells =
            RoadGeometry.segment(
                RoadPoint(previous.x, previous.z),
                RoadPoint(current.x, current.z),
                session.profile.width,
            )
        for (cell in cells) {
            val laneIndex = cell.lane + session.profile.width / 2
            val material = session.profile.lanes[laneIndex]
            val block = surfaceAt(player.world, cell.x, cell.z, interpolatedY(previous, current, cell.x, cell.z)) ?: continue
            if (block.type == material) continue
            val key = BlockKey(block.x, block.y, block.z)
            val existing = session.planned[key]
            if (existing == null && session.planned.size >= settings.maxPlannedBlocks) {
                session.capped = true
                continue
            }
            if (existing == null || abs(cell.lane) < abs(existing.lane)) {
                val afterData = material.createBlockData()
                val previewData = previewSelector.select(afterData, block.location).blockData
                val planned =
                    PlannedBlock(
                        key = key,
                        lane = cell.lane,
                        beforeData = existing?.beforeData ?: block.blockData.asString,
                        afterData = afterData.asString,
                    )
                session.planned[key] = planned
                player.sendBlockChange(block.location, previewData)
            }
        }
        session.lastCenter = current
        return true
    }

    fun commit(player: Player): RoadResult {
        val session = sessions[player.uniqueId] ?: return RoadResult("messages.roadNoSession")
        if (expired(session)) {
            cancel(player)
            return RoadResult("messages.roadExpired")
        }
        val world = plugin.server.getWorld(session.worldId) ?: return RoadResult("messages.roadConflict")
        if (player.world.uid != session.worldId) return RoadResult("messages.roadConflict")
        if (session.planned.isEmpty()) return RoadResult("messages.roadEmpty")
        val resolved = session.planned.values.mapNotNull { resolve(world, it) }
        if (resolved.size != session.planned.size || resolved.any { !validCurrent(it) }) return RoadResult("messages.roadConflict")
        for ((block, planned) in resolved) {
            if (!plugin.canRoadChange(player, block, Bukkit.createBlockData(planned.afterData).material)) {
                return RoadResult("messages.roadProtected")
            }
        }

        val applied = mutableListOf<RoadBlockRecord>()
        try {
            resolved.forEach { (block, planned) ->
                val previous = plugin.inspectTrail(block)
                val record =
                    RoadBlockRecord(
                        planned.key.x,
                        planned.key.y,
                        planned.key.z,
                        planned.beforeData,
                        planned.afterData,
                        previous,
                        TrailBlockState(null, 0),
                    )
                applied += record
                plugin.placeRoad(player.name, block, Bukkit.createBlockData(planned.afterData))
                applied[applied.lastIndex] = record.copy(afterState = checkNotNull(plugin.inspectTrail(block)))
            }
        } catch (error: Exception) {
            rollback(world, applied)
            plugin.logger.severe("Road commit for ${player.name} rolled back: ${error.message}")
            return RoadResult("messages.roadCommitFailed")
        }
        val historyBefore = LinkedHashMap(history)
        history.remove(player.uniqueId)
        history[player.uniqueId] = RoadCommitRecord(world.uid, clockMillis(), applied)
        while (history.size > MAX_HISTORY_PLAYERS) history.remove(history.keys.first())
        try {
            historyStore.save(history)
        } catch (error: Exception) {
            history.clear()
            history.putAll(historyBefore)
            rollback(world, applied)
            plugin.logger.severe("Road commit for ${player.name} was rolled back because undo history could not be saved: ${error.message}")
            return RoadResult("messages.roadCommitFailed")
        }
        sessions.remove(player.uniqueId)
        applied.forEach { change ->
            val block = world.getBlockAt(change.x, change.y, change.z)
            player.sendBlockChange(block.location, Bukkit.createBlockData(change.afterData))
        }
        return RoadResult(
            if (session.capped) "messages.roadCommittedCapped" else "messages.roadCommitted",
            mapOf("%count%" to applied.size.toString()),
        )
    }

    fun cancel(player: Player): RoadResult {
        val session = sessions.remove(player.uniqueId) ?: return RoadResult("messages.roadNoSession")
        cancelPreview(player, session)
        return RoadResult("messages.roadCancelled", mapOf("%count%" to session.planned.size.toString()))
    }

    fun discard(player: Player) {
        sessions.remove(player.uniqueId)?.let { cancelPreview(player, it) }
    }

    fun undo(player: Player): RoadResult {
        val commit = history[player.uniqueId] ?: return RoadResult("messages.roadNothingToUndo")
        val world = plugin.server.getWorld(commit.worldId) ?: return RoadResult("messages.roadUndoConflict")
        if (player.world.uid != world.uid) return RoadResult("messages.roadUndoWrongWorld")
        val blocks = commit.blocks.mapNotNull { change -> resolve(world, change) }
        if (
            blocks.size != commit.blocks.size ||
            blocks.any { (block, change) ->
                block.blockData.asString != change.afterData || plugin.inspectTrail(block) != change.afterState
            }
        ) {
            return RoadResult("messages.roadUndoConflict")
        }
        val beforeData =
            runCatching { blocks.associate { (_, change) -> change to Bukkit.createBlockData(change.beforeData) } }
                .getOrElse { return RoadResult("messages.roadUndoConflict") }
        for ((block, change) in blocks) {
            if (!plugin.canRoadChange(player, block, beforeData.getValue(change).material)) {
                return RoadResult("messages.roadProtected")
            }
        }
        val restored = mutableListOf<RoadBlockRecord>()
        try {
            blocks.forEach { (block, change) ->
                restored += change
                plugin.restoreRoad(player.name, block, beforeData.getValue(change), change.previousState)
            }
        } catch (error: Exception) {
            restoreCommittedRoad(world, restored)
            plugin.logger.severe("Road undo for ${player.name} was rolled back: ${error.message}")
            return RoadResult("messages.roadUndoFailed")
        }
        history.remove(player.uniqueId)
        runCatching { historyStore.save(history) }
            .onFailure { plugin.logger.warning("Road undo succeeded but road-history.yml could not be updated: ${it.message}") }
        return RoadResult("messages.roadUndone", mapOf("%count%" to blocks.size.toString()))
    }

    fun status(player: Player): RoadResult {
        val session = sessions[player.uniqueId]
        return if (session == null) {
            RoadResult(if (history.containsKey(player.uniqueId)) "messages.roadStatusIdleWithUndo" else "messages.roadStatusIdle")
        } else {
            RoadResult(
                "messages.roadStatusActive",
                mapOf(
                    "%profile%" to session.profile.name,
                    "%count%" to session.planned.size.toString(),
                    "%limit%" to settings.maxPlannedBlocks.toString(),
                ),
            )
        }
    }

    fun tick() {
        sessions.entries.toList().forEach { (uuid, session) ->
            val player = plugin.server.getPlayer(uuid)
            if (expired(session)) {
                sessions.remove(uuid)
                player?.let {
                    cancelPreview(it, session)
                    plugin.message(it, "messages.roadExpired")
                }
            }
        }
    }

    fun close() {
        sessions.entries.toList().forEach { (uuid, session) ->
            plugin.server.getPlayer(uuid)?.let { cancelPreview(it, session) }
        }
        sessions.clear()
    }

    private fun validCurrent(pair: Pair<Block, PlannedBlock>): Boolean {
        val (block, planned) = pair
        return block.blockData.asString == planned.beforeData &&
            block.type in settings.paintableMaterials &&
            clearAbove(block)
    }

    private fun surfaceAt(
        world: World,
        x: Int,
        z: Int,
        referenceY: Int,
    ): Block? {
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
        val offsets = buildList {
            add(0)
            for (distance in 1..settings.surfaceSearchDepth) {
                add(distance)
                add(-distance)
            }
        }
        return offsets.asSequence()
            .map { referenceY + it }
            .filter { it >= world.minHeight && it < world.maxHeight - 1 }
            .map { world.getBlockAt(x, it, z) }
            .firstOrNull { block -> block.type in settings.paintableMaterials && clearAbove(block) }
    }

    private fun clearAbove(block: Block): Boolean =
        block.getRelative(0, 1, 0).type.let { material ->
            material.isAir || (!material.isSolid && material != Material.WATER && material != Material.LAVA)
        }

    private fun resolve(world: World, planned: PlannedBlock): Pair<Block, PlannedBlock>? =
        if (world.isChunkLoaded(planned.key.x shr 4, planned.key.z shr 4)) {
            world.getBlockAt(planned.key.x, planned.key.y, planned.key.z) to planned
        } else {
            null
        }

    private fun resolve(world: World, applied: RoadBlockRecord): Pair<Block, RoadBlockRecord>? =
        if (applied.y in world.minHeight until world.maxHeight && world.isChunkLoaded(applied.x shr 4, applied.z shr 4)) {
            world.getBlockAt(applied.x, applied.y, applied.z) to applied
        } else {
            null
        }

    private fun rollback(
        world: World,
        applied: List<RoadBlockRecord>,
    ) {
        applied.asReversed().forEach { block ->
            runCatching {
                plugin.restoreRoad(
                    "RoadCommitRollback",
                    world.getBlockAt(block.x, block.y, block.z),
                    Bukkit.createBlockData(block.beforeData),
                    block.previousState,
                )
            }.onFailure { plugin.logger.severe("Could not roll back road block ${block.x},${block.y},${block.z}: ${it.message}") }
        }
    }

    private fun restoreCommittedRoad(
        world: World,
        restored: List<RoadBlockRecord>,
    ) {
        restored.asReversed().forEach { block ->
            runCatching {
                plugin.restoreRoad(
                    "RoadUndoRollback",
                    world.getBlockAt(block.x, block.y, block.z),
                    Bukkit.createBlockData(block.afterData),
                    block.afterState,
                )
            }.onFailure { plugin.logger.severe("Could not roll back road undo at ${block.x},${block.y},${block.z}: ${it.message}") }
        }
    }

    private fun cancelPreview(
        player: Player,
        session: Session,
    ) {
        val world = plugin.server.getWorld(session.worldId) ?: return
        if (player.world.uid != world.uid) return
        session.planned.values.forEach { planned ->
            if (world.isChunkLoaded(planned.key.x shr 4, planned.key.z shr 4)) {
                val block = world.getBlockAt(planned.key.x, planned.key.y, planned.key.z)
                player.sendBlockChange(block.location, block.blockData)
            }
        }
    }

    private fun withinSegmentLimits(
        from: Surface,
        to: Surface,
    ): Boolean {
        val xDelta = (to.x - from.x).toLong()
        val zDelta = (to.z - from.z).toLong()
        val maxDistance = settings.maxSegmentDistanceBlocks.toLong()
        return xDelta * xDelta + zDelta * zDelta <= maxDistance * maxDistance &&
            abs(to.y - from.y) <= settings.maxSegmentHeightDifferenceBlocks
    }

    private fun interpolatedY(
        from: Surface,
        to: Surface,
        x: Int,
        z: Int,
    ): Int {
        val xDelta = (to.x - from.x).toDouble()
        val zDelta = (to.z - from.z).toDouble()
        val lengthSquared = xDelta * xDelta + zDelta * zDelta
        if (lengthSquared == 0.0) return to.y
        val progress = (((x - from.x) * xDelta + (z - from.z) * zDelta) / lengthSquared).coerceIn(0.0, 1.0)
        return (from.y + (to.y - from.y) * progress).roundToInt()
    }

    private fun expired(session: Session): Boolean =
        clockMillis() - session.startedAt >= settings.previewExpirySeconds * 1000L

    private data class Surface(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private data class BlockKey(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private data class PlannedBlock(
        val key: BlockKey,
        val lane: Int,
        val beforeData: String,
        val afterData: String,
    )

    private data class Session(
        val worldId: UUID,
        val profile: RoadProfile,
        var lastCenter: Surface,
        val startedAt: Long,
        val planned: LinkedHashMap<BlockKey, PlannedBlock> = linkedMapOf(),
        var capped: Boolean = false,
    )

    private companion object {
        const val MAX_HISTORY_PLAYERS = 10
    }
}
