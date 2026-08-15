package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.MockBukkit

class RoadPreviewSelectorTest :
    FreeSpec({
        beforeSpec { MockBukkit.mock() }
        afterSpec { MockBukkit.unmock() }

        "a full collision cube is safe to show as a fake block" {
            RoadPreviewSelector.isFullCube(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))) shouldBe true
        }

        "a path-height collision box requires the full-height preview fallback" {
            RoadPreviewSelector.isFullCube(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 15.0 / 16.0, 1.0))) shouldBe false
        }

        "several partial boxes do not masquerade as a full collision cube" {
            RoadPreviewSelector.isFullCube(
                listOf(
                    BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                    BoundingBox(0.0, 0.5, 0.0, 0.5, 1.0, 1.0),
                ),
            ) shouldBe false
        }

        "a non-full target is replaced with full-height yellow concrete" {
            val selection =
                RoadPreviewSelector { _, _ -> false }
                    .select(Material.DIRT_PATH.createBlockData(), Location(null, 0.0, 0.0, 0.0))

            selection.substituted shouldBe true
            selection.blockData.material shouldBe Material.YELLOW_CONCRETE
        }

        "a full-cube target remains visible as its real material" {
            val selection =
                RoadPreviewSelector { _, _ -> true }
                    .select(Material.COBBLESTONE.createBlockData(), Location(null, 0.0, 0.0, 0.0))

            selection.substituted shouldBe false
            selection.blockData.material shouldBe Material.COBBLESTONE
        }
    })
