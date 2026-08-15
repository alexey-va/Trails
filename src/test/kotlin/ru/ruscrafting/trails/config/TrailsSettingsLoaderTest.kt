package ru.ruscrafting.trails.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class TrailsSettingsLoaderTest :
    FreeSpec({
        "loads the v2 split configuration without mixing integration settings" {
            val folder = Files.createTempDirectory("trails-settings-")
            try {
                writeValidConfiguration(folder)
                Files.writeString(
                    folder.resolve("config.yml"),
                    Files.readString(folder.resolve("config.yml"))
                        .replace("    log-block-changes: true # LogBlock", "    log-block-changes: false # LogBlock")
                        .replace("    enabled: true # RedProtect", "    enabled: false # RedProtect"),
                )

                val settings = load(folder)

                settings.integrations.logBlockChanges shouldBe false
                settings.integrations.coreProtectChanges shouldBe true
                settings.integrations.redProtectEnabled shouldBe false
                settings.worldMode shouldBe WorldMode.ALLOWLIST
                settings.worldEnabled("survival") shouldBe true
                settings.worldEnabled("world") shouldBe false
                settings.decayFraction shouldBe 0.03
                settings.definitions.single().stages.last().requiredWalks shouldBe 1
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects unsafe ranges in both files as one validation failure" {
            val folder = Files.createTempDirectory("trails-settings-")
            try {
                writeValidConfiguration(folder)
                Files.writeString(
                    folder.resolve("config.yml"),
                    Files.readString(folder.resolve("config.yml"))
                        .replace("  update-interval-ticks: 1", "  update-interval-ticks: 0")
                        .replace("  interval-ticks: 1200", "  interval-ticks: 1200.5")
                        .replace("  blocks-per-chunk-percent: 3", "  blocks-per-chunk-percent: 200"),
                )
                Files.writeString(
                    folder.resolve("trails.yml"),
                    Files.readString(folder.resolve("trails.yml")).replace("count-chance-percent: 100", "count-chance-percent: 101"),
                )

                val error = shouldThrow<TrailsSettingsException> { load(folder) }

                error.problems shouldContain "speed-boost.update-interval-ticks must be positive"
                error.problems shouldContain "decay.interval-ticks must be an integer"
                error.problems shouldContain "decay.blocks-per-chunk-percent must be between 0.0 and 100.0"
                error.problems shouldContain "trails.DirtPath.stages[0].count-chance-percent must be between 0 and 100"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "aggregates unknown Bukkit enum values" {
            val folder = Files.createTempDirectory("trails-settings-")
            try {
                writeValidConfiguration(folder)
                Files.writeString(
                    folder.resolve("config.yml"),
                    Files.readString(folder.resolve("config.yml"))
                        .replace("  advance: IRON_SHOVEL", "  advance: NOT_A_TOOL")
                        .replace("  visualization-particle: NAUTILUS", "  visualization-particle: NOT_A_PARTICLE"),
                )

                val error = shouldThrow<TrailsSettingsException> { load(folder) }

                error.problems shouldContain "tools.advance uses unknown material 'NOT_A_TOOL'"
                error.problems shouldContain "trail-creation.visualization-particle uses unknown particle 'NOT_A_PARTICLE'"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects air as a configured issued tool" {
            val folder = Files.createTempDirectory("trails-settings-")
            try {
                writeValidConfiguration(folder)
                Files.writeString(
                    folder.resolve("config.yml"),
                    Files.readString(folder.resolve("config.yml")).replace("  inspect: STICK", "  inspect: AIR"),
                )

                val error = shouldThrow<TrailsSettingsException> { load(folder) }

                error.problems shouldContain "tools.inspect must be an inventory item material"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects malformed numbers and booleans instead of silently using defaults" {
            val folder = Files.createTempDirectory("trails-settings-")
            try {
                writeValidConfiguration(folder)
                Files.writeString(
                    folder.resolve("config.yml"),
                    Files.readString(folder.resolve("config.yml"))
                        .replace("  sprint-progress-multiplier: 1.5", "  sprint-progress-multiplier: fast")
                        .replace("  trails-enabled: true", "  trails-enabled: sometimes"),
                )

                val error = shouldThrow<TrailsSettingsException> { load(folder) }

                error.problems shouldContain "trail-creation.sprint-progress-multiplier must be a number"
                error.problems shouldContain "player-defaults.trails-enabled must be a boolean"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    }) {
    companion object {
        private val materials = setOf("AIR", "GRASS_BLOCK", "DIRT", "DIRT_PATH", "IRON_SHOVEL", "STICK")

        private fun load(folder: java.nio.file.Path): TrailsSettings =
            TrailsSettingsLoader.load(
                config = YamlConfig(folder, "config.yml"),
                trails = YamlConfig(folder, "trails.yml"),
                materialExists = materials::contains,
                particleExists = { it == "NAUTILUS" },
            )

        private fun writeValidConfiguration(folder: java.nio.file.Path) {
            Files.writeString(
                folder.resolve("config.yml"),
                """
                config-version: 2
                locale: ru-RU
                commands:
                  localized-alias: footpaths
                player-defaults:
                  trails-enabled: true
                  speed-boost-enabled: true
                worlds:
                  mode: allowlist
                  names: [survival]
                trail-creation:
                  while-sneaking: false
                  sprint-progress-multiplier: 1.5
                  require-permission: false
                  strict-stage-order: false
                  visualization-particle: NAUTILUS
                speed-boost:
                  require-permission: false
                  only-created-trails: false
                  update-interval-ticks: 1
                  adjustment-step: 0.006
                  remove-immediately-off-trail: false
                tools:
                  advance: IRON_SHOVEL
                  inspect: STICK
                decay:
                  enabled: true
                  interval-ticks: 1200
                  chunk-selection-chance-percent: 20
                  blocks-per-chunk-percent: 3
                  minimum-player-distance-blocks: 5
                  step-counter-reduction-percent: 10
                messages:
                  protection-denied:
                    enabled: false
                    cooldown-seconds: 10
                storage:
                  player-preferences-save-interval-minutes: 5
                integrations:
                  towny:
                    enabled: true
                    allow-in-wilderness: true
                    permission-mode: false
                  lands:
                    enabled: true
                    allow-in-wilderness: true
                    apply-flag-to-subareas: true
                    flag-icon-material: DIRT_PATH
                  griefprevention:
                    enabled: true
                    allow-in-wilderness: true
                  worldguard:
                    enabled: true
                    allow-bypass: false
                    register-decay-flag: false
                  logblock:
                    log-block-changes: true # LogBlock
                  coreprotect:
                    log-block-changes: true
                  playerplot:
                    enabled: true
                  redprotect:
                    enabled: true # RedProtect
                  residence:
                    enabled: true
                  dynmap:
                    trigger-render: true
                """.trimIndent(),
            )
            Files.writeString(
                folder.resolve("trails.yml"),
                """
                config-version: 1
                trails:
                  DirtPath:
                    selection-weight: 2
                    stages:
                      - material: GRASS_BLOCK
                        required-walks: 10
                        count-chance-percent: 100
                        speed-multiplier: 1.0
                      - material: DIRT
                        speed-multiplier: 1.1
                """.trimIndent(),
            )
        }
    }
}
