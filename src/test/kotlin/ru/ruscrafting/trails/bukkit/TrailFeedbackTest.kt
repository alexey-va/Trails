package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TrailFeedbackTest :
    FreeSpec({
        "milestones fire once when a short stage crosses their percentage" {
            val milestones = setOf(25, 50, 75)

            TrailFeedback.crossedMilestone(0, 1, 5, milestones) shouldBe false
            TrailFeedback.crossedMilestone(1, 2, 5, milestones) shouldBe true
            TrailFeedback.crossedMilestone(2, 3, 5, milestones) shouldBe true
            TrailFeedback.crossedMilestone(3, 4, 5, milestones) shouldBe true
        }

        "ordinary progress does not emit particles between milestones" {
            TrailFeedback.crossedMilestone(5, 6, 20, setOf(25, 50, 75)) shouldBe false
        }
    })
