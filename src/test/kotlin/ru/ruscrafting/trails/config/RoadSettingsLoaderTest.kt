package ru.ruscrafting.trails.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files

class RoadSettingsLoaderTest :
    FreeSpec({
        "loads bounded opt-in road profiles" {
            val folder = Files.createTempDirectory("trails-roads-")
            try {
                write(folder, validRoads())

                val settings = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))

                settings.enabled shouldBe true
                settings.worldEnabled("arc_qa_flat") shouldBe true
                settings.worldEnabled("survival") shouldBe false
                settings.maxSegmentDistanceBlocks shouldBe 16
                settings.maxSegmentHeightDifferenceBlocks shouldBe 4
                settings.profiles.getValue("rustic").lanePalettes.map { it.select(0) } shouldBe
                    listOf(Material.COARSE_DIRT, Material.DIRT_PATH, Material.COARSE_DIRT)
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "legacy roads use v1 defaults and do not inherit bundled v2 profiles" {
            val folder = Files.createTempDirectory("trails-roads-v1-defaults-")
            try {
                write(
                    folder,
                    """
                    config-version: 1
                    replaceable-materials: [DIRT]
                    profiles:
                      legacy:
                        lanes: [DIRT]
                    """.trimIndent(),
                )

                val settings = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))

                settings.maxPlannedBlocks shouldBe 256
                settings.previewExpirySeconds shouldBe 300L
                settings.surfaceSearchDepth shouldBe 2
                settings.maxSegmentDistanceBlocks shouldBe 16
                settings.maxSegmentHeightDifferenceBlocks shouldBe 4
                settings.profiles.keys shouldBe setOf("legacy")
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects even widths, unsafe limits, and unknown materials together" {
            val folder = Files.createTempDirectory("trails-roads-invalid-")
            try {
                write(
                    folder,
                    validRoads()
                        .replace("max-planned-blocks: 128", "max-planned-blocks: 5000")
                        .replace("max-segment-distance-blocks: 16", "max-segment-distance-blocks: 100")
                        .replace("max-segment-height-difference-blocks: 4", "max-segment-height-difference-blocks: -1")
                        .replace("enabled: true", "enabled: maybe")
                        .replace("[COARSE_DIRT, DIRT_PATH, COARSE_DIRT]", "[DIRT, NOT_A_BLOCK]"),
                )

                val error = shouldThrow<TrailsSettingsException> { RoadSettingsLoader.load(YamlConfig(folder, "roads.yml")) }

                error.problems shouldContain "limits.max-planned-blocks must be between 1 and 1024"
                error.problems shouldContain "limits.max-segment-distance-blocks must be between 1 and 64"
                error.problems shouldContain "limits.max-segment-height-difference-blocks must be between 0 and 16"
                error.problems shouldContain "enabled must be a boolean"
                error.problems shouldContain "profiles.rustic.lanes[1] uses unknown material 'NOT_A_BLOCK'"
                error.problems shouldContain "profiles.rustic.lanes must contain an odd number of palettes between 1 and 7"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "accepts decorative road materials independently from natural trail stages" {
            val folder = Files.createTempDirectory("trails-roads-palette-")
            try {
                write(
                    folder,
                    validRoads().replace(
                        "[COARSE_DIRT, DIRT_PATH, COARSE_DIRT]",
                        "[COBBLESTONE, STONE_BRICKS, COBBLESTONE]",
                    ),
                )

                val settings = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))

                settings.profiles.getValue("rustic").lanePalettes.map { it.select(0) } shouldBe
                    listOf(Material.COBBLESTONE, Material.STONE_BRICKS, Material.COBBLESTONE)
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "loads safe-solid Roads v2 with larger previews, flight capture, drops, and profile steps" {
            val folder = Files.createTempDirectory("trails-roads-v2-")
            try {
                write(folder, validRoadsV2())

                val settings = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))

                settings.configVersion shouldBe 2
                settings.maxPlannedBlocks shouldBe 2048
                settings.surfaceSearchDepth shouldBe 8
                settings.maxSegmentDistanceBlocks shouldBe 48
                settings.captureWhileFlying shouldBe true
                settings.replacementMode shouldBe RoadReplacementMode.SAFE_SOLID
                settings.protectedMaterials shouldContain Material.DIAMOND_BLOCK
                settings.returnReplacedBlocksInSurvival shouldBe true
                settings.heightTransitionPalette(settings.profiles.getValue("rustic"))?.select(0) shouldBe Material.COBBLESTONE_STAIRS
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects unsafe road, transition, replacement, and resource-bound configuration" {
            val folder = Files.createTempDirectory("trails-roads-v2-invalid-")
            try {
                write(
                    folder,
                    validRoadsV2()
                        .replace("max-planned-blocks: 2048", "max-planned-blocks: 4097")
                        .replace("mode: safe-solid", "mode: everything")
                        .replace("default-material: OAK_STAIRS", "default-material: BEDROCK")
                        .replace("lanes: [COARSE_DIRT, DIRT_PATH, COARSE_DIRT]", "lanes: [DIRT, CHEST, DIRT]")
                        .replace("height-transition-material: COBBLESTONE_STAIRS", "height-transition-material: STONE"),
                )

                val error = shouldThrow<TrailsSettingsException> { RoadSettingsLoader.load(YamlConfig(folder, "roads.yml")) }

                error.problems shouldContain "limits.max-planned-blocks must be between 1 and 4096"
                error.problems shouldContain "replacement.mode must be 'allowlist' or 'safe-solid'"
                error.problems shouldContain "height-transitions.default-materials uses unsafe material 'BEDROCK'"
                error.problems shouldContain "profiles.rustic.lanes[1] uses unsafe material 'CHEST'"
                error.problems shouldContain "profiles.rustic.height-transition-materials uses unsafe material 'STONE'"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "loads weighted lanes, weighted steps, and a bounded periodic form" {
            val folder = Files.createTempDirectory("trails-roads-pattern-")
            try {
                write(folder, weightedPatternRoadsV2())

                val settings = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))
                val profile = settings.profiles.getValue("lantern_lane")
                val pattern = profile.decorationPatterns.single()

                profile.lanePalettes[0].materials shouldBe setOf(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE)
                profile.heightTransitionPalette?.materials shouldBe setOf(Material.STONE_BRICK_STAIRS, Material.OAK_SLAB)
                pattern.everyBlocks shouldBe 12
                pattern.alternateSides shouldBe true
                pattern.placements.map { Triple(it.forward, it.lateral, it.vertical) } shouldBe
                    listOf(Triple(0, 2, 1), Triple(0, 2, 2), Triple(0, 2, 3))
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects percentage totals and patterns that intersect the road lanes" {
            val folder = Files.createTempDirectory("trails-roads-pattern-invalid-")
            try {
                write(
                    folder,
                    weightedPatternRoadsV2()
                        .replace("COBBLESTONE: 70, MOSSY_COBBLESTONE: 30", "COBBLESTONE: 70, MOSSY_COBBLESTONE: 20")
                        .replace("lateral: 2", "lateral: 1"),
                )

                val error = shouldThrow<TrailsSettingsException> { RoadSettingsLoader.load(YamlConfig(folder, "roads.yml")) }

                error.problems shouldContain "profiles.lantern_lane.lanes[0] percentages must total 100"
                error.problems shouldContain "profiles.lantern_lane pattern 'lantern_posts' must place every element outside the road lanes"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    }) {
    companion object {
        private fun write(folder: java.nio.file.Path, content: String) {
            Files.writeString(folder.resolve("roads.yml"), content)
        }

        private fun validRoads(): String =
            """
            config-version: 1
            enabled: true
            worlds: [arc_qa_flat]
            limits:
              max-planned-blocks: 128
              preview-expiry-seconds: 60
              surface-search-depth: 2
              max-segment-distance-blocks: 16
              max-segment-height-difference-blocks: 4
            replaceable-materials: [GRASS_BLOCK, DIRT]
            profiles:
              rustic:
                lanes: [COARSE_DIRT, DIRT_PATH, COARSE_DIRT]
            """.trimIndent()

        private fun validRoadsV2(): String =
            """
            config-version: 2
            enabled: true
            worlds: [arc_qa_flat]
            limits:
              max-planned-blocks: 2048
              preview-expiry-seconds: 600
              surface-search-depth: 8
              max-segment-distance-blocks: 48
              max-segment-height-difference-blocks: 8
            movement:
              capture-while-flying: true
            replacement:
              mode: safe-solid
              protected-materials: [DIAMOND_BLOCK]
            removed-blocks:
              return-to-survival-inventory: true
            height-transitions:
              enabled: true
              default-material: OAK_STAIRS
            replaceable-materials: [GRASS_BLOCK, DIRT]
            profiles:
              rustic:
                lanes: [COARSE_DIRT, DIRT_PATH, COARSE_DIRT]
                height-transition-material: COBBLESTONE_STAIRS
            """.trimIndent()

        private fun weightedPatternRoadsV2(): String =
            """
            config-version: 2
            enabled: true
            worlds: [arc_qa_flat]
            limits:
              max-planned-blocks: 2048
              preview-expiry-seconds: 600
              surface-search-depth: 8
              max-segment-distance-blocks: 48
              max-segment-height-difference-blocks: 8
            movement:
              capture-while-flying: true
            replacement:
              mode: safe-solid
              protected-materials: []
            removed-blocks:
              return-to-survival-inventory: false
            height-transitions:
              enabled: true
              default-materials: {OAK_STAIRS: 100}
            replaceable-materials: [GRASS_BLOCK, DIRT]
            patterns:
              lantern_posts:
                every-blocks: 12
                alternate-sides: true
                placements:
                  - {forward: 0, lateral: 2, vertical: 1, materials: {COBBLESTONE_WALL: 70, MOSSY_COBBLESTONE_WALL: 30}}
                  - {forward: 0, lateral: 2, vertical: 2, material: OAK_FENCE}
                  - {forward: 0, lateral: 2, vertical: 3, material: LANTERN}
            profiles:
              lantern_lane:
                lanes:
                  - {COBBLESTONE: 70, MOSSY_COBBLESTONE: 30}
                  - {STONE_BRICKS: 80, CRACKED_STONE_BRICKS: 20}
                  - {COBBLESTONE: 70, MOSSY_COBBLESTONE: 30}
                height-transition-materials: {STONE_BRICK_STAIRS: 80, OAK_SLAB: 20}
                patterns: [lantern_posts]
            """.trimIndent()
    }
}
