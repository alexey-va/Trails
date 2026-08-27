package ru.ruscrafting.trails.config

import org.bukkit.configuration.file.YamlConfiguration
import ru.ruscrafting.trails.domain.TrailDefinition
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ConfigMigrationResult(
    val migrated: Boolean,
    val backup: Path? = null,
    val addedDefaults: Set<String> = emptySet(),
)

class LegacyConfigMigrator(
    private val dataFolder: Path,
    private val materialExists: (String) -> Boolean,
    private val particleExists: (String) -> Boolean,
) {
    fun migrateIfNeeded(config: YamlConfig): ConfigMigrationResult {
        val explicitVersion = config.value("config-version").takeIf { config.existsExplicitly("config-version") }
        if (explicitVersion != null) {
            val version =
                when (explicitVersion) {
                    is Number -> explicitVersion.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                    else -> explicitVersion.toString().toIntOrNull()
                }
            return if (version == 2) migrateV2(config) else ConfigMigrationResult(migrated = false)
        }

        val legacy = LegacyTrailsSettingsLoader.load(config, materialExists, particleExists)
        val locale = YamlConfig(dataFolder, "lang/${legacy.language}.yml")
        val requestedAlias = locale.string("command-name", "trails").trim().lowercase()
        val commandAlias = requestedAlias.takeUnless { it.isBlank() || it == "trails" || it == "paths" }

        val migrated =
            legacy.copy(
                configVersion = TrailsSettingsLoader.CONFIG_VERSION,
                trailsConfigVersion = TrailsSettingsLoader.TRAILS_CONFIG_VERSION,
                commandAlias = commandAlias,
            )
        val configDocument = configDocument(migrated)
        val trailsDocument = trailsDocument(migrated.definitions)
        validateDocuments(configDocument, trailsDocument)

        val configPath = dataFolder.resolve("config.yml")
        val trailsPath = dataFolder.resolve("trails.yml")
        val configBackup = dataFolder.resolve("config.v1.backup.yml")
        if (!Files.exists(configBackup)) Files.copy(configPath, configBackup)
        val trailsBackup = dataFolder.resolve("trails.pre-v2.backup.yml")
        if (Files.exists(trailsPath) && !Files.exists(trailsBackup)) Files.copy(trailsPath, trailsBackup)

        writeAtomic(trailsPath, trailsDocument)
        writeAtomic(configPath, configDocument)
        return ConfigMigrationResult(migrated = true, backup = configBackup)
    }

    private fun migrateV2(config: YamlConfig): ConfigMigrationResult {
        val migrated = bundledYaml("config.yml")
        val preservedPaths =
            listOf(
                "locale",
                "commands.localized-alias",
                "player-defaults.trails-enabled",
                "player-defaults.speed-boost-enabled",
                "worlds.mode",
                "worlds.names",
                "trail-creation.while-sneaking",
                "trail-creation.sprint-progress-multiplier",
                "trail-creation.require-permission",
                "trail-creation.strict-stage-order",
                "trail-creation.visualization-particle",
                "speed-boost.require-permission",
                "speed-boost.only-created-trails",
                "speed-boost.update-interval-ticks",
                "speed-boost.adjustment-step",
                "speed-boost.remove-immediately-off-trail",
                "tools.advance",
                "tools.inspect",
                "decay.enabled",
                "decay.interval-ticks",
                "decay.chunk-selection-chance-percent",
                "decay.blocks-per-chunk-percent",
                "decay.minimum-player-distance-blocks",
                "decay.step-counter-reduction-percent",
                "messages.protection-denied.enabled",
                "messages.protection-denied.cooldown-seconds",
                "storage.player-preferences-save-interval-minutes",
            )
        preservedPaths.forEach { path -> config.value(path)?.let { migrated.set(path, it) } }
        migrated.set(
            "integrations.coreprotect.log-block-changes",
            config.value("integrations.coreprotect.log-block-changes") ?: true,
        )
        val configDocument = migrated.saveToString()
        val trailsPath = dataFolder.resolve("trails.yml")
        val trailsDocument =
            if (Files.exists(trailsPath)) Files.readString(trailsPath, StandardCharsets.UTF_8) else bundledYaml("trails.yml").saveToString()
        validateDocuments(configDocument, trailsDocument)

        val configPath = dataFolder.resolve("config.yml")
        val backup = dataFolder.resolve("config.v2.backup.yml")
        if (!Files.exists(backup)) Files.copy(configPath, backup)
        writeAtomic(configPath, configDocument)
        return ConfigMigrationResult(migrated = true, backup = backup)
    }

    private fun validateDocuments(configDocument: String, trailsDocument: String) {
        val validationFolder = Files.createTempDirectory(dataFolder, ".migration-validation-")
        try {
            Files.writeString(validationFolder.resolve("config.yml"), configDocument)
            Files.writeString(validationFolder.resolve("trails.yml"), trailsDocument)
            TrailsSettingsLoader.load(
                config = YamlConfig(validationFolder, "config.yml"),
                trails = YamlConfig(validationFolder, "trails.yml"),
                materialExists = materialExists,
                particleExists = particleExists,
            )
        } finally {
            validationFolder.toFile().deleteRecursively()
        }
    }

    private fun configDocument(settings: TrailsSettings): String =
        bundledYaml("config.yml").apply {
            set("config-version", TrailsSettingsLoader.CONFIG_VERSION)
            set("locale", settings.language)
            set("commands.localized-alias", settings.commandAlias.orEmpty())
            set("player-defaults.trails-enabled", settings.enabledByDefault)
            set("player-defaults.speed-boost-enabled", settings.boostEnabledByDefault)
            set("worlds.mode", settings.worldMode.name.lowercase())
            set("worlds.names", settings.enabledWorlds.sorted())
            set("trail-creation.while-sneaking", !settings.sneakBypass)
            set("trail-creation.sprint-progress-multiplier", settings.runModifier)
            set("trail-creation.require-permission", settings.usePermissionForTrails)
            set("trail-creation.strict-stage-order", settings.strictLinks)
            set("trail-creation.visualization-particle", settings.trailParticle)
            set("speed-boost.require-permission", settings.usePermissionForBoost)
            set("speed-boost.only-created-trails", settings.speedBoostOnlyTrails)
            set("speed-boost.update-interval-ticks", settings.speedBoostInterval)
            set("speed-boost.adjustment-step", settings.speedBoostStep)
            set("speed-boost.remove-immediately-off-trail", settings.immediatelyRemoveBoost)
            set("tools.advance", settings.trailTool)
            set("tools.inspect", settings.infoTool)
            set("decay.enabled", settings.trailDecay)
            set("decay.interval-ticks", settings.decayTimer)
            set("decay.chunk-selection-chance-percent", settings.chunkChance * 100.0)
            set("decay.blocks-per-chunk-percent", settings.decayFraction * 100.0)
            set("decay.minimum-player-distance-blocks", settings.decayDistance)
            set("decay.step-counter-reduction-percent", settings.stepDecayFraction * 100.0)
            set("messages.protection-denied.enabled", settings.sendDenyMessage)
            set("messages.protection-denied.cooldown-seconds", settings.denyMessageIntervalSeconds)
            set("storage.player-preferences-save-interval-minutes", settings.saveIntervalMinutes)
            with(settings.integrations) {
                set("integrations.protection-events.block-place-compatibility", true)
                set("integrations.coreprotect.log-block-changes", coreProtectChanges)
            }
        }.saveToString()

    private fun trailsDocument(definitions: List<TrailDefinition>): String =
        bundledYaml("trails.yml").apply {
            set("config-version", TrailsSettingsLoader.TRAILS_CONFIG_VERSION)
            // Bukkit normally treats dots in keys as path separators. Trail IDs are
            // persisted identifiers, so write the definition map with an otherwise
            // unused separator to preserve every legacy ID byte-for-byte.
            options().pathSeparator('\u0000')
            set(
                "trails",
                definitions.associateTo(linkedMapOf()) { definition ->
                    definition.name to
                        linkedMapOf<String, Any>(
                            "selection-weight" to definition.selectionWeight,
                            "stages" to
                                definition.stages.mapIndexed { index, stage ->
                                    linkedMapOf<String, Any>("material" to stage.material).apply {
                                        if (index != definition.stages.lastIndex) {
                                            put("required-walks", stage.requiredWalks)
                                            put("count-chance-percent", stage.chancePercent)
                                        }
                                        put("speed-multiplier", stage.speedMultiplier)
                                    }
                                },
                        )
                },
            )
        }.saveToString()

    private fun bundledYaml(resource: String): YamlConfiguration {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        return loader.getResourceAsStream(resource)?.use { source ->
            InputStreamReader(source, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        } ?: YamlConfiguration()
    }

    private fun writeAtomic(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
