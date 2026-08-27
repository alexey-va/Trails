package ru.ruscrafting.trails.config

import org.bukkit.Material
import org.bukkit.Particle
import java.nio.file.Path

internal data class TrailsConfigurationSnapshot(
    val settings: TrailsSettings,
    val roads: RoadSettings,
    val locale: LocaleService,
)

/** Owns the versioned Trails files and builds complete, validated reload candidates. */
internal class TrailsConfiguration(
    private val dataFolder: Path,
    ensureBundledResource: (String) -> Unit,
    migrationReporter: (ConfigMigrationResult) -> Unit = {},
) {
    private val configFile: YamlConfig
    private val trailsFile: YamlConfig
    private val roadsFile: YamlConfig

    init {
        BUNDLED_RESOURCES.forEach(ensureBundledResource)
        configFile = YamlConfig(dataFolder, "config.yml")
        val migration =
            LegacyConfigMigrator(
                dataFolder = dataFolder,
                materialExists = ::materialExists,
                particleExists = ::particleExists,
            ).migrateIfNeeded(configFile)
        if (migration.migrated) {
            migrationReporter(migration)
            configFile.reload()
        }
        val addedDefaults =
            configFile.mergeBundledDefaults(
                versionPath = "config-version",
                targetVersion = TrailsSettingsLoader.CONFIG_VERSION,
            )
        if (addedDefaults.isNotEmpty()) {
            migrationReporter(ConfigMigrationResult(migrated = true, addedDefaults = addedDefaults))
        }
        trailsFile = YamlConfig(dataFolder, "trails.yml")
        roadsFile = YamlConfig(dataFolder, "roads.yml")
    }

    fun load(reload: Boolean): TrailsConfigurationSnapshot {
        if (reload) {
            configFile.reload()
            trailsFile.reload()
            roadsFile.reload()
        }
        val settings =
            TrailsSettingsLoader.load(
                config = configFile,
                trails = trailsFile,
                materialExists = ::materialExists,
                particleExists = ::particleExists,
            )
        return TrailsConfigurationSnapshot(
            settings = settings,
            roads = RoadSettingsLoader.load(roadsFile),
            locale =
                LocaleService.load(
                    dataFolder = dataFolder,
                    language = settings.language,
                    commandName = settings.commandAlias ?: "trails",
                ),
        )
    }

    private fun materialExists(name: String): Boolean = Material.getMaterial(name) != null

    private fun particleExists(name: String): Boolean = runCatching { Particle.valueOf(name) }.isSuccess

    private companion object {
        val BUNDLED_RESOURCES =
            listOf(
                "config.yml",
                "trails.yml",
                "roads.yml",
                "lang/en-US.yml",
                "lang/ru-RU.yml",
                "lang/zh-CN.yml",
                "players.yml",
            )
    }
}
