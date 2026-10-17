package ru.ruscrafting.trails.bukkit

import org.bukkit.block.Block
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.service.TrailService
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BoundedAsyncExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.random.Random
import java.util.UUID

class DecayScheduler(
    private val plugin: Plugin,
    private val settings: TrailsSettings,
    private val trailService: TrailService,
    private val nativeWork: BudgetedTaskQueue,
    private val storage: BoundedAsyncExecutor,
    private val canChange: (Block, Material) -> Boolean = { _, _ -> true },
    private val random: Random = Random.Default,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val firstObserved = mutableMapOf<DecayBlockKey, Long>()
    private var latestStats = DecayCycleStats.enabled()
    @Volatile private var closed = false
    private var inFlight: CompletableFuture<Unit>? = null
    private val task: BukkitTask =
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { runCycle() },
            settings.decayTimer,
            settings.decayTimer,
        )

    override fun close() {
        closed = true
        task.cancel()
    }

    internal fun snapshot(): DecayCycleStats = latestStats

    /** One cycle owns one cursor; timer overlap coalesces rather than multiplying scans. */
    internal fun runCycle(): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CancellationException("Decay scheduler is closed"))
        inFlight?.takeUnless { it.isDone }?.let { return it.copy() }
        val cycle = Cycle(plugin.server.worlds.iterator(), currentTimeMillis())
        val result = advance(cycle)
        inFlight = result
        result.whenComplete { _, failure ->
            if (failure != null && !closed && failure.cause !is CancellationException && failure !is CancellationException) {
                plugin.logger.warning("Trail decay cycle failed: ${failure.message}")
            }
        }
        return result.copy()
    }

    private fun advance(cycle: Cycle): CompletableFuture<Unit> = nativeWork.call({ !closed }) {
        captureChunk(cycle)
    }.thenCompose { it }.thenCompose { batch ->
        if (batch == null) finishCycle(cycle) else storage.submit {
            // Both lists are detached numeric positions; no Bukkit getter runs here.
            val candidates = if (settings.decayEdgeFirst && batch.edges.isNotEmpty()) batch.edges else batch.idle
            val count = sampleSize(candidates.size, settings.decayFraction, random.nextDouble())
            Selection(candidates.size, candidates.shuffled(random).take(count))
        }.thenCompose { selection -> nativeWork.call({ !closed }) {
            cycle.candidateBlocks += selection.candidateCount
            val selected = selection.blocks.iterator()
            nativeWork.submit({ !closed }) {
                if (!selected.hasNext()) true else {
                    val position = selected.next()
                    val world = plugin.server.getWorld(position.world)
                    if (world != null && world.isChunkLoaded(position.x shr 4, position.z shr 4)) {
                        val block = world.getBlockAt(position.x, position.y, position.z)
                        val activity = trailService.lastActivityMillis(block) ?: firstObserved[position] ?: cycle.now
                        if (isIdle(activity, cycle.now, settings.decayMinimumIdleMinutes)) {
                            if (nearPlayer(block)) cycle.nearbySkipped++
                            else if (trailService.decay(block, settings.stepDecayFraction) { target -> canChange(block, target) }) {
                                cycle.changedBlocks++
                            }
                        }
                    }
                    !selected.hasNext()
                }
            }
        }.thenCompose { it } }.thenCompose { advance(cycle) }
    }

    private fun captureChunk(cycle: Cycle): CompletableFuture<ChunkBatch?> {
        var batch: ChunkBatch? = null
        var waiting: CompletableFuture<Unit>? = null
        return nativeWork.submit({ !closed }) {
            val chunk = cycle.chunk
            if (chunk == null) {
                when {
                    cycle.chunks.hasNext() -> {
                        val next = cycle.chunks.next()
                        // The loaded-chunk inventory may be older than this cursor step.
                        if (!isLoaded(next)) return@submit false
                        cycle.loadedChunks++
                        cycle.chunk = next
                        cycle.afterPosition = null
                        cycle.idle = arrayListOf()
                        cycle.edges = arrayListOf()
                        cycle.sampled = random.nextDouble() <= settings.chunkChance
                        if (cycle.sampled) cycle.sampledChunks++
                        if (!trailService.isChunkReady(next)) {
                            waiting = trailService.prepareChunk(next)
                            return@submit true
                        }
                    }
                    cycle.worlds.hasNext() -> {
                        val world = cycle.worlds.next()
                        // This is the platform inventory call; block work below yields individually.
                        cycle.chunks = if (settings.worldEnabled(world.name)) world.loadedChunks.iterator() else emptyList<Chunk>().iterator()
                    }
                    else -> return@submit true
                }
                false
            } else if (!isLoaded(chunk)) {
                cycle.chunk = null
                false
            } else {
                val next = trailService.nextTrackedBlock(chunk, cycle.afterPosition)
                if (next == null) {
                    cycle.chunk = null
                    if (cycle.sampled) {
                        batch = ChunkBatch(cycle.idle, cycle.edges)
                        cycle.idle = arrayListOf()
                        cycle.edges = arrayListOf()
                        true
                    } else false
                } else {
                    cycle.afterPosition = next.first
                    val block = next.second
                    val position = key(block)
                    cycle.trackedBlocks++
                    cycle.activeKeys += position
                    if (cycle.sampled) {
                        val activity = trailService.lastActivityMillis(block) ?: firstObserved.getOrPut(position) { cycle.now }
                        if (isIdle(activity, cycle.now, settings.decayMinimumIdleMinutes)) {
                            cycle.idleBlocks++
                            cycle.idle += position
                            if (settings.decayEdgeFirst && trailService.isDecayEdge(block)) cycle.edges += position
                        }
                    }
                    false
                }
            }
        }.thenCompose {
            val preparation = waiting
            if (preparation == null) CompletableFuture.completedFuture(batch) else preparation.handle { _, failure -> failure }.thenCompose { failure ->
                nativeWork.call({ !closed }) {
                    val chunk = cycle.chunk
                    // Unloading an optional decay chunk invalidates its preparation normally;
                    // it must not abandon every later chunk in this world inventory.
                    if (chunk == null || !isLoaded(chunk)) cycle.chunk = null
                    else if (failure != null) throw failure
                    captureChunk(cycle)
                }.thenCompose { it }
            }
        }
    }

    private fun isLoaded(chunk: Chunk): Boolean {
        val world = chunk.world
        return plugin.server.getWorld(world.uid) === world && world.isChunkLoaded(chunk.x, chunk.z)
    }

    private fun finishCycle(cycle: Cycle): CompletableFuture<Unit> {
        val observed = firstObserved.keys.iterator()
        return nativeWork.submit({ !closed }) {
            if (observed.hasNext()) {
                if (observed.next() !in cycle.activeKeys) observed.remove()
                false
            } else {
                latestStats = DecayCycleStats(true, cycle.now, cycle.loadedChunks, cycle.sampledChunks,
                    cycle.trackedBlocks, cycle.idleBlocks, cycle.candidateBlocks, cycle.nearbySkipped, cycle.changedBlocks)
                true
            }
        }
    }

    private fun nearPlayer(block: Block): Boolean {
        if (settings.decayDistance == 0.0) return false
        val location = block.location
        val maximum = settings.decayDistance * settings.decayDistance
        // Paper's spatial query keeps this check local; the predicate retains the old strict sphere.
        return block.world.getNearbyPlayers(location, settings.decayDistance) { player ->
            player.isOnline && player.location.distanceSquared(location) < maximum
        }.isNotEmpty()
    }

    private class Cycle(val worlds: Iterator<World>, val now: Long) {
        var chunks: Iterator<Chunk> = emptyList<Chunk>().iterator()
        var chunk: Chunk? = null
        var afterPosition: Int? = null
        var sampled = false
        var idle = arrayListOf<DecayBlockKey>()
        var edges = arrayListOf<DecayBlockKey>()
        val activeKeys = hashSetOf<DecayBlockKey>()
        var loadedChunks = 0
        var sampledChunks = 0
        var trackedBlocks = 0
        var idleBlocks = 0
        var candidateBlocks = 0
        var nearbySkipped = 0
        var changedBlocks = 0
    }

    private data class ChunkBatch(val idle: List<DecayBlockKey>, val edges: List<DecayBlockKey>)
    private data class Selection(val candidateCount: Int, val blocks: List<DecayBlockKey>)

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        internal fun sampleSize(size: Int, fraction: Double, random: Double): Int {
            require(size >= 0)
            require(fraction in 0.0..1.0)
            require(random in 0.0..1.0)
            val expected = size * fraction
            val whole = floor(expected).toInt()
            return (whole + if (random < expected - whole) 1 else 0).coerceAtMost(size)
        }

        internal fun isIdle(
            lastActivityMillis: Long,
            nowMillis: Long,
            minimumIdleMinutes: Long,
        ): Boolean {
            require(lastActivityMillis >= 0)
            require(nowMillis >= 0)
            require(minimumIdleMinutes > 0)
            if (nowMillis < lastActivityMillis) return false
            return nowMillis - lastActivityMillis >= Math.multiplyExact(minimumIdleMinutes, MILLIS_PER_MINUTE)
        }

        internal fun <T> preferEdges(
            candidates: List<T>,
            isEdge: (T) -> Boolean,
        ): List<T> = candidates.filter(isEdge).ifEmpty { candidates }

        private fun key(block: Block): DecayBlockKey =
            DecayBlockKey(block.world.uid, block.x, block.y, block.z)
    }
}

internal data class DecayCycleStats(
    val enabled: Boolean,
    val ranAtMillis: Long,
    val loadedChunks: Int,
    val sampledChunks: Int,
    val trackedBlocks: Int,
    val idleBlocks: Int,
    val candidateBlocks: Int,
    val nearbySkipped: Int,
    val changedBlocks: Int,
) {
    companion object {
        fun enabled(): DecayCycleStats = DecayCycleStats(true, 0L, 0, 0, 0, 0, 0, 0, 0)

        fun disabled(): DecayCycleStats = DecayCycleStats(false, 0L, 0, 0, 0, 0, 0, 0, 0)
    }
}

private data class DecayBlockKey(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)
