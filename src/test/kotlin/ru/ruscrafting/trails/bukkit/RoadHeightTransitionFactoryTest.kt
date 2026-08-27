package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.domain.RoadPoint

class RoadHeightTransitionFactoryTest :
    FreeSpec({
        lateinit var runtime: MockBukkitTestRuntime

        beforeTest { runtime = MockBukkitTestRuntime.open() }
        afterTest { runtime.close() }

        "stairs keep their full-height side against the higher road block in every cardinal direction" {
            listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST).forEach { highSide ->
                val data =
                    RoadHeightTransitionFactory.create(
                        Material.COBBLESTONE_STAIRS,
                        highSide,
                    ) as Stairs

                data.facing.oppositeFace shouldBe highSide
                data.half shouldBe Bisected.Half.BOTTOM
                data.shape shouldBe Stairs.Shape.STRAIGHT
                data.isWaterlogged shouldBe false
            }
        }

        "slab transitions are always dry bottom slabs" {
            val data =
                RoadHeightTransitionFactory.create(
                    Material.OAK_SLAB,
                    BlockFace.WEST,
                ) as Slab

            data.type shouldBe Slab.Type.BOTTOM
            data.isWaterlogged shouldBe false
        }

        "diagonal raster steps keep the dominant road heading instead of turning sideways" {
            RoadHeightTransitionFactory.transitionTravelFace(
                from = RoadPoint(10, 10),
                to = RoadPoint(11, 11),
                headingFrom = RoadPoint(0, 0),
                headingTo = RoadPoint(8, 3),
            ) shouldBe BlockFace.EAST

            RoadHeightTransitionFactory.transitionTravelFace(
                from = RoadPoint(10, 10),
                to = RoadPoint(11, 11),
                headingFrom = RoadPoint(0, 0),
                headingTo = RoadPoint(3, 8),
            ) shouldBe BlockFace.SOUTH

            RoadHeightTransitionFactory.transitionTravelFace(
                from = RoadPoint(11, 11),
                to = RoadPoint(10, 10),
                headingFrom = RoadPoint(8, 3),
                headingTo = RoadPoint(0, 0),
            ) shouldBe BlockFace.WEST
        }

        "cardinal lane movement wins even at a turn" {
            RoadHeightTransitionFactory.transitionTravelFace(
                from = RoadPoint(10, 10),
                to = RoadPoint(11, 10),
                headingFrom = RoadPoint(10, 10),
                headingTo = RoadPoint(10, 20),
            ) shouldBe BlockFace.EAST
        }
    })
