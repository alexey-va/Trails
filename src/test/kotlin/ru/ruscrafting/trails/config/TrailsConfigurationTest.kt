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
    })
