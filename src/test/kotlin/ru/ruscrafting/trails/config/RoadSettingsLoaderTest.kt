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
                settings.profiles.getValue("rustic").lanes shouldBe
                    listOf(Material.COARSE_DIRT, Material.DIRT_PATH, Material.COARSE_DIRT)
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
                error.problems shouldContain "profiles.rustic.lanes uses unknown material 'NOT_A_BLOCK'"
                error.problems shouldContain "profiles.rustic.lanes must contain an odd number of materials between 1 and 7"
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

                settings.profiles.getValue("rustic").lanes shouldBe
                    listOf(Material.COBBLESTONE, Material.STONE_BRICKS, Material.COBBLESTONE)
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
    }
}
