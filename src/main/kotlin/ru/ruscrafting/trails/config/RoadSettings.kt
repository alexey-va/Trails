package ru.ruscrafting.trails.config

import org.bukkit.Material

data class RoadProfile(
    val name: String,
    val lanes: List<Material>,
) {
    val width: Int = lanes.size
}

data class RoadSettings(
    val configVersion: Int,
    val enabled: Boolean,
    val worlds: Set<String>,
    val maxPlannedBlocks: Int,
    val previewExpirySeconds: Long,
    val surfaceSearchDepth: Int,
    val maxSegmentDistanceBlocks: Int,
    val maxSegmentHeightDifferenceBlocks: Int,
    val replaceableMaterials: Set<Material>,
    val profiles: Map<String, RoadProfile>,
) {
    val roadMaterials: Set<Material> = profiles.values.flatMapTo(linkedSetOf()) { it.lanes }
    val paintableMaterials: Set<Material> = replaceableMaterials + roadMaterials

    fun worldEnabled(name: String): Boolean = worlds.isEmpty() || worlds.any { it.equals(name, ignoreCase = true) }
}

object RoadSettingsLoader {
    const val CONFIG_VERSION = 1

    fun load(
        config: YamlConfig,
    ): RoadSettings {
        val problems = mutableListOf<String>()
        val version = integer(config, "config-version", CONFIG_VERSION, problems)
        if (version != CONFIG_VERSION) problems += "roads.yml config-version must be $CONFIG_VERSION (found $version)"

        val maxBlocks = integer(config, "limits.max-planned-blocks", 256, problems)
        if (maxBlocks !in 1..1024) problems += "limits.max-planned-blocks must be between 1 and 1024"
        val expiry = integer(config, "limits.preview-expiry-seconds", 300, problems)
        if (expiry !in 10..3600) problems += "limits.preview-expiry-seconds must be between 10 and 3600"
        val depth = integer(config, "limits.surface-search-depth", 2, problems)
        if (depth !in 0..8) problems += "limits.surface-search-depth must be between 0 and 8"
        val maxSegmentDistance = integer(config, "limits.max-segment-distance-blocks", 16, problems)
        if (maxSegmentDistance !in 1..64) problems += "limits.max-segment-distance-blocks must be between 1 and 64"
        val maxSegmentHeightDifference = integer(config, "limits.max-segment-height-difference-blocks", 4, problems)
        if (maxSegmentHeightDifference !in 0..16) {
            problems += "limits.max-segment-height-difference-blocks must be between 0 and 16"
        }
        val enabled = boolean(config, "enabled", false, problems)

        val rawReplaceable = config.stringList("replaceable-materials")
        if (rawReplaceable.size > 64) problems += "replaceable-materials must contain at most 64 entries"
        val replaceable =
            materials(rawReplaceable.take(64), "replaceable-materials", problems)
                .filterTo(linkedSetOf()) { it.isBlock && it.isSolid && !it.isAir }
        if (replaceable.isEmpty()) problems += "replaceable-materials must contain at least one solid block material"

        val profiles = linkedMapOf<String, RoadProfile>()
        val profileNames = config.keys("profiles")
        if (profileNames.size > 32) problems += "profiles must contain at most 32 entries"
        profileNames.take(32).forEach { rawName ->
            val name = rawName.trim().lowercase()
            if (name.length > 32 || !PROFILE_NAME.matches(name)) {
                problems += "profiles.$rawName must use at most 32 lowercase letters, digits, underscores, or hyphens"
            }
            val rawLanes = config.stringList("profiles.$rawName.lanes")
            val lanes = materials(rawLanes, "profiles.$rawName.lanes", problems)
            if (rawLanes.size !in 1..7 || rawLanes.size % 2 == 0) {
                problems += "profiles.$rawName.lanes must contain an odd number of materials between 1 and 7"
            }
            lanes.forEach { material ->
                if (!material.isBlock || !material.isSolid || material.isAir) {
                    problems += "profiles.$rawName.lanes uses non-solid block material '${material.name}'"
                }
            }
            profiles[name] = RoadProfile(name, lanes)
        }
        if (profiles.isEmpty()) problems += "profiles must define at least one road profile"

        val worlds = config.stringList("worlds").map(String::trim).filter(String::isNotEmpty)
        if (worlds.size > 64 || worlds.any { it.length > 64 }) problems += "worlds must contain at most 64 names of at most 64 characters"
        if (problems.isNotEmpty()) throw TrailsSettingsException(problems.distinct())
        return RoadSettings(
            configVersion = version,
            enabled = enabled,
            worlds = worlds.toSet(),
            maxPlannedBlocks = maxBlocks,
            previewExpirySeconds = expiry.toLong(),
            surfaceSearchDepth = depth,
            maxSegmentDistanceBlocks = maxSegmentDistance,
            maxSegmentHeightDifferenceBlocks = maxSegmentHeightDifference,
            replaceableMaterials = replaceable,
            profiles = profiles,
        )
    }

    private fun materials(
        values: List<String>,
        path: String,
        problems: MutableList<String>,
    ): List<Material> =
        values.mapNotNull { raw ->
            Material.getMaterial(raw.trim().uppercase()).also { material ->
                if (material == null) problems += "$path uses unknown material '${raw.trim().uppercase()}'"
            }
        }

    private fun integer(
        config: YamlConfig,
        path: String,
        default: Int,
        problems: MutableList<String>,
    ): Int {
        val raw = config.value(path) ?: return default
        val value =
            when (raw) {
                is Number -> raw.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        if (value == null) problems += "$path must be an integer"
        return value ?: default
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

    private val PROFILE_NAME = Regex("[a-z0-9_-]+")
}
