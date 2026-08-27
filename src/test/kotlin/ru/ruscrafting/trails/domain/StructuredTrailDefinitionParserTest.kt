package ru.ruscrafting.trails.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class StructuredTrailDefinitionParserTest :
    FreeSpec({
        val materials = setOf("GRASS_BLOCK", "DIRT", "DIRT_PATH")
        val parser = StructuredTrailDefinitionParser(materials::contains)

        "parses weighted stages and supplies terminal progress defaults" {
            val definitions =
                parser.parse(
                    mapOf(
                        "DirtPath" to
                            mapOf(
                                "selection-weight" to 3,
                                "stages" to
                                    listOf(
                                        mapOf(
                                            "material" to "grass_block",
                                            "required-walks" to 10,
                                            "count-chance-percent" to 75,
                                            "speed-multiplier" to 1.0,
                                        ),
                                        mapOf("material" to "dirt_path", "speed-multiplier" to 1.3),
                                    ),
                            ),
                    ),
                ).single()

            definitions.selectionWeight shouldBe 3
            definitions.stages.first().material shouldBe "GRASS_BLOCK"
            definitions.stages.last().requiredWalks shouldBe 1
            definitions.stages.last().chancePercent shouldBe 100.0
        }

        "reports structured paths for every invalid stage field" {
            val error =
                shouldThrow<TrailDefinitionException> {
                    parser.parse(
                        mapOf(
                            "DirtPath" to
                                mapOf(
                                    "selection-weight" to 0,
                                    "stages" to
                                        listOf(
                                            mapOf(
                                                "material" to "UNKNOWN",
                                                "required-walks" to 0,
                                                "count-chance-percent" to 101,
                                                "speed-multiplier" to 7,
                                            ),
                                        ),
                                ),
                        ),
                    )
                }

            error.problems shouldContain "trails.DirtPath.selection-weight must be a positive integer"
            error.problems shouldContain "trails.DirtPath.stages[0].material uses unknown material 'UNKNOWN'"
            error.problems shouldContain "trails.DirtPath.stages[0].required-walks must be a positive integer when specified"
            error.problems shouldContain "trails.DirtPath.stages[0].count-chance-percent must be between 0 and 100"
            error.problems shouldContain "trails.DirtPath.stages[0].speed-multiplier must be between 0 and 5"
        }

        "rejects malformed explicit values instead of silently using defaults" {
            val error =
                shouldThrow<TrailDefinitionException> {
                    parser.parse(
                        mapOf(
                            "DirtPath" to
                                mapOf(
                                    "selection-weight" to "heavy",
                                    "stages" to
                                        listOf(
                                            mapOf(
                                                "material" to "DIRT_PATH",
                                                "required-walks" to "many",
                                                "count-chance-percent" to "often",
                                                "speed-multiplier" to "fast",
                                            ),
                                        ),
                                ),
                        ),
                    )
                }

            error.problems shouldContain "trails.DirtPath.selection-weight must be a positive integer"
            error.problems shouldContain "trails.DirtPath.stages[0].required-walks must be a positive integer when specified"
            error.problems shouldContain "trails.DirtPath.stages[0].count-chance-percent must be between 0 and 100"
            error.problems shouldContain "trails.DirtPath.stages[0].speed-multiplier must be between 0 and 5"
        }

        "normalizes optional world and biome conditions" {
            val definition =
                parser.parse(
                    mapOf(
                        "DirtPath" to
                            mapOf(
                                "conditions" to
                                    mapOf(
                                        "worlds" to listOf("Survival"),
                                        "biomes" to listOf("forest", "minecraft:taiga"),
                                    ),
                                "stages" to listOf(mapOf("material" to "DIRT_PATH")),
                            ),
                    ),
                ).single()

            definition.conditions.worlds shouldBe setOf("survival")
            definition.conditions.biomes shouldBe setOf("minecraft:forest", "minecraft:taiga")
        }

        "rejects malformed condition lists without dropping the definition silently" {
            val error =
                shouldThrow<TrailDefinitionException> {
                    parser.parse(
                        mapOf(
                            "DirtPath" to
                                mapOf(
                                    "conditions" to mapOf("biomes" to "forest"),
                                    "stages" to listOf(mapOf("material" to "DIRT_PATH")),
                                ),
                        ),
                    )
                }

            error.problems shouldContain "trails.DirtPath.conditions.biomes must be a list of non-blank strings"
        }
    })
