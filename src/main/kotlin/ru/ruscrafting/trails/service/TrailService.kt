package ru.ruscrafting.trails.service

import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.ruscrafting.trails.domain.DecayDecision
import ru.ruscrafting.trails.domain.ProgressDecision
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.domain.TrailEnvironment
import ru.ruscrafting.trails.domain.TrailProgressEngine
import ru.ruscrafting.trails.domain.TrailStage
import ru.ruscrafting.trails.storage.TrailBlockState
import ru.ruscrafting.trails.storage.TrailBlockStore
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
    private val activity = mutableMapOf<TrailBlockKey, Long>()

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
        canChange: (Block, Material) -> Boolean = { _, _ -> true },
    ): TrailWalkResult =
        walk(player, movementContext(block), sprintModifier, forced, popularThreshold, wideningDirection, canChange)

    fun walk(
        player: Player,
        context: TrailMovementContext,
        sprintModifier: Double,
        forced: Boolean = false,
        popularThreshold: Int? = null,
        wideningDirection: BlockFace? = null,
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
                    sprintModifier = if (player.isSprinting) sprintModifier else 1.0,
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
                if (!canChange(context.block, material)) return TrailWalkResult.NoChange
                changeMaterial(player.name, context.block, material)
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
        if (onlyTrackedTrails && context.stored == null) 1.0 else context.stage?.speedMultiplier ?: 1.0

    fun canAffect(block: Block): Boolean = canAffect(movementContext(block))

    fun canAffect(context: TrailMovementContext): Boolean = context.stage != null

    fun trackedBlocks(
        chunk: Chunk,
        limit: Int = Int.MAX_VALUE,
    ): Collection<Block> = store.trackedBlocks(chunk, limit)

    fun inspect(block: Block): TrailBlockState? = store.read(block)

    fun inspection(block: Block): TrailInspection? {
        val stored = store.read(block) ?: return null
        val stage = catalog.resolve(block.type.name, stored.identity, environmentOf(block)) ?: return null
        return TrailInspection(stage, catalog.next(stage), stored.walks)
    }

    fun next(stage: TrailStage): TrailStage? = catalog.next(stage)

    fun clear(block: Block) {
        activity.remove(key(block))
        store.clear(block)
    }

    fun lastActivityMillis(block: Block): Long? = activity[key(block)]

    /** Activity timestamps are only meaningful while the corresponding chunk remains loaded. */
    fun forgetActivity(chunk: Chunk) {
        val world = chunk.world.uid
        activity.keys.removeIf { key ->
            key.world == world && key.x shr 4 == chunk.x && key.z shr 4 == chunk.z
        }
    }

    fun isDecayEdge(block: Block): Boolean {
        val center = store.read(block) ?: return false
        val stage = catalog.resolve(block.type.name, center.identity, environmentOf(block)) ?: return false
        val connected =
            CARDINAL_FACES.count { face ->
                val neighbour = block.getRelative(face)
                if (!neighbour.world.isChunkLoaded(neighbour.x shr 4, neighbour.z shr 4)) return@count false
                val neighbourState = store.read(neighbour) ?: return@count false
                catalog.resolve(neighbour.type.name, neighbourState.identity, environmentOf(neighbour))?.trailName == stage.trailName
            }
        return connected <= 1
    }

    fun move(
        blocks: Collection<Block>,
        direction: BlockFace,
    ) {
        val sources = blocks.distinct()
        val movements = sources.mapNotNull { block -> store.read(block)?.let { block.getRelative(direction) to it } }
        val activityMovements = sources.mapNotNull { block -> activity[key(block)]?.let { block.getRelative(direction) to it } }
        val destinations = sources.map { it.getRelative(direction) }
        (sources + destinations).distinct().forEach(store::clear)
        movements.forEach { (block, state) -> store.write(block, state) }
        (sources + destinations).forEach { activity.remove(key(it)) }
        activityMovements.forEach { (block, timestamp) -> activity[key(block)] = timestamp }
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
        val target = Material.valueOf(previous.material)
        if (shoulders.any { shoulder ->
                store.read(shoulder) != null ||
                    catalog.resolve(shoulder.type.name, null, environmentOf(shoulder))?.trailName != terminal.trailName ||
                    !canChange(shoulder, target)
            }
        ) {
            return emptyList()
        }
        shoulders.forEach { shoulder ->
            changeMaterial(actor, shoulder, target)
            store.write(shoulder, TrailBlockState(previous.identity, 0))
            recordActivity(shoulder)
        }
        return shoulders
    }

    private fun recordActivity(block: Block) {
        activity[key(block)] = currentTimeMillis()
    }

    private fun key(block: Block): TrailBlockKey = TrailBlockKey(block.world.uid, block.x, block.y, block.z)

    private fun changeMaterial(actor: String, block: Block, material: Material) {
        val before = block.state
        block.setType(material, false)
        val after = block.state
        observer.changed(actor, before, after)
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

private data class TrailBlockKey(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)
