package ru.ruscrafting.trails.domain

sealed interface ProgressDecision {
    data object NoChange : ProgressDecision

    data class Counted(
        val stage: TrailStage,
        val walks: Int,
    ) : ProgressDecision

    data class Advanced(
        val from: TrailStage,
        val to: TrailStage,
    ) : ProgressDecision

    data class TerminalCounted(
        val stage: TrailStage,
        val walks: Int,
    ) : ProgressDecision

    data class Popular(
        val stage: TrailStage,
    ) : ProgressDecision
}

sealed interface DecayDecision {
    data object NoChange : DecayDecision

    data object Cleared : DecayDecision

    data class CountedDown(
        val stage: TrailStage,
        val walks: Int,
    ) : DecayDecision

    data class Regressed(
        val from: TrailStage,
        val to: TrailStage,
        val walks: Int,
    ) : DecayDecision
}

class TrailProgressEngine(
    private val catalog: TrailCatalog,
) {
    fun walk(
        stage: TrailStage,
        currentWalks: Int,
        randomPercent: Double,
        sprintModifier: Double,
        forced: Boolean = false,
        popularThreshold: Int? = null,
    ): ProgressDecision {
        require(currentWalks >= 0) { "currentWalks must be non-negative" }
        require(randomPercent in 0.0..100.0) { "randomPercent must be between 0 and 100" }
        require(sprintModifier >= 0.0) { "sprintModifier must be non-negative" }
        require(popularThreshold == null || popularThreshold > 0) { "popularThreshold must be positive when present" }
        val next = catalog.next(stage)
        if (next == null && (popularThreshold == null || forced)) return ProgressDecision.NoChange
        val effectiveChance = (stage.chancePercent * sprintModifier).coerceAtMost(100.0)
        if (!forced && (effectiveChance == 0.0 || randomPercent > effectiveChance)) return ProgressDecision.NoChange
        val walks = currentWalks.toLong() + 1
        if (next == null) {
            return if (walks >= popularThreshold!!) {
                ProgressDecision.Popular(stage)
            } else {
                ProgressDecision.TerminalCounted(stage, walks.toInt())
            }
        }
        return if (forced || walks >= stage.requiredWalks) {
            ProgressDecision.Advanced(stage, next)
        } else {
            ProgressDecision.Counted(stage, walks.toInt())
        }
    }

    fun decay(
        stage: TrailStage,
        currentWalks: Int,
        fraction: Double,
    ): DecayDecision {
        require(currentWalks >= 0) { "currentWalks must be non-negative" }
        require(fraction in 0.0..1.0) { "fraction must be between 0 and 1" }
        if (currentWalks == 0 && stage.index == 0) return DecayDecision.Cleared
        val decrement = maxOf(1, (currentWalks * fraction).toInt())
        val remaining = currentWalks - decrement
        if (remaining >= 0) {
            return if (remaining == 0 && stage.index == 0) {
                DecayDecision.Cleared
            } else {
                DecayDecision.CountedDown(stage, remaining)
            }
        }
        val previous = catalog.previous(stage) ?: return DecayDecision.Cleared
        return DecayDecision.Regressed(stage, previous, maxOf(0, previous.requiredWalks - 1))
    }
}
