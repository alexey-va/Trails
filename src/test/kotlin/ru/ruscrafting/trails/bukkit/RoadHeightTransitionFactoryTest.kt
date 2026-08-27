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

        "stairs ascend toward the higher road block in every cardinal direction" {
            listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST).forEach { highSide ->
                val data =
                    RoadHeightTransitionFactory.create(
                        Material.COBBLESTONE_STAIRS,
                        highSide,
                    ) as Stairs

                data.facing shouldBe highSide
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

        "row heading wins when an inside lane moves sideways at a turn" {
            RoadHeightTransitionFactory.transitionTravelFace(
                from = RoadPoint(10, 10),
                to = RoadPoint(11, 10),
                headingFrom = RoadPoint(10, 10),
                headingTo = RoadPoint(10, 20),
            ) shouldBe BlockFace.SOUTH
        }

        "every lane in a transition row shares one road heading" {
            val laneSteps =
                listOf(
                    RoadPoint(9, 9) to RoadPoint(10, 9),
                    RoadPoint(10, 9) to RoadPoint(11, 10),
                    RoadPoint(11, 9) to RoadPoint(11, 10),
                    RoadPoint(12, 9) to RoadPoint(11, 10),
                    RoadPoint(13, 9) to RoadPoint(12, 9),
                )

            laneSteps.forEach { (from, to) ->
                RoadHeightTransitionFactory.transitionTravelFace(
                    from = from,
                    to = to,
                    headingFrom = RoadPoint(10, 10),
                    headingTo = RoadPoint(10, 20),
                ) shouldBe BlockFace.SOUTH
            }
        }
    })
