package ru.ruscrafting.trails.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player

class ProtectionPolicyTest :
    FreeSpec({
        "composite protection denies when any installed policy denies" {
            val player = mockk<Player>()
            val location = mockk<Location>()
            var checks = 0
            val policy =
                ProtectionPolicy.composite(
                    listOf(
                        ProtectionPolicy { _, _ -> checks++; true },
                        ProtectionPolicy { _, _ -> checks++; false },
                        ProtectionPolicy { _, _ -> checks++; true },
                    ),
                )

            policy.canCreate(player, location) shouldBe false
            checks shouldBe 2
        }
    })
