package ru.ruscrafting.trails.bukkit

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
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
    private var surfacePolicy = RoadSurfacePolicy(initialSettings)
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
        surfacePolicy = RoadSurfacePolicy(newSettings)
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
        val session =
            Session(
                worldId = player.world.uid,
                profile = profile,
                lastCenter = Surface(center.x, center.y, center.z),
                startedAt = clockMillis(),
            )
        sessions[player.uniqueId] = session
        val substitutedMaterials =
            (
                profile.laneMaterials +
                    settings.heightTransitionPalette(profile)?.materials.orEmpty() +
                    profile.decorationPatterns.flatMap { pattern ->
                        pattern.placements.flatMap { it.palette.materials }
                    }
            ).distinct()
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
        if (player.isFlying && !settings.captureWhileFlying) return true
        if (expired(session) || location.world?.uid != session.worldId) {
            cancel(player)
            return false
        }
        val center = surfaceAt(player.world, location.blockX, location.blockZ, location.blockY - 1) ?: return true
        val current = Surface(center.x, center.y, center.z)
        val previous = session.lastCenter
        if (current.x == previous.x && current.z == previous.z) return true
        if (!withinSegmentLimits(previous, current)) {
            planSegment(player, session, previous, current, onlyLastRow = true)
            session.lastCenter = current
            return true
        }
        planSegment(player, session, previous, current)
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
            if (!plugin.canRoadChange(player, block, planned.afterData.material)) {
                return RoadResult("messages.roadProtected")
            }
        }
        val compensateRemovedBlocks =
            settings.returnReplacedBlocksInSurvival &&
                player.gameMode == GameMode.SURVIVAL &&
                player.hasPermission(RETURN_BLOCKS_PERMISSION)
        val removedMaterials =
            if (compensateRemovedBlocks) {
                resolved.map { (_, planned) -> Bukkit.createBlockData(planned.beforeData).material }
            } else {
                emptyList()
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
                        planned.afterData.asString,
                        previous,
                        TrailBlockState(null, 0),
                    )
                applied += record
                plugin.placeRoad(player.name, block, planned.afterData)
                applied[applied.lastIndex] = record.copy(afterState = checkNotNull(plugin.inspectTrail(block)))
            }
        } catch (error: Exception) {
            rollback(world, applied)
            plugin.logger.severe("Road commit for ${player.name} rolled back: ${error.message}")
            return RoadResult("messages.roadCommitFailed")
        }
        val historyBefore = LinkedHashMap(history)
        history.remove(player.uniqueId)
        if (!compensateRemovedBlocks) history[player.uniqueId] = RoadCommitRecord(world.uid, clockMillis(), applied)
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
            player.sendBlockChange(block.location, block.blockData)
        }
        val notices =
            if (compensateRemovedBlocks) {
                val delivery = RoadBlockCompensation.deliver(player, removedMaterials)
                buildList {
                    add(
                        RoadNotice(
                            "messages.roadBlocksReturned",
                            mapOf("%count%" to delivery.returnedItems.toString()),
                        ),
                    )
                    if (delivery.droppedItems > 0) {
                        add(
                            RoadNotice(
                                "messages.roadBlocksDropped",
                                mapOf("%count%" to delivery.droppedItems.toString()),
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }
        return RoadResult(
            if (session.capped) "messages.roadCommittedCapped" else "messages.roadCommitted",
            mapOf("%count%" to applied.size.toString()),
            notices,
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

    private fun planSegment(
        player: Player,
        session: Session,
        from: Surface,
        to: Surface,
        onlyLastRow: Boolean = false,
    ) {
        val fromPoint = RoadPoint(from.x, from.z)
        val toPoint = RoadPoint(to.x, to.z)
        val geometryRows = RoadGeometry.rows(fromPoint, toPoint, session.profile.width)
        val selectedRows = if (onlyLastRow) geometryRows.takeLast(1) else geometryRows
        val rows =
            selectedRows.map { row ->
                val referenceY = interpolatedY(from, to, row.center.x, row.center.z)
                ResolvedRoadRow(
                    center = row.center,
                    cells =
                        row.cells.mapNotNull { cell ->
                            surfaceAt(player.world, cell.x, cell.z, referenceY)?.let { block -> cell.lane to block }
                        }.toMap(),
                )
            }
        rows.forEach { row ->
            row.cells.forEach { (lane, block) ->
                val palette = session.profile.lanePalettes[lane + session.profile.width / 2]
                val material = palette.select(paletteSample(session, block, lane, PlanRole.SURFACE))
                planBlock(player, session, block, lane, material.createBlockData(), PlanRole.SURFACE)
            }
        }
        settings.heightTransitionPalette(session.profile)?.let { transitionPalette ->
            rows.zipWithNext().forEach { (fromRow, toRow) ->
                fromRow.cells.keys.intersect(toRow.cells.keys).forEach { lane ->
                    val fromBlock = fromRow.cells.getValue(lane)
                    val toBlock = toRow.cells.getValue(lane)
                    val heightDifference = toBlock.y - fromBlock.y
                    if (abs(heightDifference) == 1) {
                        val ascending = heightDifference > 0
                        val highBlock = if (ascending) toBlock else fromBlock
                        val transitionMaterial =
                            transitionPalette.select(paletteSample(session, highBlock, lane, PlanRole.HEIGHT_TRANSITION))
                        val transition =
                            RoadHeightTransitionFactory.create(
                                transitionMaterial,
                                fromRow.center,
                                toRow.center,
                                ascending,
                            )
                        planBlock(player, session, highBlock, lane, transition, PlanRole.HEIGHT_TRANSITION)
                    }
                }
            }
        }
        val newRows = if (onlyLastRow) rows else rows.drop(1)
        newRows.forEachIndexed { index, row ->
            val previousCenter = if (onlyLastRow) fromPoint else rows[index].center
            planPatterns(player, session, previousCenter, row)
        }
    }

    private fun planPatterns(
        player: Player,
        session: Session,
        previousCenter: RoadPoint,
        row: ResolvedRoadRow,
    ) {
        session.traversedBlocks++
        val anchor = row.cells[0] ?: return
        session.profile.decorationPatterns.forEach { pattern ->
            if (session.traversedBlocks % pattern.everyBlocks != 0L) return@forEach
            val occurrence = session.traversedBlocks / pattern.everyBlocks
            val side = if (pattern.alternateSides && (occurrence - 1L) % 2L == 1L) -1 else 1
            val face = RoadHeightTransitionFactory.travelFace(previousCenter, row.center)
            val forwardX = face.modX
            val forwardZ = face.modZ
            val rightX = -forwardZ
            val rightZ = forwardX
            val targets =
                pattern.placements.map { placement ->
                    val lateral = placement.lateral * side
                    val x = anchor.x + placement.forward * forwardX + lateral * rightX
                    val y = anchor.y + placement.vertical
                    val z = anchor.z + placement.forward * forwardZ + lateral * rightZ
                    placement to BlockKey(x, y, z)
                }
            if (
                targets.any { (_, key) ->
                    key.y !in player.world.minHeight until player.world.maxHeight ||
                        !player.world.isChunkLoaded(key.x shr 4, key.z shr 4) ||
                        !player.world.getBlockAt(key.x, key.y, key.z).type.isAir ||
                        session.planned.containsKey(key)
                }
            ) {
                return@forEach
            }
            if (session.planned.size + targets.size > settings.maxPlannedBlocks) {
                session.capped = true
                return@forEach
            }
            targets.forEachIndexed { placementIndex, (placement, key) ->
                val block = player.world.getBlockAt(key.x, key.y, key.z)
                val material =
                    placement.palette.select(
                        paletteSample(session, block, placementIndex, PlanRole.DECORATION),
                    )
                planBlock(player, session, block, placementIndex, material.createBlockData(), PlanRole.DECORATION)
            }
        }
    }

    private fun planBlock(
        player: Player,
        session: Session,
        block: Block,
        lane: Int,
        afterData: BlockData,
        role: PlanRole,
    ) {
        val key = BlockKey(block.x, block.y, block.z)
        val existing = session.planned[key]
        if (existing == null && block.blockData.asString == afterData.asString) return
        if (existing == null && session.planned.size >= settings.maxPlannedBlocks) {
            session.capped = true
            return
        }
        val shouldReplace =
            existing == null ||
                role.priority > existing.role.priority ||
                (role == PlanRole.HEIGHT_TRANSITION && existing.role == role) ||
                (role == existing.role && abs(lane) < abs(existing.lane))
        if (!shouldReplace) return
        val planned =
            PlannedBlock(
                key = key,
                lane = lane,
                beforeData = existing?.beforeData ?: block.blockData.asString,
                afterData = afterData.clone(),
                role = role,
            )
        session.planned[key] = planned
        val previewData = previewSelector.select(afterData, block.location).blockData
        player.sendBlockChange(block.location, previewData)
    }

    private fun validCurrent(pair: Pair<Block, PlannedBlock>): Boolean {
        val (block, planned) = pair
        if (block.blockData.asString != planned.beforeData) return false
        return if (planned.role == PlanRole.DECORATION) {
            block.type.isAir
        } else {
            surfacePolicy.canReplace(block) && surfacePolicy.hasWalkableTop(block)
        }
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
            .firstOrNull(surfacePolicy::hasWalkableTop)
            ?.takeIf(surfacePolicy::canReplace)
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

    private fun paletteSample(
        session: Session,
        block: Block,
        lane: Int,
        role: PlanRole,
    ): Long {
        var value = session.paletteSeed
        value = mix(value xor block.x.toLong())
        value = mix(value xor block.y.toLong())
        value = mix(value xor block.z.toLong())
        value = mix(value xor lane.toLong())
        return mix(value xor role.priority.toLong())
    }

    private fun mix(input: Long): Long {
        var value = input
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return value xor (value ushr 33)
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
        val afterData: BlockData,
        val role: PlanRole,
    )

    private data class ResolvedRoadRow(
        val center: RoadPoint,
        val cells: Map<Int, Block>,
    )

    private enum class PlanRole(val priority: Int) {
        SURFACE(0),
        HEIGHT_TRANSITION(1),
        DECORATION(2),
    }

    private data class Session(
        val worldId: UUID,
        val profile: RoadProfile,
        var lastCenter: Surface,
        val startedAt: Long,
        val paletteSeed: Long = worldId.mostSignificantBits xor worldId.leastSignificantBits xor startedAt,
        val planned: LinkedHashMap<BlockKey, PlannedBlock> = linkedMapOf(),
        var capped: Boolean = false,
        var traversedBlocks: Long = 0,
    )

    private companion object {
        const val MAX_HISTORY_PLAYERS = 10
        const val RETURN_BLOCKS_PERMISSION = "trails.roads.collect-drops"
    }
}
