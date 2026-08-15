package ru.ruscrafting.trails.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.nio.file.Files
import java.nio.file.Path

private enum class MessageFormat {
    LEGACY,
    MINIMESSAGE,
}

class LocaleService private constructor(
    private val config: YamlConfig,
    private val configuredFormat: MessageFormat,
    val commandName: String,
    private val prefixRaw: String,
) {
    val formatName: String = configuredFormat.name.lowercase()
    val landsDisplayName: String = SECTION.serialize(renderValue("lands.flag.display-name"))
    val landsDescription: List<String> = renderValueList("lands.flag.description").map(SECTION::serialize)

    fun render(
        path: String,
        replacements: Map<String, String> = emptyMap(),
        placeholderParser: (String) -> String = { it },
    ): Component {
        val raw = config.stringOrNull(path) ?: path
        return when (formatFor(path)) {
            MessageFormat.LEGACY -> {
                val rendered =
                    replacements.entries
                        .fold(raw) { message, (key, value) -> message.replace(key, value) }
                        .replace("%plugin_prefix%", prefixRaw)
                        .replace("%command%", commandName)
                LEGACY.deserialize(placeholderParser(rendered))
            }

            MessageFormat.MINIMESSAGE -> {
                val component =
                    MINI.deserialize(
                        raw,
                        TagResolver.builder()
                            .resolver(Placeholder.component("prefix", renderPrefix()))
                            .resolver(Placeholder.unparsed("command", commandName))
                            .apply {
                                replacements.forEach { (key, value) ->
                                    val name = key.trim('%').replace('_', '-')
                                    if (name.isNotEmpty()) resolver(Placeholder.unparsed(name, value))
                                }
                            }.build(),
                    )
                applyPlaceholderApi(component, placeholderParser)
            }
        }
    }

    fun renderLegacy(
        path: String,
        replacements: Map<String, String> = emptyMap(),
        placeholderParser: (String) -> String = { it },
    ): String = SECTION.serialize(render(path, replacements, placeholderParser))

    fun renderLegacyList(
        path: String,
        replacements: Map<String, String> = emptyMap(),
    ): List<String> = renderValueList(path, replacements).map(SECTION::serialize)

    fun plain(path: String): String = config.stringOrNull(path) ?: path

    private fun renderValue(
        path: String,
        replacements: Map<String, String> = emptyMap(),
    ): Component {
        val raw = config.stringOrNull(path) ?: path
        return renderRaw(raw, formatFor(path), replacements)
    }

    private fun renderValueList(
        path: String,
        replacements: Map<String, String> = emptyMap(),
    ): List<Component> =
        config.stringListOrNull(path).orEmpty().map { raw -> renderRaw(raw, formatFor(path), replacements) }

    private fun renderRaw(
        raw: String,
        format: MessageFormat,
        replacements: Map<String, String>,
    ): Component =
        when (format) {
            MessageFormat.LEGACY -> {
                val rendered = replacements.entries.fold(raw) { message, (key, value) -> message.replace(key, value) }
                LEGACY.deserialize(rendered.replace("%plugin_prefix%", prefixRaw).replace("%command%", commandName))
            }
            MessageFormat.MINIMESSAGE -> {
                val resolvers =
                    replacements.map { (key, value) ->
                        Placeholder.unparsed(key.trim('%').replace('_', '-'), value)
                    }
                MINI.deserialize(raw, *resolvers.toTypedArray())
            }
        }

    private fun renderPrefix(): Component =
        when (configuredFormat) {
            MessageFormat.LEGACY -> LEGACY.deserialize(prefixRaw)
            MessageFormat.MINIMESSAGE -> MINI.deserialize(prefixRaw)
        }

    private fun formatFor(path: String): MessageFormat =
        if (config.existsExplicitly(path)) configuredFormat else MessageFormat.MINIMESSAGE

    private fun applyPlaceholderApi(component: Component, parser: (String) -> String): Component {
        val serialized = LEGACY.serialize(component)
        val parsed = parser(serialized)
        return if (parsed == serialized) component else LEGACY.deserialize(parsed)
    }

    companion object {
        private val MINI = MiniMessage.miniMessage()
        private val LEGACY = LegacyComponentSerializer.legacyAmpersand()
        private val SECTION = LegacyComponentSerializer.legacySection()

        fun load(
            dataFolder: Path,
            language: String,
            commandName: String,
        ): LocaleService {
            val config = YamlConfig(dataFolder, "lang/$language.yml")
            require(Files.isRegularFile(dataFolder.resolve("lang/$language.yml"))) {
                "Locale lang/$language.yml does not exist"
            }
            val format =
                if (!config.existsExplicitly("format")) {
                    MessageFormat.LEGACY
                } else {
                    when (config.string("format").trim().lowercase()) {
                        "legacy" -> MessageFormat.LEGACY
                        "minimessage" -> MessageFormat.MINIMESSAGE
                        else -> error("Locale format must be legacy or minimessage")
                    }
                }
            return LocaleService(
                config = config,
                configuredFormat = format,
                commandName = commandName,
                prefixRaw =
                    if (config.existsExplicitly("plugin-prefix")) {
                        config.string("plugin-prefix")
                    } else if (format == MessageFormat.MINIMESSAGE) {
                        "<gray>[<yellow>Trails</yellow>]</gray>"
                    } else {
                        "&7[&eTrails&7]&r"
                    },
            )
        }
    }
}
