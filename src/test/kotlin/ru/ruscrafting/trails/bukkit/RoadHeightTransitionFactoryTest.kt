package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import ru.arc.paper.testing.MockBukkitTestRuntime

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
    })
