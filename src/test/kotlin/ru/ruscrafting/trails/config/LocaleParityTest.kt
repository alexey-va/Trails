package ru.ruscrafting.trails.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class LocaleParityTest :
    FreeSpec({
        "Russian and English player messages have identical keys" {
            val folder = Files.createTempDirectory("trails-locales-")
            try {
                val english = YamlConfig(folder, "lang/en-US.yml")
                val russian = YamlConfig(folder, "lang/ru-RU.yml")
                val chinese = YamlConfig(folder, "lang/zh-CN.yml")

                russian.keys("messages") shouldContainExactlyInAnyOrder english.keys("messages")
                chinese.keys("messages") shouldContainExactlyInAnyOrder english.keys("messages")
                russian.keys("roadProfiles") shouldContainExactlyInAnyOrder english.keys("roadProfiles")
                chinese.keys("roadProfiles") shouldContainExactlyInAnyOrder english.keys("roadProfiles")
                russian.keys("tools") shouldContainExactlyInAnyOrder english.keys("tools")
                chinese.keys("tools") shouldContainExactlyInAnyOrder english.keys("tools")
                val roads = RoadSettingsLoader.load(YamlConfig(folder, "roads.yml"))
                english.keys("roadProfiles") shouldContainExactlyInAnyOrder roads.profiles.keys

                val locale = LocaleService.load(folder, "ru-RU", "trails")
                locale.formatName shouldBe "minimessage"
                locale.renderLegacy("messages.toggledOnOther", mapOf("%name%" to "<red>Игрок")) shouldContain "<red>Игрок"
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "an existing legacy locale inherits new bundled messages without being overwritten" {
            val folder = Files.createTempDirectory("trails-legacy-locale-")
            try {
                val localeFolder = Files.createDirectories(folder.resolve("lang"))
                val legacyFile = localeFolder.resolve("ru-RU.yml")
                val original =
                    """
                    command-name: trails
                    messages:
                      toggledOn: '%plugin_prefix% &aСвоё сообщение.'
                    """.trimIndent() + "\n"
                Files.writeString(legacyFile, original)

                val locale = LocaleService.load(folder, "ru-RU", "trails")

                val rendered = locale.renderLegacy("messages.toggledOn")
                rendered shouldContain "Своё сообщение"
                rendered.contains("<gray>") shouldBe false
                locale.renderLegacy("messages.reloadFailed", mapOf("%error%" to "ошибка")) shouldContain "ошибка"
                locale.renderLegacy("messages.trail-info", mapOf("%walks%" to "3", "%trail%" to "DirtPath:1")) shouldContain "DirtPath:1"
                Files.readString(legacyFile) shouldBe original
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "bundled locale fallback does not depend on the server thread context classloader" {
            val folder = Files.createTempDirectory("trails-context-loader-locale-")
            val thread = Thread.currentThread()
            val previousLoader = thread.contextClassLoader
            try {
                val localeFolder = Files.createDirectories(folder.resolve("lang"))
                Files.writeString(
                    localeFolder.resolve("ru-RU.yml"),
                    "format: minimessage\nplugin-prefix: '<yellow>Тропы <gray>»'\n",
                )
                thread.contextClassLoader = object : ClassLoader(null) {}

                val locale = LocaleService.load(folder, "ru-RU", "trails")

                locale.renderLegacy(
                    "messages.trail-info",
                    mapOf("%walks%" to "3", "%trail%" to "DirtPath:1"),
                ) shouldContain "DirtPath:1"
            } finally {
                thread.contextClassLoader = previousLoader
                folder.toFile().deleteRecursively()
            }
        }
    })
