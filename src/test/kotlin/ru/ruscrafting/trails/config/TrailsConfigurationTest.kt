package ru.ruscrafting.trails.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class TrailsConfigurationTest :
    FreeSpec({
        "an existing v3 installation starts on v4 with missing defaults merged once" {
            val folder = Files.createTempDirectory("trails-config-v3-")
            val runtime = MockBukkitTestRuntime.open()
            try {
                Files.writeString(
                    folder.resolve("config.yml"),
                    """
                    config-version: 3
                    locale: en-US
                    speed-boost:
                      adjustment-step: 0.02
                    operator-owned:
                      note: keep-me
                    """.trimIndent() + "\n",
                )
                val reports = mutableListOf<ConfigMigrationResult>()

                val first = TrailsConfiguration(folder, ensureBundledResource = {}, migrationReporter = reports::add)
                val snapshot = first.load(reload = false)

                snapshot.settings.configVersion shouldBe TrailsSettingsLoader.CONFIG_VERSION
                snapshot.settings.speedBoostStep shouldBe 0.02
                reports shouldHaveSize 1
                reports.single().addedDefaults.isNotEmpty() shouldBe true
                val merged = Files.readString(folder.resolve("config.yml"))
                merged shouldContain "config-version: 4"
                merged shouldContain "note: keep-me"
                merged shouldContain "stage-change-sound:"

                val secondReports = mutableListOf<ConfigMigrationResult>()
                TrailsConfiguration(folder, ensureBundledResource = {}, migrationReporter = secondReports::add)
                    .load(reload = false)
                secondReports shouldHaveSize 0
            } finally {
                runtime.close()
                folder.toFile().deleteRecursively()
            }
        }

        "an existing trails catalog receives new bundled definitions without losing operator values" {
            val folder = Files.createTempDirectory("trails-catalog-merge-")
            val runtime = MockBukkitTestRuntime.open()
            try {
                copyBundled(folder, "config.yml")
                copyBundled(folder, "roads.yml")
                Files.writeString(
                    folder.resolve("trails.yml"),
                    """
                    config-version: 1
                    trails:
                      DirtPath:
                        selection-weight: 7
                        operator-note: keep-me
                        stages:
                          - material: GRASS_BLOCK
                            required-walks: 37
                            count-chance-percent: 100
                            speed-multiplier: 1.0
                          - material: DIRT_PATH
                            speed-multiplier: 1.3
                    """.trimIndent() + "\n",
                )
                val reports = mutableListOf<ConfigMigrationResult>()

                val first = TrailsConfiguration(folder, ensureBundledResource = {}, migrationReporter = reports::add)
                val snapshot = first.load(reload = false)

                snapshot.settings.definitions.first { it.name == "DirtPath" }.stages.first().requiredWalks shouldBe 37
                snapshot.settings.definitions.any { it.name == "DesertPath" } shouldBe true
                snapshot.settings.definitions.any { it.name == "MushroomPath" } shouldBe true
                reports shouldHaveSize 1
                val mergedOnce = Files.readString(folder.resolve("trails.yml"))
                mergedOnce shouldContain "operator-note: keep-me"
                mergedOnce shouldContain "selection-weight: 7"
                mergedOnce shouldContain "DesertPath:"
                mergedOnce shouldContain "MushroomPath:"

                val secondReports = mutableListOf<ConfigMigrationResult>()
                TrailsConfiguration(folder, ensureBundledResource = {}, migrationReporter = secondReports::add)
                    .load(reload = false)
                secondReports shouldHaveSize 0
                Files.readString(folder.resolve("trails.yml")) shouldBe mergedOnce
            } finally {
                runtime.close()
                folder.toFile().deleteRecursively()
            }
        }

        "legacy roads retain v1 semantics instead of inheriting v2 profiles" {
            val folder = Files.createTempDirectory("trails-roads-v1-merge-")
            val runtime = MockBukkitTestRuntime.open()
            try {
                copyBundled(folder, "config.yml")
                copyBundled(folder, "trails.yml")
                val legacyRoads =
                    """
                    config-version: 1
                    replaceable-materials: [DIRT]
                    profiles:
                      legacy:
                        lanes: [DIRT]
                    """.trimIndent() + "\n"
                Files.writeString(folder.resolve("roads.yml"), legacyRoads)

                val snapshot = TrailsConfiguration(folder, ensureBundledResource = {}).load(reload = false)

                snapshot.roads.profiles.keys shouldBe setOf("legacy")
                Files.readString(folder.resolve("roads.yml")) shouldBe legacyRoads
            } finally {
                runtime.close()
                folder.toFile().deleteRecursively()
            }
        }
    })

private fun copyBundled(folder: java.nio.file.Path, resource: String) {
    val stream = checkNotNull(TrailsConfigurationTest::class.java.classLoader.getResourceAsStream(resource))
    stream.use { Files.copy(it, folder.resolve(resource)) }
}
