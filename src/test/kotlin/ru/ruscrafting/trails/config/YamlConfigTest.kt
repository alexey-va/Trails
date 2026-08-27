package ru.ruscrafting.trails.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class YamlConfigTest :
    FreeSpec({
        "reads legacy scalar coercions and nested maps" {
            val folder = Files.createTempDirectory("trails-yaml-")
            try {
                Files.writeString(
                    folder.resolve("values.yml"),
                    """
                    enabled: yes
                    interval: '5'
                    fraction: 0.25
                    players:
                      first:
                        enable: false
                    """.trimIndent(),
                )
                val config = YamlConfig(folder, "values.yml")

                config.boolean("enabled") shouldBe true
                config.long("interval") shouldBe 5L
                config.double("fraction") shouldBe 0.25
                (config.map<Any>("players")["first"] as Map<*, *>)["enable"] shouldBe false
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "atomically saves YAML-safe structured values" {
            val folder = Files.createTempDirectory("trails-yaml-")
            try {
                Files.writeString(folder.resolve("values.yml"), "players: {}\n")
                val config = YamlConfig(folder, "values.yml")
                config.setStructured("players", mapOf("first" to mapOf("enable" to true)))
                config.saveStrict()

                val reloaded = YamlConfig(folder, "values.yml")
                (reloaded.map<Any>("players")["first"] as Map<*, *>)["enable"] shouldBe true
                Files.list(folder).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } shouldBe true }
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "rejects object serialization that could emit Bukkit type tags" {
            val folder = Files.createTempDirectory("trails-yaml-")
            try {
                Files.writeString(folder.resolve("values.yml"), "root: {}\n")
                val config = YamlConfig(folder, "values.yml")

                shouldThrow<IllegalArgumentException> {
                    config.setStructured("root", mapOf("unsafe" to Any()))
                }
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "merge-forward copies missing bundled keys and preserves operator values" {
            val folder = Files.createTempDirectory("trails-yaml-merge-")
            try {
                Files.writeString(
                    folder.resolve("config.yml"),
                    """
                    config-version: 3
                    locale: ru-RU
                    speed-boost:
                      adjustment-step: 0.02
                    operator-owned:
                      note: keep-me
                    """.trimIndent() + "\n",
                )
                val config = YamlConfig(folder, "config.yml")

                val added = config.mergeBundledDefaults("config-version", TrailsSettingsLoader.CONFIG_VERSION)

                added.contains("trail-creation.feedback.stage-change-sound.enabled") shouldBe true
                config.int("config-version") shouldBe TrailsSettingsLoader.CONFIG_VERSION
                config.double("speed-boost.adjustment-step") shouldBe 0.02
                config.string("operator-owned.note") shouldBe "keep-me"
                config.existsExplicitly("trail-creation.feedback.stage-change-sound.enabled") shouldBe true
                config.mergeBundledDefaults("config-version", TrailsSettingsLoader.CONFIG_VERSION) shouldBe emptySet()
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    })
