package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.service.SpeedController
import ru.ruscrafting.trails.service.TrailService
import ru.ruscrafting.trails.service.WalkSpeedTarget
import ru.ruscrafting.trails.storage.PlayerPreferencesStore
import java.util.UUID

/** Owns every reloadable scheduler and walk-speed state used by Trails. */
internal class RuntimeTaskSupervisor(
    private val plugin: Plugin,
    private val preferences: PlayerPreferencesStore,
    private val speedController: SpeedController = SpeedController(),
    private val speedTarget: (Player) -> WalkSpeedTarget = ::BukkitWalkSpeedTarget,
) : AutoCloseable {
    private var tasks: RuntimeTasks? = null

    fun reconfigure(
        settings: TrailsSettings,
        trailService: TrailService,
        canDecay: (Block, Material) -> Boolean,
    ) {
        val replacement = schedule(settings, trailService, canDecay)
        restoreAllSpeeds()
        tasks?.close()
        tasks = replacement
    }

    fun targetSpeed(
        player: Player,
        multiplier: Double,
        immediate: Boolean,
    ) = speedController.target(speedTarget(player), multiplier, immediate)

    fun restoreSpeed(player: Player) = speedController.restore(speedTarget(player))

    override fun close() {
        restoreAllSpeeds()
        tasks?.close()
        tasks = null
    }

    private fun schedule(
        settings: TrailsSettings,
        trailService: TrailService,
        canDecay: (Block, Material) -> Boolean,
    ): RuntimeTasks {
        var speedTask: BukkitTask? = null
        var preferenceSaveTask: BukkitTask? = null
        var decayScheduler: DecayScheduler? = null
        try {
            speedTask =
                plugin.server.scheduler.runTaskTimer(
                    plugin,
                    Runnable {
                        speedController.tick(onlineSpeedTargets(), settings.speedBoostStep.toFloat())
                    },
                    0L,
                    settings.speedBoostInterval,
                )
            val saveIntervalTicks = Math.multiplyExact(settings.saveIntervalMinutes, TICKS_PER_MINUTE)
            preferenceSaveTask =
                plugin.server.scheduler.runTaskTimer(
                    plugin,
                    Runnable(preferences::saveAsync),
                    saveIntervalTicks,
                    saveIntervalTicks,
                )
            if (settings.trailDecay) {
                decayScheduler = DecayScheduler(plugin, settings, trailService, canDecay)
            }
            return RuntimeTasks(speedTask, preferenceSaveTask, decayScheduler)
        } catch (error: Exception) {
            speedTask?.cancel()
            preferenceSaveTask?.cancel()
            decayScheduler?.close()
            throw error
        }
    }

    private fun restoreAllSpeeds() = speedController.restoreAll(onlineSpeedTargets())

    private fun onlineSpeedTargets(): Map<UUID, WalkSpeedTarget> =
        plugin.server.onlinePlayers.associate { it.uniqueId to speedTarget(it) }

    private data class RuntimeTasks(
        val speed: BukkitTask,
        val preferences: BukkitTask,
        val decay: DecayScheduler?,
    ) : AutoCloseable {
        override fun close() {
            speed.cancel()
            preferences.cancel()
            decay?.close()
        }
    }

    private companion object {
        const val TICKS_PER_MINUTE = 60L * 20L
    }
}
