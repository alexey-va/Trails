package ru.ruscrafting.trails.bukkit

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.ruscrafting.trails.TrailsPlugin
import ru.ruscrafting.trails.config.RoadProfile
import ru.ruscrafting.trails.config.RoadSettings
import ru.ruscrafting.trails.domain.RoadGeometry
import ru.ruscrafting.trails.domain.RoadPoint
import ru.ruscrafting.trails.domain.RoadRow
import ru.ruscrafting.trails.storage.RoadBlockRecord
import ru.ruscrafting.trails.storage.RoadCommitRecord
import ru.ruscrafting.trails.storage.RoadHistoryStore
import ru.ruscrafting.trails.storage.TrailBlockState
import java.util.UUID
import kotlin.math.abs

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
                routeSections =
                    mutableListOf(
                        RouteSection(
                            samples = mutableListOf(Surface(center.x, center.y, center.z)),
                            countFirstRow = false,
                        ),
                    ),
            )
        sessions[player.uniqueId] = session
        return RoadResult(
            "messages.roadStarted",
            mapOf("%profile%" to profile.name),
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
        if (session.capped) return true
        val connected = withinSegmentLimits(previous, current)
        val capturedRows =
            if (connected) {
                maxOf(abs(current.x - previous.x), abs(current.z - previous.z))
            } else {
                1
            }
        if (session.capturedRows + capturedRows > settings.maxPlannedBlocks * MAX_CAPTURED_ROW_MULTIPLIER) {
            session.capped = true
            return true
        }
        if (connected) {
            session.routeSections.last().samples += current
        } else {
            session.routeSections +=
                RouteSection(
                    samples = mutableListOf(current),
                    countFirstRow = true,
                    arrivalFrom = RoadPoint(previous.x, previous.z),
                )
        }
        session.capturedRows += capturedRows
        session.lastCenter = current
        session.dirty = true
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
        refreshPlan(player, session)
        if (session.planned.isEmpty()) return RoadResult("messages.roadEmpty")
        val resolved = session.planned.values.mapNotNull { resolve(world, it) }
        if (resolved.size != session.planned.size || resolved.any { !validCurrent(it, session.planned) }) {
            return RoadResult("messages.roadConflict")
        }
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
                applied[applied.lastIndex] = record.copy(afterState = plugin.inspectTrail(block))
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
            refreshPlan(player, session)
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
            } else if (player != null) {
                refreshPlan(player, session)
            }
        }
    }

    fun close() {
        sessions.entries.toList().forEach { (uuid, session) ->
            plugin.server.getPlayer(uuid)?.let { cancelPreview(it, session) }
        }
        sessions.clear()
    }

    private fun rebuildPlan(
        player: Player,
        session: Session,
    ) {
        val draft = PlanDraft(baseline = LinkedHashMap(session.planned))
        val sections = session.routeSections.map { section -> resolveSection(player.world, session.profile, section) }

        sections.flatMap(BuiltRoadSection::rows).forEach { row ->
            row.cells.forEach { (lane, cell) ->
                val block = cell.surface
                val palette = session.profile.lanePalettes[lane + session.profile.width / 2]
                val material = palette.select(paletteSample(session, block, lane, PlanRole.SURFACE))
                planBlock(session, draft, block, lane, material.createBlockData(), PlanRole.SURFACE)
            }
        }
        sections.flatMap(BuiltRoadSection::rows).forEach { row ->
            row.cells.forEach { (lane, cell) ->
                cell.clearance.forEach { block ->
                    planBlock(session, draft, block, lane, Material.AIR.createBlockData(), PlanRole.CLEARANCE)
                }
            }
        }
        settings.heightTransitionPalette(session.profile)?.let { transitionPalette ->
            sections.forEach { section -> planHeightTransitions(session, draft, section.rows, transitionPalette) }
        }

        val roadColumns =
            sections
                .asSequence()
                .flatMap { it.geometryRows.asSequence() }
                .flatMap { it.row.cells.asSequence() }
                .map { it.x to it.z }
                .toHashSet()
        sections.forEach { section ->
            val rows = if (section.countFirstRow) section.rows else section.rows.drop(1)
            rows.forEach { row -> planPatterns(player, session, draft, roadColumns, row) }
        }

        updatePreview(player, session.planned, draft.planned)
        session.planned.clear()
        session.planned.putAll(draft.planned)
        session.capped = draft.capped
        session.dirty = false
    }

    private fun refreshPlan(
        player: Player,
        session: Session,
    ) {
        if (session.dirty) rebuildPlan(player, session)
    }

    private fun resolveSection(
        world: World,
        profile: RoadProfile,
        section: RouteSection,
    ): BuiltRoadSection {
        val points = section.samples.map { RoadPoint(it.x, it.z) }
        val smoothingTolerance =
            if (settings.smoothingEnabled) {
                minOf(settings.smoothingToleranceBlocks, profile.width / 2.0)
            } else {
                0.0
            }
        val anchors =
            RoadGeometry.smooth(
                points,
                smoothingTolerance,
            )
        val geometryRows =
            if (anchors.size == 1 && section.arrivalFrom != null) {
                val arrival = section.arrivalFrom
                val landing = anchors.single()
                listOf(
                    DirectedRoadRow(
                        RoadGeometry.row(landing, arrival, landing, profile.width),
                        arrival,
                        landing,
                    ),
                )
            } else {
                anchors.zipWithNext().flatMapIndexed { index, (from, to) ->
                    RoadGeometry.rows(from, to, profile.width)
                        .let { rows -> if (index == 0) rows else rows.drop(1) }
                        .map { row -> DirectedRoadRow(row, from, to) }
                }
            }
        var referenceY = section.samples.first().y
        val centerSurfaces =
            geometryRows.map { directed ->
                val center = directed.row.center
                surfaceAt(world, center.x, center.z, referenceY)?.also { referenceY = it.y }
            }
        val rowGrades = RoadGeometry.smoothIsolatedGrades(centerSurfaces.map { it?.y })
        val rows =
            geometryRows.mapIndexedNotNull { sequence, directed ->
                val centerSurface = centerSurfaces[sequence] ?: return@mapIndexedNotNull null
                val rowGrade = rowGrades[sequence] ?: return@mapIndexedNotNull null
                ResolvedRoadRow(
                    sequence = sequence,
                    center = directed.row.center,
                    headingFrom = directed.headingFrom,
                    headingTo = directed.headingTo,
                    cells =
                        directed.row.cells.mapNotNull { cell ->
                            val naturalSurface =
                                if (cell.lane == 0) {
                                    centerSurface
                                } else {
                                    surfaceAt(world, cell.x, cell.z, rowGrade)
                                }
                            naturalSurface
                                ?.takeIf { block -> abs(block.y - rowGrade) <= settings.maxCrossSlopeBlocks }
                                ?.let { block -> resolveRoadCell(world, cell.x, cell.z, rowGrade, block) }
                                ?.let { resolved -> cell.lane to resolved }
                        }.toMap(),
                ).takeIf { 0 in it.cells }
            }
        return BuiltRoadSection(geometryRows, rows, section.countFirstRow)
    }

    private fun resolveRoadCell(
        world: World,
        x: Int,
        z: Int,
        rowGrade: Int,
        naturalSurface: Block,
    ): ResolvedRoadCell? {
        if (rowGrade !in world.minHeight until world.maxHeight) return null
        val surface = world.getBlockAt(x, rowGrade, z)
        if (!surfacePolicy.canPlaceSurface(surface)) return null

        val clearance = linkedMapOf<BlockKey, Block>()
        if (naturalSurface.y > rowGrade) {
            for (y in rowGrade + 1..naturalSurface.y) {
                val block = world.getBlockAt(x, y, z)
                if (!surfacePolicy.canExcavate(block)) return null
                clearance[BlockKey(x, y, z)] = block
            }
        }
        for (offset in 1..settings.clearanceHeightBlocks) {
            val y = rowGrade + offset
            if (y >= world.maxHeight) return null
            val block = world.getBlockAt(x, y, z)
            val key = BlockKey(x, y, z)
            if (block.type.isAir || key in clearance) continue
            when {
                surfacePolicy.canClearAbove(block) -> clearance[key] = block
                surfacePolicy.canRemainAboveRoad(block) -> Unit
                else -> return null
            }
        }
        return ResolvedRoadCell(surface, clearance.values.toList())
    }

    private fun planHeightTransitions(
        session: Session,
        draft: PlanDraft,
        rows: List<ResolvedRoadRow>,
        transitionPalette: ru.ruscrafting.trails.config.RoadMaterialPalette,
    ) {
        rows.zipWithNext().forEach { (fromRow, toRow) ->
            if (toRow.sequence != fromRow.sequence + 1) return@forEach
            val fromCenter = fromRow.cells[0]?.surface ?: return@forEach
            val toCenter = toRow.cells[0]?.surface ?: return@forEach
            val centerHeightDifference = toCenter.y - fromCenter.y
            if (abs(centerHeightDifference) != 1) return@forEach
            fromRow.cells.keys.intersect(toRow.cells.keys).forEach { lane ->
                val fromBlock = fromRow.cells.getValue(lane).surface
                val toBlock = toRow.cells.getValue(lane).surface
                if (toBlock.y - fromBlock.y != centerHeightDifference) return@forEach
                val ascending = centerHeightDifference > 0
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
                planBlock(session, draft, highBlock, lane, transition, PlanRole.HEIGHT_TRANSITION)
            }
        }
    }

    private fun planPatterns(
        player: Player,
        session: Session,
        draft: PlanDraft,
        roadColumns: Set<Pair<Int, Int>>,
        row: ResolvedRoadRow,
    ) {
        draft.traversedBlocks++
        val anchor = row.cells[0]?.surface ?: return
        session.profile.decorationPatterns.forEach { pattern ->
            if (draft.traversedBlocks % pattern.everyBlocks != 0L) return@forEach
            val occurrence = draft.traversedBlocks / pattern.everyBlocks
            val side = if (pattern.alternateSides && (occurrence - 1L) % 2L == 1L) -1 else 1
            val face = RoadHeightTransitionFactory.travelFace(row.headingFrom, row.headingTo)
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
                        (key.x to key.z) in roadColumns ||
                        draft.planned.containsKey(key)
                }
            ) {
                return@forEach
            }
            if (draft.planned.size + targets.size > settings.maxPlannedBlocks) {
                draft.capped = true
                return@forEach
            }
            targets.forEachIndexed { placementIndex, (placement, key) ->
                val block = player.world.getBlockAt(key.x, key.y, key.z)
                val material =
                    placement.palette.select(
                        paletteSample(session, block, placementIndex, PlanRole.DECORATION),
                    )
                planBlock(session, draft, block, placementIndex, material.createBlockData(), PlanRole.DECORATION)
            }
        }
    }

    private fun planBlock(
        session: Session,
        draft: PlanDraft,
        block: Block,
        lane: Int,
        afterData: BlockData,
        role: PlanRole,
    ) {
        val key = BlockKey(block.x, block.y, block.z)
        val existing = draft.planned[key]
        if (existing == null && block.blockData.asString == afterData.asString) return
        if (existing == null && draft.planned.size >= settings.maxPlannedBlocks) {
            draft.capped = true
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
                beforeData = existing?.beforeData ?: draft.baseline[key]?.beforeData ?: block.blockData.asString,
                afterData = afterData.clone(),
                role = role,
            )
        draft.planned[key] = planned
    }

    private fun updatePreview(
        player: Player,
        previous: Map<BlockKey, PlannedBlock>,
        current: Map<BlockKey, PlannedBlock>,
    ) {
        previous.forEach { (key, planned) ->
            if (current[key]?.afterData?.asString == planned.afterData.asString) return@forEach
            if (!player.world.isChunkLoaded(key.x shr 4, key.z shr 4)) return@forEach
            val block = player.world.getBlockAt(key.x, key.y, key.z)
            player.sendBlockChange(block.location, block.blockData)
        }
        current.forEach { (key, planned) ->
            if (previous[key]?.afterData?.asString == planned.afterData.asString) return@forEach
            val block = player.world.getBlockAt(key.x, key.y, key.z)
            val previewData =
                if (planned.role == PlanRole.CLEARANCE && surfacePolicy.canClearAbove(block)) {
                    planned.afterData
                } else {
                    previewSelector.select(planned.afterData, block.location).blockData
                }
            player.sendBlockChange(block.location, previewData)
        }
    }

    private fun validCurrent(
        pair: Pair<Block, PlannedBlock>,
        plan: Map<BlockKey, PlannedBlock>,
    ): Boolean {
        val (block, planned) = pair
        if (block.blockData.asString != planned.beforeData) return false
        return when (planned.role) {
            PlanRole.DECORATION -> block.type.isAir
            PlanRole.CLEARANCE -> surfacePolicy.canExcavate(block) || surfacePolicy.canClearAbove(block)
            PlanRole.SURFACE,
            PlanRole.HEIGHT_TRANSITION,
            -> surfacePolicy.canPlaceSurface(block) && roadClearanceStillValid(block, plan)
        }
    }

    private fun roadClearanceStillValid(
        surface: Block,
        plan: Map<BlockKey, PlannedBlock>,
    ): Boolean {
        for (offset in 1..settings.clearanceHeightBlocks) {
            val y = surface.y + offset
            if (y >= surface.world.maxHeight) return false
            val above = surface.world.getBlockAt(surface.x, y, surface.z)
            if (above.type.isAir) continue
            val planned = plan[BlockKey(above.x, above.y, above.z)]
            if (planned?.role == PlanRole.CLEARANCE || surfacePolicy.canRemainAboveRoad(above)) continue
            return false
        }
        return true
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
        val sequence: Int,
        val center: RoadPoint,
        val headingFrom: RoadPoint,
        val headingTo: RoadPoint,
        val cells: Map<Int, ResolvedRoadCell>,
    )

    private data class ResolvedRoadCell(
        val surface: Block,
        val clearance: List<Block>,
    )

    private data class DirectedRoadRow(
        val row: RoadRow,
        val headingFrom: RoadPoint,
        val headingTo: RoadPoint,
    )

    private data class BuiltRoadSection(
        val geometryRows: List<DirectedRoadRow>,
        val rows: List<ResolvedRoadRow>,
        val countFirstRow: Boolean,
    )

    private data class RouteSection(
        val samples: MutableList<Surface>,
        val countFirstRow: Boolean,
        val arrivalFrom: RoadPoint? = null,
    )

    private data class PlanDraft(
        val baseline: Map<BlockKey, PlannedBlock>,
        val planned: LinkedHashMap<BlockKey, PlannedBlock> = linkedMapOf(),
        var capped: Boolean = false,
        var traversedBlocks: Long = 0,
    )

    private enum class PlanRole(val priority: Int) {
        SURFACE(0),
        HEIGHT_TRANSITION(1),
        CLEARANCE(2),
        DECORATION(3),
    }

    private data class Session(
        val worldId: UUID,
        val profile: RoadProfile,
        var lastCenter: Surface,
        val startedAt: Long,
        val routeSections: MutableList<RouteSection>,
        val paletteSeed: Long = worldId.mostSignificantBits xor worldId.leastSignificantBits xor startedAt,
        val planned: LinkedHashMap<BlockKey, PlannedBlock> = linkedMapOf(),
        var capped: Boolean = false,
        var capturedRows: Int = 0,
        var dirty: Boolean = false,
    )

    private companion object {
        const val MAX_HISTORY_PLAYERS = 10
        const val MAX_CAPTURED_ROW_MULTIPLIER = 2
        const val RETURN_BLOCKS_PERMISSION = "trails.roads.collect-drops"
    }
}
