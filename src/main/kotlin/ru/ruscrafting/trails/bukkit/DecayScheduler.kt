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

    private fun runDecay() {
        val playersByWorld = plugin.server.onlinePlayers.groupBy(Player::getWorld)
        val now = currentTimeMillis()
        val activeKeys = mutableSetOf<DecayBlockKey>()
        plugin.server.worlds.filter { settings.worldEnabled(it.name) }.forEach { world ->
            world.loadedChunks.forEach chunkLoop@{ chunk ->
                val tracked = trailService.trackedBlocks(chunk).toList()
                tracked.mapTo(activeKeys, ::key)
                if (random.nextDouble() > settings.chunkChance) return@chunkLoop
                val idle =
                    tracked.filter { block ->
                        val blockKey = key(block)
                        val activity = trailService.lastActivityMillis(block) ?: firstObserved.getOrPut(blockKey) { now }
                        isIdle(activity, now, settings.decayMinimumIdleMinutes)
                    }
                val candidates = if (settings.decayEdgeFirst) preferEdges(idle, trailService::isDecayEdge) else idle
                val count = sampleSize(candidates.size, settings.decayFraction, random.nextDouble())
                candidates.shuffled(random).take(count).forEach { block ->
                    if (nearPlayer(block, playersByWorld[world].orEmpty())) return@forEach
                    trailService.decay(block, settings.stepDecayFraction) { target -> canChange(block, target) }
                }
            }
        }
        firstObserved.keys.retainAll(activeKeys)
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

private data class DecayBlockKey(
    val world: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
)
