package ru.ruscrafting.trails.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LegacyConfigMigratorTest :
    FreeSpec({
        "migrates v1 transactionally while preserving player data and persisted trail ids" {
            val folder = Files.createTempDirectory("trails-migration-")
            try {
                val legacy = legacyConfig()
                Files.writeString(folder.resolve("config.yml"), legacy)
                Files.writeString(folder.resolve("players.yml"), "players:\n  00000000-0000-0000-0000-000000000001:\n    enabled: false\n")
                val lang = Files.createDirectories(folder.resolve("lang"))
                Files.writeString(
                    lang.resolve("ru-RU.yml"),
                    "command-name: footpaths\nplugin-prefix: '&eТропы'\nlands:\n  flag:\n    icon-material: DIRT_PATH\n",
                )
                val playerDataBefore = Files.readString(folder.resolve("players.yml"))

                val result = migrator(folder).migrateIfNeeded(YamlConfig(folder, "config.yml"))

                result.migrated shouldBe true
                Files.readString(folder.resolve("config.v1.backup.yml")) shouldBe legacy
                Files.readString(folder.resolve("players.yml")) shouldBe playerDataBefore
                val migrated = loadV2(folder)
                migrated.configVersion shouldBe 2
                migrated.trailsConfigVersion shouldBe 1
                migrated.commandAlias shouldBe "footpaths"
                migrated.worldMode shouldBe WorldMode.ALLOWLIST
                migrated.enabledWorlds shouldBe setOf("survival")
                migrated.decayFraction shouldBe 0.03
                migrated.chunkChance shouldBe 0.2
                migrated.definitions.single().name shouldBe "DirtPath"
                migrated.definitions.single().stages.last().speedMultiplier shouldBe 1.1

                migrator(folder).migrateIfNeeded(YamlConfig(folder, "config.yml")).migrated shouldBe false
                Files.readString(folder.resolve("config.v1.backup.yml")) shouldBe legacy
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "invalid legacy input is rejected before backups or v2 files are written" {
            val folder = Files.createTempDirectory("trails-migration-invalid-")
            try {
                val invalid = legacyConfig().replace("speed-boost-interval: 1", "speed-boost-interval: 0")
                Files.writeString(folder.resolve("config.yml"), invalid)

                shouldThrow<TrailsSettingsException> {
                    migrator(folder).migrateIfNeeded(YamlConfig(folder, "config.yml"))
                }

                Files.readString(folder.resolve("config.yml")) shouldBe invalid
                Files.exists(folder.resolve("config.v1.backup.yml")) shouldBe false
                Files.exists(folder.resolve("trails.yml")) shouldBe false
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "fractional legacy scheduling values are rejected instead of truncated" {
            val folder = Files.createTempDirectory("trails-migration-invalid-integer-")
            try {
                val invalid = legacyConfig().replace("speed-boost-interval: 1", "speed-boost-interval: 1.5")
                Files.writeString(folder.resolve("config.yml"), invalid)

                val error = shouldThrow<TrailsSettingsException> {
                    migrator(folder).migrateIfNeeded(YamlConfig(folder, "config.yml"))
                }

                error.problems shouldBe listOf("General.speed-boost-interval must be an integer")
                Files.readString(folder.resolve("config.yml")) shouldBe invalid
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "legacy trail ids containing dots survive migration exactly" {
            val folder = Files.createTempDirectory("trails-migration-id-")
            try {
                Files.writeString(folder.resolve("config.yml"), legacyConfig().replace("DirtPath:", "Dirt.Path:"))

                migrator(folder).migrateIfNeeded(YamlConfig(folder, "config.yml"))

                loadV2(folder).definitions.single().name shouldBe "Dirt.Path"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    }) {
    companion object {
        private val materials = setOf("GRASS_BLOCK", "DIRT", "DIRT_PATH", "IRON_SHOVEL", "STICK")

        private fun migrator(folder: java.nio.file.Path) =
            LegacyConfigMigrator(
                dataFolder = folder,
                materialExists = materials::contains,
                particleExists = { it == "NAUTILUS" },
            )

        private fun loadV2(folder: java.nio.file.Path): TrailsSettings =
            TrailsSettingsLoader.load(
                YamlConfig(folder, "config.yml"),
                YamlConfig(folder, "trails.yml"),
                materials::contains,
                { it == "NAUTILUS" },
            )

        private fun legacyConfig(): String =
            """
            General:
              Language: ru-RU
              enabled-by-default: true
              boost-enabled-by-default: true
              run-modifier: 1.5
              sneak-bypass: true
              speed-boost-interval: 1
              speed-boost-step: 0.006
              speed-boost-only-trails: false
              use-permission-for-trails: false
              use-permission-for-boost: false
              immediately-remove-boost: false
              trail-tool: IRON_SHOVEL
              info-tool: STICK
              trail-decay: true
              decay-fraction: 0.03
              chunk-chance: 0.2
              decay-timer: 1200
              decay-distance: 5
              step-decay-fraction: 0.1
              strict-links: false
              trails-particle: NAUTILUS
              enabled-worlds: [survival]
            Messages:
              SendDenyMessage: false
              Interval: 10
            Data-Saving:
              Interval: 5
            Plugin-Integration:
              Lands:
                PathsInWilderness: true
                ApplyInSubAreas: true
              WorldGuard:
                IntegrationEnabled: true
                CheckBypass: false
                decay-flag: false
              LogBlock:
                LogPathBlocks: true
              CoreProtect:
                LogPathBlocks: true
              RedProtect:
                integration-enabled: true
            Trails:
              DirtPath: 'GRASS_BLOCK:10:100:1.0 > DIRT:15:100:1.1'
            """.trimIndent() + "\n"
    }
}
