package ru.ruscrafting.trails.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TrailProgressEngineTest :
    FreeSpec({
        val grass = TrailStage("DirtPath", 0, "GRASS_BLOCK", 3, 50.0, 1.0)
        val dirt = TrailStage("DirtPath", 1, "DIRT", 4, 100.0, 1.1)
        val path = TrailStage("DirtPath", 2, "DIRT_PATH", 5, 100.0, 1.3)
        val catalog = TrailCatalog(listOf(TrailDefinition("DirtPath", listOf(grass, dirt, path))), false)
        val engine = TrailProgressEngine(catalog)

        "counts the exact configured number of successful walks" {
            engine.walk(grass, 0, 50.0, 1.0) shouldBe ProgressDecision.Counted(grass, 1)
            engine.walk(grass, 1, 50.0, 1.0) shouldBe ProgressDecision.Counted(grass, 2)
            engine.walk(grass, 2, 50.0, 1.0) shouldBe ProgressDecision.Advanced(grass, dirt)
        }

        "rejects a roll above chance and accepts the boundary" {
            engine.walk(grass, 0, 50.01, 1.0) shouldBe ProgressDecision.NoChange
            engine.walk(grass, 0, 50.0, 1.0) shouldBe ProgressDecision.Counted(grass, 1)
        }

        "caps the sprint-adjusted chance at one hundred" {
            engine.walk(grass, 0, 100.0, 3.0) shouldBe ProgressDecision.Counted(grass, 1)
        }

        "zero chance never counts even when the random roll is zero" {
            engine.walk(grass, 0, 0.0, 0.0) shouldBe ProgressDecision.NoChange
            engine.walk(grass.copy(chancePercent = 0.0), 0, 0.0, 1.0) shouldBe ProgressDecision.NoChange
        }

        "maximum persisted counters advance without overflowing" {
            engine.walk(grass, Int.MAX_VALUE, 0.0, 1.0) shouldBe ProgressDecision.Advanced(grass, dirt)
            engine.walk(path, Int.MAX_VALUE, 0.0, 1.0, popularThreshold = Int.MAX_VALUE) shouldBe
                ProgressDecision.Popular(path)
        }

        "manual advance transitions immediately" {
            engine.walk(grass, 0, 100.0, 0.0, forced = true) shouldBe ProgressDecision.Advanced(grass, dirt)
        }

        "does not accumulate useless counters on the terminal stage" {
            engine.walk(path, 100, 0.0, 1.0) shouldBe ProgressDecision.NoChange
        }

        "terminal traffic reaches a bounded popularity threshold when widening is enabled" {
            engine.walk(path, 0, 0.0, 1.0, popularThreshold = 3) shouldBe ProgressDecision.TerminalCounted(path, 1)
            engine.walk(path, 2, 0.0, 1.0, popularThreshold = 3) shouldBe ProgressDecision.Popular(path)
        }

        "decay clears the initial stage at zero" {
            engine.decay(grass, 1, 0.1) shouldBe DecayDecision.Cleared
        }

        "decay regresses a depleted later stage" {
            engine.decay(dirt, 0, 0.1) shouldBe DecayDecision.Regressed(dirt, grass, 2)
        }

        "decay subtracts the configured fraction with a minimum of one" {
            engine.decay(dirt, 20, 0.25) shouldBe DecayDecision.CountedDown(dirt, 15)
            engine.decay(dirt, 2, 0.1) shouldBe DecayDecision.CountedDown(dirt, 1)
        }
    })
