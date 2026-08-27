package ru.ruscrafting.trails.bukkit

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.ruscrafting.trails.config.LocaleService
import ru.ruscrafting.trails.config.TrailFeedbackSettings
import ru.ruscrafting.trails.service.TrailInspection
import ru.ruscrafting.trails.service.TrailWalkResult

internal class TrailFeedback(
    private val settings: TrailFeedbackSettings,
    private val locale: LocaleService,
) {
    fun onWalk(
        player: Player,
        block: Block,
        result: TrailWalkResult,
    ) {
        when (result) {
            is TrailWalkResult.Counted -> showMilestone(player, block, result)
            is TrailWalkResult.Advanced -> playAdvanceSound(player, block)
            TrailWalkResult.NoChange,
            is TrailWalkResult.PopularCounted,
            is TrailWalkResult.Widened,
            -> Unit
        }
    }

    fun inspect(
        player: Player,
        inspection: TrailInspection?,
    ) {
        val component =
            when {
                inspection == null -> locale.render("actionbar.trailEmpty")
                inspection.next == null ->
                    locale.render(
                        "actionbar.trailComplete",
                        mapOf("%trail%" to trailLabel(inspection.stage.trailName)),
                    )
                else ->
                    locale.render(
                        "actionbar.trailProgress",
                        replacements =
                            mapOf(
                                "%trail%" to trailLabel(inspection.stage.trailName),
                                "%walks%" to inspection.walks.toString(),
                                "%required%" to inspection.stage.requiredWalks.toString(),
                            ),
                        componentReplacements = mapOf("%bar%" to progressBar(inspection.walks, inspection.stage.requiredWalks)),
                    )
            }
        player.sendActionBar(component)
    }

    private fun showMilestone(
        player: Player,
        block: Block,
        counted: TrailWalkResult.Counted,
    ) {
        if (!settings.progressParticlesEnabled) return
        if (!crossedMilestone(counted.previousWalks, counted.walks, counted.stage.requiredWalks, settings.progressMilestonesPercent)) {
            return
        }
        player.spawnParticle(
            Particle.BLOCK,
            block.location.clone().add(0.5, 1.05, 0.5),
            settings.progressParticleCount,
            0.22,
            0.04,
            0.22,
            0.015,
            block.blockData,
        )
    }

    private fun playAdvanceSound(player: Player, block: Block) {
        if (!settings.stageSoundEnabled) return
        player.playSound(
            block.location.clone().add(0.5, 1.0, 0.5),
            Sound.BLOCK_GRAVEL_STEP,
            SoundCategory.BLOCKS,
            settings.stageSoundVolume,
            settings.stageSoundPitch,
        )
    }

    private fun trailLabel(id: String): String =
        "trailNames.$id".let { path -> if (locale.exists(path)) locale.plain(path) else id }

    private fun progressBar(
        walks: Int,
        required: Int,
    ): Component {
        val filled = ((walks.toDouble() / required) * BAR_SEGMENTS).toInt().coerceIn(0, BAR_SEGMENTS)
        return Component.text("■".repeat(filled), ACCENT)
            .append(Component.text("■".repeat(BAR_SEGMENTS - filled), MUTED))
    }

    companion object {
        private const val BAR_SEGMENTS = 10
        private val ACCENT = TextColor.color(0x92bed8)
        private val MUTED = TextColor.color(0x555555)

        internal fun crossedMilestone(
            previousWalks: Int,
            walks: Int,
            requiredWalks: Int,
            milestones: Set<Int>,
        ): Boolean {
            require(previousWalks >= 0 && walks >= previousWalks)
            require(requiredWalks > 0)
            val previousPercent = previousWalks * 100.0 / requiredWalks
            val currentPercent = walks * 100.0 / requiredWalks
            return milestones.any { it > previousPercent && it <= currentPercent }
        }
    }
}
