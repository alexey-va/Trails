package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DecaySchedulerTest :
    FreeSpec({
        "fractional sampling can select zero instead of decaying every small chunk" {
            DecayScheduler.sampleSize(size = 1, fraction = 0.03, random = 0.5) shouldBe 0
            DecayScheduler.sampleSize(size = 1, fraction = 0.03, random = 0.01) shouldBe 1
        }

        "sampling never exceeds the chunk's tracked blocks" {
            DecayScheduler.sampleSize(size = 3, fraction = 1.0, random = 0.0) shouldBe 3
            DecayScheduler.sampleSize(size = 0, fraction = 1.0, random = 0.0) shouldBe 0
        }

        "idle decay waits for the full configured quiet period" {
            DecayScheduler.isIdle(1_000L, 60_999L, 1L) shouldBe false
            DecayScheduler.isIdle(1_000L, 61_000L, 1L) shouldBe true
            DecayScheduler.isIdle(61_000L, 1_000L, 1L) shouldBe false
        }

        "edge-first falls back to all idle blocks for a closed route" {
            DecayScheduler.preferEdges(listOf("a", "b")) { false } shouldBe listOf("a", "b")
            DecayScheduler.preferEdges(listOf("a", "b")) { it == "b" } shouldBe listOf("b")
        }
    })
