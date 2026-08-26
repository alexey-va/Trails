package ru.ruscrafting.trails.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class RoadMaterialPaletteTest :
    FreeSpec({
        "percentage boundaries select the configured material deterministically" {
            val palette =
                RoadMaterialPalette(
                    listOf(
                        WeightedRoadMaterial(Material.COBBLESTONE, 70),
                        WeightedRoadMaterial(Material.MOSSY_COBBLESTONE, 20),
                        WeightedRoadMaterial(Material.STONE_BRICKS, 10),
                    ),
                )

            palette.select(0) shouldBe Material.COBBLESTONE
            palette.select(69) shouldBe Material.COBBLESTONE
            palette.select(70) shouldBe Material.MOSSY_COBBLESTONE
            palette.select(89) shouldBe Material.MOSSY_COBBLESTONE
            palette.select(90) shouldBe Material.STONE_BRICKS
            palette.select(190) shouldBe Material.STONE_BRICKS
            palette.select(-1) shouldBe Material.STONE_BRICKS
        }

        "palette invariants reject duplicate or non-positive entries" {
            shouldThrow<IllegalArgumentException> {
                RoadMaterialPalette(
                    listOf(
                        WeightedRoadMaterial(Material.STONE, 50),
                        WeightedRoadMaterial(Material.STONE, 50),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                RoadMaterialPalette(
                    listOf(
                        WeightedRoadMaterial(Material.STONE, 101),
                        WeightedRoadMaterial(Material.DIRT, -1),
                    ),
                )
            }
        }
    })
