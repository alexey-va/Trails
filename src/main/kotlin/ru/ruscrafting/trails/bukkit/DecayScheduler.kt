package ru.ruscrafting.trails.bukkit

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.integration.DecayPolicy
import ru.ruscrafting.trails.service.TrailService
import kotlin.math.floor
import kotlin.random.Random

class DecayScheduler(
    private val plugin: Plugin,
    private val settings: TrailsSettings,
    private val trailService: TrailService,
    private val decayPolicy: DecayPolicy,
    private val random: Random = Random.Default,
) : AutoCloseable {
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
        plugin.server.worlds.filter { settings.worldEnabled(it.name) }.forEach { world ->
            world.loadedChunks.forEach chunkLoop@{ chunk ->
                if (random.nextDouble() > settings.chunkChance) return@chunkLoop
                val candidates = trailService.trackedBlocks(chunk).toList()
                val count = sampleSize(candidates.size, settings.decayFraction, random.nextDouble())
                candidates.shuffled(random).take(count).forEach { block ->
                    if (nearPlayer(block, playersByWorld[world].orEmpty())) return@forEach
                    if (decayPolicy.canDecay(block.location)) trailService.decay(block, settings.stepDecayFraction)
                }
            }
        }
    }

    private fun nearPlayer(block: Block, players: Collection<Player>): Boolean {
        val maximum = settings.decayDistance * settings.decayDistance
        return players.any { it.location.distanceSquared(block.location) < maximum }
    }

    companion object {
        internal fun sampleSize(size: Int, fraction: Double, random: Double): Int {
            require(size >= 0)
            require(fraction in 0.0..1.0)
            require(random in 0.0..1.0)
            val expected = size * fraction
            val whole = floor(expected).toInt()
            return (whole + if (random < expected - whole) 1 else 0).coerceAtMost(size)
        }
    }
}
