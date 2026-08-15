package ru.ruscrafting.trails.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class TrailDefinitionParserTest :
    FreeSpec({
        val materials = setOf("GRASS_BLOCK", "DIRT", "COARSE_DIRT", "DIRT_PATH")
        val parser = TrailDefinitionParser(materials::contains)

        "parses the legacy four-field trail syntax" {
            val definitions =
                parser.parse(
                    mapOf("DirtPath" to "GRASS_BLOCK:10:100:1.0 > DIRT:15:50:1.1 > DIRT_PATH:7:100:1.3"),
                )

            definitions shouldHaveSize 1
            definitions.single().stages.map { it.requiredWalks } shouldBe listOf(10, 15, 7)
            definitions.single().stages.map { it.speedMultiplier } shouldBe listOf(1.0, 1.1, 1.3)
        }

        "defaults an omitted speed multiplier to one" {
            parser.parse(mapOf("DirtPath" to "GRASS_BLOCK:5:100 > DIRT:5:100"))
                .single()
                .stages
                .first()
                .speedMultiplier shouldBe 1.0
        }

        "reports every invalid field instead of partially loading" {
            val error =
                shouldThrow<TrailDefinitionException> {
                    parser.parse(
                        mapOf(
                            "bad:name" to "STONE:0:101:8",
                            "DirtPath" to "UNKNOWN:-1:nope:-2",
                        ),
                    )
                }

            error.problems shouldHaveSize 6
            error.problems shouldContainAll
                listOf(
                    "Trail name 'bad:name' must be non-blank and must not contain ':'",
                    "Trail 'DirtPath' stage 1 uses unknown material 'UNKNOWN'",
                    "Trail 'DirtPath' stage 1 walks must be a positive integer",
                    "Trail 'DirtPath' stage 1 chance must be between 0 and 100",
                    "Trail 'DirtPath' stage 1 speed must be between 0 and 5",
                    "Trails must contain at least one valid definition",
                )
        }
    })
