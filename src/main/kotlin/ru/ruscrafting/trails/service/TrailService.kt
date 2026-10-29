package ru.ruscrafting.trails.service

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.Player
import ru.ruscrafting.trails.domain.DecayDecision
import ru.ruscrafting.trails.domain.ProgressDecision
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.domain.TrailEnvironment
import ru.ruscrafting.trails.domain.TrailProgressEngine
import ru.ruscrafting.trails.domain.TrailStage
import ru.ruscrafting.trails.storage.TrailBlockState
import ru.ruscrafting.trails.storage.TrailBlockStore
import ru.ruscrafting.trails.storage.TrailBlockPosition
import java.util.UUID
import kotlin.random.Random

class TrailService(
    private val catalog: TrailCatalog,
    private val store: TrailBlockStore,
    private val observer: BlockChangeObserver = BlockChangeObserver.NONE,
    private val randomPercent: () -> Double = { Random.nextDouble(0.0, 100.0) },
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val environmentOf: (Block) -> TrailEnvironment = { block ->
        TrailEnvironment(block.world.name, block.biome.key.toString())
    },
) {
    private val progress = TrailProgressEngine(catalog)
    private val activity = mutableMapOf<TrailActivityChunk, MutableMap<Int, Long>>()

    fun movementContext(block: Block): TrailMovementContext {
        val stored = store.read(block)
        return TrailMovementContext(
            block,
            stored,
            catalog.resolve(block.type.name, stored?.identity, environmentOf(block)),
        )
    }

    fun walk(
        player: Player,
        block: Block,
        sprintModifier: Double,
        forced: Boolean = false,
        popularThreshold: Int? = null,
        wideningDirection: BlockFace? = null,
        sprinting: Boolean = player.isSprinting,
        canChange: (Block, Material) -> Boolean = { _, _ -> true },
    ): TrailWalkResult =
        walk(player, movementContext(block), sprintModifier, forced, popularThreshold, wideningDirection, sprinting, canChange)

    fun walk(
        player: Player,
        context: TrailMovementContext,
        sprintModifier: Double,
        forced: Boolean = false,
        popularThreshold: Int? = null,
        wideningDirection: BlockFace? = null,
        sprinting: Boolean = player.isSprinting,
        canChange: (Block, Material) -> Boolean = { _, _ -> true },
    ): TrailWalkResult {
        val stored = context.stored
        val stage = context.stage ?: return TrailWalkResult.NoChange
        val previousWalks = stored?.walks ?: 0
        return when (
            val decision =
                progress.walk(
                    stage = stage,
                    currentWalks = previousWalks,
                    randomPercent = randomPercent(),
                    sprintModifier = if (sprinting) sprintModifier else 1.0,
                    forced = forced,
                    popularThreshold = popularThreshold,
                )
        ) {
            ProgressDecision.NoChange -> TrailWalkResult.NoChange
            is ProgressDecision.Counted -> {
                store.write(context.block, TrailBlockState(decision.stage.identity, decision.walks))
                recordActivity(context.block)
                TrailWalkResult.Counted(decision.stage, previousWalks, decision.walks)
            }
            is ProgressDecision.Advanced -> {
                val material = Material.valueOf(decision.to.material)
                val clearance = trailClearance(context.block)
                if (!canChange(context.block, material) || clearance.any { !canChange(it.block, Material.AIR) })
                    return TrailWalkResult.NoChange
                changeMaterial(player.name, context.block, material, clearance)
                store.write(context.block, TrailBlockState(decision.to.identity, 0))
                recordActivity(context.block)
                TrailWalkResult.Advanced(decision.from, decision.to)
            }
            is ProgressDecision.TerminalCounted -> {
                store.write(context.block, TrailBlockState(decision.stage.identity, decision.walks))
                recordActivity(context.block)
                TrailWalkResult.PopularCounted(decision.stage, decision.walks)
            }
            is ProgressDecision.Popular -> {
                val shoulders =
                    wideningDirection?.let { direction ->
                        widen(player.name, context.block, decision.stage, direction, canChange)
                    }.orEmpty()
                val retainedWalks = if (shoulders.isEmpty()) maxOf(0, (popularThreshold ?: 1) - 1) else 0
                store.write(context.block, TrailBlockState(decision.stage.identity, retainedWalks))
                recordActivity(context.block)
                if (shoulders.isEmpty()) {
                    TrailWalkResult.PopularCounted(decision.stage, retainedWalks)
                } else {
                    TrailWalkResult.Widened(decision.stage, shoulders)
                }
            }
        }
    }

    fun decay(
        block: Block,
        fraction: Double,
        canChange: (Material) -> Boolean = { true },
    ): Boolean {
        if (!store.isReady(block.chunk)) {
            store.prepare(block.chunk)
            return false
        }
        val stored = store.read(block) ?: return false
        val stage = catalog.resolve(block.type.name, stored.identity, environmentOf(block)) ?: return false
        return when (val decision = progress.decay(stage, stored.walks, fraction)) {
            DecayDecision.NoChange -> false
            DecayDecision.Cleared -> {
                clear(block)
                true
            }
            is DecayDecision.CountedDown -> {
                store.write(block, TrailBlockState(decision.stage.identity, decision.walks))
                true
            }
            is DecayDecision.Regressed -> {
                val material = Material.valueOf(decision.to.material)
                if (!canChange(material)) return false
                changeMaterial("NaturalTrailDecay", block, material)
                store.write(
                    block,
                    TrailBlockState(
                        identity = decision.to.identity.takeUnless { decision.to.index == 0 },
                        walks = decision.walks,
                    ),
                )
                true
            }
        }
    }

    fun speedMultiplier(block: Block, onlyTrackedTrails: Boolean): Double {
        return speedMultiplier(movementContext(block), onlyTrackedTrails)
    }

    fun speedMultiplier(context: TrailMovementContext, onlyTrackedTrails: Boolean): Double =
        if (onlyTrackedTrails && context.stored == null) 1.0 else
            (context.stage ?: context.stored?.let { roadStairStage(context.block, it.identity) })?.speedMultiplier ?: 1.0

    /** Metadata registration never replaces the caller's accepted road or resets its wear. */
    fun registerSurface(block: Block): Boolean {
        val previous = store.read(block)
        val stage = catalog.resolve(block.type.name, previous?.identity, environmentOf(block))
            ?: roadStairStage(block, previous?.identity) ?: return false
        if (previous != null) return true
        store.write(block, TrailBlockState(stage.identity, 0))
        recordActivity(block)
        return true
    }

    private fun roadStairStage(block: Block, identity: ru.ruscrafting.trails.domain.TrailIdentity?): TrailStage? {
        if (!block.type.name.endsWith("_STAIRS")) return null
        val stairs = block.blockData as? Stairs ?: return null
        if (stairs.half != Bisected.Half.BOTTOM || stairs.isWaterlogged) return null
        val base = block.type.name.removeSuffix("_STAIRS")
        val material = Material.matchMaterial(base) ?: Material.matchMaterial("${base}_PLANKS") ?: return null
        val environment = environmentOf(block)
        return catalog.resolve(material.name, identity, environment)
            ?: if (material.name.endsWith("_PLANKS")) catalog.resolve(Material.COBBLESTONE.name, identity, environment) else null
    }

    fun canAffect(block: Block): Boolean = canAffect(movementContext(block))

    fun canAffect(context: TrailMovementContext): Boolean = context.stage != null

    fun trackedBlocks(
        chunk: Chunk,
        limit: Int = Int.MAX_VALUE,
    ): Collection<Block> = store.trackedBlocks(chunk, limit)

    fun nextTrackedBlock(chunk: Chunk, afterPosition: Int?): Pair<Int, Block>? = store.nextTrackedBlock(chunk, afterPosition)

    fun isChunkReady(chunk: Chunk): Boolean = store.isReady(chunk)

    fun prepareChunk(chunk: Chunk) = store.prepare(chunk)

    fun inspect(block: Block): TrailBlockState? = store.read(block)

    fun inspection(block: Block): TrailInspection? {
        val stored = store.read(block) ?: return null
        val stage = catalog.resolve(block.type.name, stored.identity, environmentOf(block)) ?: return null
        return TrailInspection(stage, catalog.next(stage), stored.walks)
    }

    fun next(stage: TrailStage): TrailStage? = catalog.next(stage)

    fun clear(block: Block) {
        forgetActivity(block.world.uid, chunkKey(block), position(block))
        store.clear(block)
    }

    fun lastActivityMillis(block: Block): Long? = activity[activityChunk(block)]?.get(position(block))

    /** Activity timestamps are only meaningful while the corresponding chunk remains loaded. */
    fun forgetActivity(chunk: Chunk) {
        activity.remove(TrailActivityChunk(chunk.world.uid, chunk.chunkKey))
    }

    internal fun forgetActivity(worldId: UUID, chunkKey: Long, position: Int) {
        val key = TrailActivityChunk(worldId, chunkKey)
        val values = activity[key] ?: return
        values.remove(position)
        if (values.isEmpty()) activity.remove(key)
    }

    fun isDecayEdge(block: Block): Boolean {
        val center = store.read(block) ?: return false
        val stage = catalog.resolve(block.type.name, center.identity, environmentOf(block)) ?: return false
        var connected = 0
        CARDINAL_FACES.forEach { face ->
            val neighbour = block.getRelative(face)
            if (!neighbour.world.isChunkLoaded(neighbour.x shr 4, neighbour.z shr 4)) return@forEach
            if (!store.isReady(neighbour.chunk)) {
                store.prepare(neighbour.chunk)
                return false
            }
            val neighbourState = store.read(neighbour) ?: return@forEach
            if (catalog.resolve(neighbour.type.name, neighbourState.identity, environmentOf(neighbour))?.trailName == stage.trailName) connected++
        }
        return connected <= 1
    }

    fun move(
        blocks: Collection<Block>,
        direction: BlockFace,
    ) {
        val sources = blocks.distinct()
        val movements = sources.mapNotNull { block -> store.read(block)?.let { block.getRelative(direction) to it } }
        val activityMovements = sources.mapNotNull { block -> lastActivityMillis(block)?.let { block.getRelative(direction) to it } }
        val destinations = sources.map { it.getRelative(direction) }
        (sources + destinations).distinct().forEach(store::clear)
        movements.forEach { (block, state) -> store.write(block, state) }
        (sources + destinations).forEach { forgetActivity(it.world.uid, chunkKey(it), position(it)) }
        activityMovements.forEach { (block, timestamp) -> activity.getOrPut(activityChunk(block)) { mutableMapOf() }[position(block)] = timestamp }
    }

    fun placeRoad(
        actor: String,
        block: Block,
        afterData: BlockData,
    ): TrailBlockState? {
        val previous = store.read(block)
        changeBlockData(actor, block, afterData)
        if (afterData.material.isAir) {
            store.clear(block)
            return previous
        }
        // Road palettes are independent from natural trail stages. Known materials retain
        // their trail identity; decorative road-only materials are still tracked for undo.
        val identity = catalog.resolve(block.type.name, null)?.identity
        store.write(block, TrailBlockState(identity, 0))
        recordActivity(block)
        return previous
    }

    fun restoreRoad(
        actor: String,
        block: Block,
        beforeData: BlockData,
        previous: TrailBlockState?,
    ) {
        changeBlockData(actor, block, beforeData)
        if (previous == null) {
            clear(block)
        } else {
            store.write(block, previous)
            recordActivity(block)
        }
    }

    private fun widen(
        actor: String,
        center: Block,
        terminal: TrailStage,
        direction: BlockFace,
        canChange: (Block, Material) -> Boolean,
    ): List<Block> {
        val previous = catalog.previous(terminal) ?: return emptyList()
        val faces =
            when (direction) {
                BlockFace.NORTH, BlockFace.SOUTH -> listOf(BlockFace.EAST, BlockFace.WEST)
                BlockFace.EAST, BlockFace.WEST -> listOf(BlockFace.NORTH, BlockFace.SOUTH)
                else -> return emptyList()
            }
        val shoulders = faces.map(center::getRelative)
        shoulders.forEach { shoulder ->
            if (!shoulder.world.isChunkLoaded(shoulder.x shr 4, shoulder.z shr 4)) return emptyList()
            if (!store.isReady(shoulder.chunk)) {
                store.prepare(shoulder.chunk)
                return emptyList()
            }
        }
        val target = Material.valueOf(previous.material)
        val clearance = shoulders.associateWith(::trailClearance)
        if (shoulders.any { shoulder ->
                store.read(shoulder) != null ||
                    catalog.resolve(shoulder.type.name, null, environmentOf(shoulder))?.trailName != terminal.trailName ||
                    !canChange(shoulder, target) || clearance.getValue(shoulder).any { !canChange(it.block, Material.AIR) }
            }
        ) {
            return emptyList()
        }
        shoulders.forEach { shoulder ->
            changeMaterial(actor, shoulder, target, clearance.getValue(shoulder))
            store.write(shoulder, TrailBlockState(previous.identity, 0))
            recordActivity(shoulder)
        }
        return shoulders
    }

    private fun recordActivity(block: Block) {
        activity.getOrPut(activityChunk(block)) { mutableMapOf() }[position(block)] = currentTimeMillis()
    }

    private fun activityChunk(block: Block): TrailActivityChunk = TrailActivityChunk(block.world.uid, chunkKey(block))
    private fun chunkKey(block: Block): Long = ((block.x shr 4).toLong() and 0xffff_ffffL) or ((block.z shr 4).toLong() shl 32)
    private fun position(block: Block): Int = TrailBlockPosition.pack(block.x and 15, block.y, block.z and 15)

    /** Natural wear clears grass and ferns only; the road builder owns its broader obstruction policy. */
    private fun trailClearance(ground: Block): List<BlockState> {
        if (ground.y + 1 >= ground.world.maxHeight) return emptyList()
        val lower = ground.getRelative(BlockFace.UP)
        if (lower.type in setOf(Material.SHORT_GRASS, Material.FERN)) return listOf(lower.state)
        if (lower.type !in setOf(Material.TALL_GRASS, Material.LARGE_FERN) ||
            (lower.blockData as? Bisected)?.half != Bisected.Half.BOTTOM || lower.y + 1 >= lower.world.maxHeight)
            return emptyList()
        val upper = lower.getRelative(BlockFace.UP)
        if (upper.type != lower.type || (upper.blockData as? Bisected)?.half != Bisected.Half.TOP) return emptyList()
        return listOf(upper.state, lower.state)
    }

    private fun changeMaterial(actor: String, block: Block, material: Material, clearance: List<BlockState> = emptyList()) {
        val before = block.state
        block.setType(material, false)
        val after = block.state
        observer.changed(actor, before, after)
        if (clearance.isNotEmpty() && block.type == material && clearance.all { it.block.blockData.asString == it.blockData.asString }) {
            clearance.forEach { changeMaterial(actor, it.block, Material.AIR) }
        }
    }

    private fun changeBlockData(actor: String, block: Block, blockData: BlockData) {
        val before = block.state
        block.setBlockData(blockData, false)
        val after = block.state
        observer.changed(actor, before, after)
    }

    private companion object {
        val CARDINAL_FACES = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
    }
}

class TrailMovementContext internal constructor(
    val block: Block,
    val stored: TrailBlockState?,
    val stage: TrailStage?,
)

sealed interface TrailWalkResult {
    data object NoChange : TrailWalkResult

    data class Counted(
        val stage: TrailStage,
        val previousWalks: Int,
        val walks: Int,
    ) : TrailWalkResult

    data class Advanced(
        val from: TrailStage,
        val to: TrailStage,
    ) : TrailWalkResult

    data class PopularCounted(
        val stage: TrailStage,
        val walks: Int,
    ) : TrailWalkResult

    data class Widened(
        val stage: TrailStage,
        val shoulders: List<Block>,
    ) : TrailWalkResult
}

data class TrailInspection(
    val stage: TrailStage,
    val next: TrailStage?,
    val walks: Int,
)

private data class TrailActivityChunk(
    val world: UUID,
    val chunkKey: Long,
)
