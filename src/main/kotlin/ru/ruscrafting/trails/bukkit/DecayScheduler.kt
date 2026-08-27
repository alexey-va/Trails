package ru.ruscrafting.trails.bukkit

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.service.TrailService
import kotlin.math.floor
import kotlin.random.Random
import java.util.UUID

class DecayScheduler(
    private val plugin: Plugin,
    private val settings: TrailsSettings,
    private val trailService: TrailService,
    private val canChange: (Block, Material) -> Boolean = { _, _ -> true },
    private val random: Random = Random.Default,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val firstObserved = mutableMapOf<DecayBlockKey, Long>()
    private var latestStats = DecayCycleStats.enabled()
    private val task: BukkitTask =
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable(::runDecay),
            settings.decayTimer,
            settings.decayTimer,
        )

    override fun close() {
        task.cancel()
    }

    internal fun snapshot(): DecayCycleStats = latestStats

    private fun runDecay() {
        val playersByWorld = plugin.server.onlinePlayers.groupBy(Player::getWorld)
        val now = currentTimeMillis()
        val activeKeys = mutableSetOf<DecayBlockKey>()
        var loadedChunks = 0
        var sampledChunks = 0
        var trackedBlocks = 0
        var idleBlocks = 0
        var candidateBlocks = 0
        var nearbySkipped = 0
        var changedBlocks = 0
        plugin.server.worlds.filter { settings.worldEnabled(it.name) }.forEach { world ->
            world.loadedChunks.forEach chunkLoop@{ chunk ->
                loadedChunks++
                val tracked = trailService.trackedBlocks(chunk).toList()
                trackedBlocks += tracked.size
                tracked.mapTo(activeKeys, ::key)
                if (random.nextDouble() > settings.chunkChance) return@chunkLoop
                sampledChunks++
                val idle =
                    tracked.filter { block ->
                        val blockKey = key(block)
                        val activity = trailService.lastActivityMillis(block) ?: firstObserved.getOrPut(blockKey) { now }
                        isIdle(activity, now, settings.decayMinimumIdleMinutes)
                    }
                idleBlocks += idle.size
                val candidates = if (settings.decayEdgeFirst) preferEdges(idle, trailService::isDecayEdge) else idle
                candidateBlocks += candidates.size
                val count = sampleSize(candidates.size, settings.decayFraction, random.nextDouble())
                candidates.shuffled(random).take(count).forEach { block ->
                    if (nearPlayer(block, playersByWorld[world].orEmpty())) {
                        nearbySkipped++
                        return@forEach
                    }
                    if (trailService.decay(block, settings.stepDecayFraction) { target -> canChange(block, target) }) {
                        changedBlocks++
                    }
                }
            }
        }
        firstObserved.keys.retainAll(activeKeys)
        latestStats =
            DecayCycleStats(
                enabled = true,
                ranAtMillis = now,
                loadedChunks = loadedChunks,
                sampledChunks = sampledChunks,
                trackedBlocks = trackedBlocks,
                idleBlocks = idleBlocks,
                candidateBlocks = candidateBlocks,
                nearbySkipped = nearbySkipped,
                changedBlocks = changedBlocks,
            )
    }

    private fun nearPlayer(block: Block, players: Collection<Player>): Boolean {
        val maximum = settings.decayDistance * settings.decayDistance
        return players.any { it.location.distanceSquared(block.location) < maximum }
    }

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
