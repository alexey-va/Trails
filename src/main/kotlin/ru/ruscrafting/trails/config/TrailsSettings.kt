package ru.ruscrafting.trails.config

import org.bukkit.Material
import org.bukkit.Particle
import ru.ruscrafting.trails.domain.StructuredTrailDefinitionParser
import ru.ruscrafting.trails.domain.TrailDefinition
import ru.ruscrafting.trails.domain.TrailDefinitionException
import ru.ruscrafting.trails.domain.TrailDefinitionParser

enum class WorldMode {
    ALL,
    ALLOWLIST,
    BLOCKLIST,
}

data class TrailsSettings(
    val configVersion: Int,
    val trailsConfigVersion: Int,
    val language: String,
    val commandAlias: String?,
    val enabledByDefault: Boolean,
    val boostEnabledByDefault: Boolean,
    val runModifier: Double,
    val sneakBypass: Boolean,
    val speedBoostInterval: Long,
    val speedBoostStep: Double,
    val speedBoostOnlyTrails: Boolean,
    val usePermissionForTrails: Boolean,
    val usePermissionForBoost: Boolean,
    val immediatelyRemoveBoost: Boolean,
    val trailTool: String,
    val infoTool: String,
    val trailDecay: Boolean,
    val decayFraction: Double,
    val chunkChance: Double,
    val decayTimer: Long,
    val decayDistance: Double,
    val stepDecayFraction: Double,
    val strictLinks: Boolean,
    val trailParticle: String,
    val worldMode: WorldMode,
    val enabledWorlds: Set<String>,
    val sendDenyMessage: Boolean,
    val denyMessageIntervalSeconds: Long,
    val saveIntervalMinutes: Long,
    val integrations: IntegrationSettings,
    val definitions: List<TrailDefinition>,
) {
    val trailToolMaterial: Material = Material.valueOf(trailTool)
    val infoToolMaterial: Material = Material.valueOf(infoTool)
    val particle: Particle = Particle.valueOf(trailParticle)

    fun worldEnabled(worldName: String): Boolean =
        when (worldMode) {
            WorldMode.ALL -> true
            WorldMode.ALLOWLIST -> enabledWorlds.any { it.equals(worldName, ignoreCase = true) }
            WorldMode.BLOCKLIST -> enabledWorlds.none { it.equals(worldName, ignoreCase = true) }
        }

    fun worldSummary(): String =
        when (worldMode) {
            WorldMode.ALL -> "all"
            WorldMode.ALLOWLIST -> "allowlist:${enabledWorlds.sorted().joinToString(",")}"
            WorldMode.BLOCKLIST -> "blocklist:${enabledWorlds.sorted().joinToString(",")}"
        }
}

data class IntegrationSettings(
    val townyEnabled: Boolean,
    val townyPathsInWilderness: Boolean,
    val townyPermissionMode: Boolean,
    val landsEnabled: Boolean,
    val landsPathsInWilderness: Boolean,
    val landsApplyInSubAreas: Boolean,
    val landsFlagIconMaterial: String,
    val griefPreventionEnabled: Boolean,
    val griefPreventionPathsInWilderness: Boolean,
    val worldGuardEnabled: Boolean,
    val worldGuardCheckBypass: Boolean,
    val worldGuardDecayFlag: Boolean,
    val logBlockChanges: Boolean,
    val coreProtectChanges: Boolean,
    val playerPlotEnabled: Boolean,
    val redProtectEnabled: Boolean,
    val residenceEnabled: Boolean,
    val dynmapRender: Boolean,
)

class TrailsSettingsException(
    val problems: List<String>,
) : IllegalArgumentException(problems.joinToString(separator = "\n"))

object TrailsSettingsLoader {
    const val CONFIG_VERSION = 2
    const val TRAILS_CONFIG_VERSION = 1
    private val COMMAND_ALIAS = Regex("[a-z0-9_-]+")

    fun load(
        config: YamlConfig,
        trails: YamlConfig,
        materialExists: (String) -> Boolean,
        particleExists: (String) -> Boolean,
    ): TrailsSettings {
        val problems = mutableListOf<String>()
        val configVersion = integer(config, "config-version", 0L, problems).toInt()
        val trailsConfigVersion = integer(trails, "config-version", 0L, problems).toInt()
        if (configVersion != CONFIG_VERSION) problems += "config-version must be $CONFIG_VERSION (found $configVersion)"
        if (trailsConfigVersion != TRAILS_CONFIG_VERSION) {
            problems += "trails.yml config-version must be $TRAILS_CONFIG_VERSION (found $trailsConfigVersion)"
        }

        val language = config.string("locale", "en-US").trim()
        if (language.isBlank()) problems += "locale must not be blank"

        val rawAlias = config.string("commands.localized-alias", "").trim().lowercase()
        val commandAlias = rawAlias.ifEmpty { null }
        if (commandAlias != null && !COMMAND_ALIAS.matches(commandAlias)) {
            problems += "commands.localized-alias must contain only lowercase letters, digits, underscores, or hyphens"
        }

        val worldMode =
            when (config.string("worlds.mode", "all").trim().lowercase()) {
                "all" -> WorldMode.ALL
                "allowlist" -> WorldMode.ALLOWLIST
                "blocklist" -> WorldMode.BLOCKLIST
                else -> {
                    problems += "worlds.mode must be one of: all, allowlist, blocklist"
                    WorldMode.ALL
                }
            }
        val worlds = config.stringList("worlds.names").map(String::trim).filter(String::isNotEmpty).toSet()
        if (worldMode != WorldMode.ALL && worlds.isEmpty()) problems += "worlds.names must not be empty when worlds.mode is not all"

        val particle = enumValue("trail-creation.visualization-particle", config, "NAUTILUS", particleExists, problems, "particle")
        val trailTool = itemMaterial("tools.advance", config, "IRON_SHOVEL", materialExists, problems)
        val infoTool = itemMaterial("tools.inspect", config, "STICK", materialExists, problems)
        val landsFlagIcon = enumValue("integrations.lands.flag-icon-material", config, "DIRT_PATH", materialExists, problems, "material")

        val definitions =
            try {
                StructuredTrailDefinitionParser(materialExists).parse(trails.value("trails"))
            } catch (exception: TrailDefinitionException) {
                problems += exception.problems
                emptyList()
            }

        val settings =
            TrailsSettings(
                configVersion = configVersion,
                trailsConfigVersion = trailsConfigVersion,
                language = language,
                commandAlias = commandAlias,
                enabledByDefault = boolean(config, "player-defaults.trails-enabled", true, problems),
                boostEnabledByDefault = boolean(config, "player-defaults.speed-boost-enabled", true, problems),
                runModifier = doubleInRange(config, "trail-creation.sprint-progress-multiplier", 1.5, 0.0..10.0, problems),
                sneakBypass = !boolean(config, "trail-creation.while-sneaking", false, problems),
                speedBoostInterval = positiveLong(config, "speed-boost.update-interval-ticks", 1L, problems),
                speedBoostStep = doubleInRange(config, "speed-boost.adjustment-step", 0.006, 0.0001..1.0, problems),
                speedBoostOnlyTrails = boolean(config, "speed-boost.only-created-trails", true, problems),
                usePermissionForTrails = boolean(config, "trail-creation.require-permission", false, problems),
                usePermissionForBoost = boolean(config, "speed-boost.require-permission", false, problems),
                immediatelyRemoveBoost = boolean(config, "speed-boost.remove-immediately-off-trail", false, problems),
                trailTool = trailTool,
                infoTool = infoTool,
                trailDecay = boolean(config, "decay.enabled", true, problems),
                decayFraction = percent(config, "decay.blocks-per-chunk-percent", 3.0, problems),
                chunkChance = percent(config, "decay.chunk-selection-chance-percent", 20.0, problems),
                decayTimer = positiveLong(config, "decay.interval-ticks", 1200L, problems),
                decayDistance = doubleInRange(config, "decay.minimum-player-distance-blocks", 5.0, 0.0..128.0, problems),
                stepDecayFraction = percent(config, "decay.step-counter-reduction-percent", 10.0, problems),
                strictLinks = boolean(config, "trail-creation.strict-stage-order", false, problems),
                trailParticle = particle,
                worldMode = worldMode,
                enabledWorlds = worlds,
                sendDenyMessage = boolean(config, "messages.protection-denied.enabled", false, problems),
                denyMessageIntervalSeconds = positiveLong(config, "messages.protection-denied.cooldown-seconds", 10L, problems),
                saveIntervalMinutes = positiveLong(config, "storage.player-preferences-save-interval-minutes", 5L, problems),
                integrations =
                    IntegrationSettings(
                        townyEnabled = boolean(config, "integrations.towny.enabled", true, problems),
                        townyPathsInWilderness = boolean(config, "integrations.towny.allow-in-wilderness", true, problems),
                        townyPermissionMode = boolean(config, "integrations.towny.permission-mode", false, problems),
                        landsEnabled = boolean(config, "integrations.lands.enabled", true, problems),
                        landsPathsInWilderness = boolean(config, "integrations.lands.allow-in-wilderness", true, problems),
                        landsApplyInSubAreas = boolean(config, "integrations.lands.apply-flag-to-subareas", true, problems),
                        landsFlagIconMaterial = landsFlagIcon,
                        griefPreventionEnabled = boolean(config, "integrations.griefprevention.enabled", true, problems),
                        griefPreventionPathsInWilderness = boolean(config, "integrations.griefprevention.allow-in-wilderness", true, problems),
                        worldGuardEnabled = boolean(config, "integrations.worldguard.enabled", true, problems),
                        worldGuardCheckBypass = boolean(config, "integrations.worldguard.allow-bypass", false, problems),
                        worldGuardDecayFlag = boolean(config, "integrations.worldguard.register-decay-flag", false, problems),
                        logBlockChanges = boolean(config, "integrations.logblock.log-block-changes", true, problems),
                        coreProtectChanges = boolean(config, "integrations.coreprotect.log-block-changes", true, problems),
                        playerPlotEnabled = boolean(config, "integrations.playerplot.enabled", true, problems),
                        redProtectEnabled = boolean(config, "integrations.redprotect.enabled", true, problems),
                        residenceEnabled = boolean(config, "integrations.residence.enabled", true, problems),
                        dynmapRender = boolean(config, "integrations.dynmap.trigger-render", true, problems),
                    ),
                definitions = definitions,
            )
        if (problems.isNotEmpty()) throw TrailsSettingsException(problems)
        return settings
    }

    private fun positiveLong(config: YamlConfig, path: String, default: Long, problems: MutableList<String>): Long {
        val value = integer(config, path, default, problems)
        if (value <= 0) problems += "$path must be positive"
        return value
    }

    private fun itemMaterial(
        path: String,
        config: YamlConfig,
        default: String,
        materialExists: (String) -> Boolean,
        problems: MutableList<String>,
    ): String {
        val value = enumValue(path, config, default, materialExists, problems, "material")
        val material = Material.valueOf(value)
        if (!material.isItem || material.isAir) problems += "$path must be an inventory item material"
        return value
    }

    private fun integer(config: YamlConfig, path: String, default: Long, problems: MutableList<String>): Long {
        val raw = config.value(path) ?: return default
        val value =
            when (raw) {
                is Number -> {
                    val number = raw.toDouble()
                    number.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() }?.toLong()
                }
                is String -> raw.trim().toLongOrNull()
                else -> null
            }
        if (value == null) problems += "$path must be an integer"
        return value ?: default
    }

    private fun doubleInRange(
        config: YamlConfig,
        path: String,
        default: Double,
        range: ClosedFloatingPointRange<Double>,
        problems: MutableList<String>,
    ): Double {
        val raw = config.value(path) ?: return default
        val value =
            when (raw) {
                is Number -> raw.toDouble().takeIf(Double::isFinite)
                is String -> raw.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                else -> null
            }
        if (value == null) {
            problems += "$path must be a number"
            return default
        }
        if (value !in range) problems += "$path must be between ${range.start} and ${range.endInclusive}"
        return value
    }

    private fun boolean(
        config: YamlConfig,
        path: String,
        default: Boolean,
        problems: MutableList<String>,
    ): Boolean {
        val raw = config.value(path) ?: return default
        val value =
            when (raw) {
                is Boolean -> raw
                is Number -> when (raw.toDouble()) {
                    1.0 -> true
                    0.0 -> false
                    else -> null
                }
                is String ->
                    when (raw.trim().lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> null
                    }
                else -> null
            }
        if (value == null) problems += "$path must be a boolean"
        return value ?: default
    }

    private fun percent(config: YamlConfig, path: String, default: Double, problems: MutableList<String>): Double =
        doubleInRange(config, path, default, 0.0..100.0, problems) / 100.0

    private fun enumValue(
        path: String,
        config: YamlConfig,
        default: String,
        exists: (String) -> Boolean,
        problems: MutableList<String>,
        kind: String,
    ): String {
        val value = config.string(path, default).trim().uppercase()
        if (!exists(value)) {
            problems += "$path uses unknown $kind '$value'"
            return default
        }
        return value
    }
}

object LegacyTrailsSettingsLoader {
    fun load(
        config: YamlConfig,
        materialExists: (String) -> Boolean,
        particleExists: (String) -> Boolean,
    ): TrailsSettings {
        val problems = mutableListOf<String>()
        fun doubleInRange(path: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
            val value = config.double(path, default)
            if (value !in range) problems += "$path must be between ${range.start} and ${range.endInclusive}"
            return value
        }

        fun positiveLong(path: String, default: Long): Long {
            val raw = config.value(path)
            val value =
                when (raw) {
                    null -> default
                    is Number -> {
                        val number = raw.toDouble()
                        number.takeIf {
                            it.isFinite() &&
                                it % 1.0 == 0.0 &&
                                it in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
                        }?.toLong()
                    }
                    is String -> raw.trim().toLongOrNull()
                    else -> null
                }
            if (value == null) {
                problems += "$path must be an integer"
                return default
            }
            if (value <= 0) problems += "$path must be positive"
            return value
        }

        fun material(path: String, default: String, itemOnly: Boolean = false): String {
            val value = config.string(path, default).trim().uppercase()
            if (!materialExists(value)) {
                problems += "$path uses unknown material '$value'"
                return default
            }
            val material = Material.valueOf(value)
            if (itemOnly && (!material.isItem || material.isAir)) {
                problems += "$path must be an inventory item material"
                return default
            }
            return value
        }

        val configuredParticle = config.string("General.trails-particle", "NAUTILUS").trim().uppercase()
        val particle =
            if (particleExists(configuredParticle)) {
                configuredParticle
            } else {
                problems += "General.trails-particle uses unknown particle '$configuredParticle'"
                "NAUTILUS"
            }

        val definitions =
            try {
                TrailDefinitionParser(materialExists).parse(flattenLegacyTrails(config.map("Trails")))
            } catch (exception: TrailDefinitionException) {
                problems += exception.problems
                emptyList()
            }
        val worlds = config.stringList("General.enabled-worlds", listOf("all")).map(String::trim).filter(String::isNotEmpty).toSet()
        val allWorlds = worlds.any { it.equals("all", ignoreCase = true) }

        val settings =
            TrailsSettings(
                configVersion = 1,
                trailsConfigVersion = 0,
                language = config.string("General.Language", "en-US").trim(),
                commandAlias = null,
                enabledByDefault = config.boolean("General.enabled-by-default", true),
                boostEnabledByDefault = config.boolean("General.boost-enabled-by-default", true),
                runModifier = doubleInRange("General.run-modifier", 1.5, 0.0..10.0),
                sneakBypass = config.boolean("General.sneak-bypass", true),
                speedBoostInterval = positiveLong("General.speed-boost-interval", 1L),
                speedBoostStep = doubleInRange("General.speed-boost-step", 0.006, 0.0001..1.0),
                speedBoostOnlyTrails = config.boolean("General.speed-boost-only-trails", true),
                usePermissionForTrails = config.boolean("General.use-permission-for-trails", false),
                usePermissionForBoost = config.boolean("General.use-permission-for-boost", false),
                immediatelyRemoveBoost = config.boolean("General.immediately-remove-boost", false),
                trailTool = material("General.trail-tool", "IRON_SHOVEL", itemOnly = true),
                infoTool = material("General.info-tool", "STICK", itemOnly = true),
                trailDecay = config.boolean("General.trail-decay", true),
                decayFraction = doubleInRange("General.decay-fraction", 0.03, 0.0..1.0),
                chunkChance = doubleInRange("General.chunk-chance", 0.2, 0.0..1.0),
                decayTimer = positiveLong("General.decay-timer", 1200L),
                decayDistance = doubleInRange("General.decay-distance", 5.0, 0.0..128.0),
                stepDecayFraction = doubleInRange("General.step-decay-fraction", 0.1, 0.0..1.0),
                strictLinks = config.boolean("General.strict-links", false),
                trailParticle = particle,
                worldMode = if (allWorlds) WorldMode.ALL else WorldMode.ALLOWLIST,
                enabledWorlds = worlds.filterNot { it.equals("all", ignoreCase = true) }.toSet(),
                sendDenyMessage = config.boolean("Messages.SendDenyMessage", false),
                denyMessageIntervalSeconds = positiveLong("Messages.Interval", 10L),
                saveIntervalMinutes = positiveLong("Data-Saving.Interval", 5L),
                integrations =
                    IntegrationSettings(
                        townyEnabled = true,
                        townyPathsInWilderness = config.boolean("Plugin-Integration.Towny.PathsInWilderness", true),
                        townyPermissionMode = config.boolean("Plugin-Integration.Towny.TownyPathsPerm", false),
                        landsEnabled = true,
                        landsPathsInWilderness = config.boolean("Plugin-Integration.Lands.PathsInWilderness", true),
                        landsApplyInSubAreas = config.boolean("Plugin-Integration.Lands.ApplyInSubAreas", true),
                        landsFlagIconMaterial = "DIRT_PATH",
                        griefPreventionEnabled = true,
                        griefPreventionPathsInWilderness = config.boolean("Plugin-Integration.GriefPrevention.PathsInWilderness", true),
                        worldGuardEnabled = config.boolean("Plugin-Integration.WorldGuard.IntegrationEnabled", true),
                        worldGuardCheckBypass = config.boolean("Plugin-Integration.WorldGuard.CheckBypass", false),
                        worldGuardDecayFlag = config.boolean("Plugin-Integration.WorldGuard.decay-flag", false),
                        logBlockChanges = config.boolean("Plugin-Integration.LogBlock.LogPathBlocks", true),
                        coreProtectChanges = config.boolean("Plugin-Integration.CoreProtect.LogPathBlocks", true),
                        playerPlotEnabled = config.boolean("Plugin-Integration.PlayerPlot.integration-enabled", true),
                        redProtectEnabled = config.boolean("Plugin-Integration.RedProtect.integration-enabled", true),
                        residenceEnabled = config.boolean("Plugin-Integration.Residence.integration-enabled", true),
                        dynmapRender = config.boolean("Plugin-Integration.Dynmap.trails-trigger-render", true),
                    ),
                definitions = definitions,
            )
        if (settings.language.isBlank()) problems += "General.Language must not be blank"
        if (!allWorlds && settings.enabledWorlds.isEmpty()) problems += "General.enabled-worlds must not be empty"
        if (problems.isNotEmpty()) throw TrailsSettingsException(problems)
        return settings
    }

    private fun flattenLegacyTrails(
        entries: Map<String, Any>,
        prefix: String = "",
    ): Map<String, String> =
        buildMap {
            entries.forEach { (key, value) ->
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                @Suppress("UNCHECKED_CAST")
                if (value is Map<*, *> && value.keys.all { it is String }) {
                    putAll(flattenLegacyTrails(value as Map<String, Any>, name))
                } else {
                    put(name, value.toString())
                }
            }
        }
}
