package ru.ruscrafting.trails.service

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class SpeedControllerTest :
    FreeSpec({
        "gradually boosts and restores the captured baseline" {
            val controller = SpeedController()
            val player = FakeSpeedTarget(walkSpeed = 0.3F)

            controller.target(player, multiplier = 1.5)
            controller.tick(mapOf(player.id to player), step = 0.05F)
            (kotlin.math.abs(player.walkSpeed - 0.35F) < 0.00001F) shouldBe true

            controller.restore(player)
            player.walkSpeed shouldBeExactly 0.3F
            controller.isActive(player.id) shouldBe false
        }

        "does not overwrite a speed changed by another plugin during restore" {
            val controller = SpeedController()
            val player = FakeSpeedTarget(walkSpeed = 0.2F)

            controller.target(player, multiplier = 1.5)
            controller.tick(mapOf(player.id to player), step = 0.1F)
            player.walkSpeed = 0.6F
            controller.restore(player)

            player.walkSpeed shouldBeExactly 0.6F
        }

        "adopts an external speed change as the new baseline" {
            val controller = SpeedController()
            val player = FakeSpeedTarget(walkSpeed = 0.2F)

            controller.target(player, multiplier = 1.5)
            controller.tick(mapOf(player.id to player), step = 0.1F)
            player.walkSpeed = 0.4F
            controller.tick(mapOf(player.id to player), step = 0.1F)

            player.walkSpeed shouldBeExactly 0.5F
            controller.restore(player)
            player.walkSpeed shouldBeExactly 0.4F
        }
    })

private data class FakeSpeedTarget(
    override val id: UUID = UUID.randomUUID(),
    override var walkSpeed: Float,
) : WalkSpeedTarget
