package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.mockbukkit.mockbukkit.MockBukkit
import ru.ruscrafting.trails.domain.RoadPoint

class RoadHeightTransitionFactoryTest :
    FreeSpec({
        beforeTest { MockBukkit.mock() }
        afterTest { MockBukkit.unmock() }

        "ascending stairs face the higher end of an eastbound road" {
            val data =
                RoadHeightTransitionFactory.create(
                    Material.COBBLESTONE_STAIRS,
                    RoadPoint(0, 0),
                    RoadPoint(1, 0),
                    ascending = true,
                ) as Stairs

            data.facing shouldBe BlockFace.EAST
            data.half shouldBe Bisected.Half.BOTTOM
            data.shape shouldBe Stairs.Shape.STRAIGHT
            data.isWaterlogged shouldBe false
        }

        "descending stairs face back toward the higher end" {
            val data =
                RoadHeightTransitionFactory.create(
                    Material.STONE_BRICK_STAIRS,
                    RoadPoint(0, 0),
                    RoadPoint(0, 1),
                    ascending = false,
                ) as Stairs

            data.facing shouldBe BlockFace.NORTH
        }

        "slab transitions are always dry bottom slabs" {
            val data =
                RoadHeightTransitionFactory.create(
                    Material.OAK_SLAB,
                    RoadPoint(0, 0),
                    RoadPoint(-1, 0),
                    ascending = true,
                ) as Slab

            data.type shouldBe Slab.Type.BOTTOM
            data.isWaterlogged shouldBe false
        }
    })
