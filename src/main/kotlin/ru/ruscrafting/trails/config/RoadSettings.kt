package ru.ruscrafting.trails.config

import org.bukkit.Material
import kotlin.math.abs

enum class RoadReplacementMode {
    ALLOWLIST,
    SAFE_SOLID,
}

data class RoadSettings(
    val configVersion: Int,
    val enabled: Boolean,
    val worlds: Set<String>,
    val maxPlannedBlocks: Int,
    val previewExpirySeconds: Long,
    val surfaceSearchDepth: Int,
    val maxCrossSlopeBlocks: Int,
    val maxSegmentDistanceBlocks: Int,
    val maxSegmentHeightDifferenceBlocks: Int,
    val captureWhileFlying: Boolean,
    val smoothingEnabled: Boolean,
    val smoothingToleranceBlocks: Double,
    val smoothingMaxGradeRunBlocks: Int,
    val clearanceHeightBlocks: Int,
    val clearableMaterials: Set<Material>,
    val replacementMode: RoadReplacementMode,
    val replaceableMaterials: Set<Material>,
    val protectedMaterials: Set<Material>,
    val returnReplacedBlocksInSurvival: Boolean,
    val heightTransitionsEnabled: Boolean,
    val defaultHeightTransitionPalette: RoadMaterialPalette?,
    val decorationPatterns: Map<String, RoadDecorationPattern>,
    val profiles: Map<String, RoadProfile>,
) {
    val roadMaterials: Set<Material> =
        profiles.values.flatMapTo(linkedSetOf()) { profile ->
            profile.laneMaterials + profile.heightTransitionPalette?.materials.orEmpty()
        }
    val paintableMaterials: Set<Material> = replaceableMaterials + roadMaterials

    fun worldEnabled(name: String): Boolean = worlds.isEmpty() || worlds.any { it.equals(name, ignoreCase = true) }

    fun heightTransitionPalette(profile: RoadProfile): RoadMaterialPalette? =
        if (heightTransitionsEnabled) profile.heightTransitionPalette ?: defaultHeightTransitionPalette else null
}

object RoadMaterialSafety {
    fun isOrdinarySolid(material: Material): Boolean =
        material.isBlock &&
            material.isSolid &&
            !material.isAir &&
            material.hardness >= 0.0F &&
            !isOre(material) &&
            !isSpecial(material)

    fun isHeightTransition(material: Material): Boolean =
        isOrdinarySolid(material) && (material.name.endsWith("_STAIRS") || material.name.endsWith("_SLAB"))

    fun isDecoration(material: Material): Boolean =
        material.isBlock &&
            material.isItem &&
            !material.isAir &&
            material.hardness >= 0.0F &&
            !isOre(material) &&
            !isSpecial(material)

    fun isClearableRoadObstruction(material: Material): Boolean = material.name in CLEARABLE_ROAD_OBSTRUCTIONS

    private fun isOre(material: Material): Boolean = material.name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS

    private fun isSpecial(material: Material): Boolean {
        val name = material.name
        return material in SPECIAL_MATERIALS ||
            name.endsWith("_HEAD") ||
            name.endsWith("_SKULL") ||
            name.endsWith("_WALL_HEAD") ||
            name.endsWith("_WALL_SKULL") ||
            name.endsWith("_SHULKER_BOX") ||
            name.endsWith("_SIGN") ||
            name.endsWith("_WALL_SIGN") ||
            name.endsWith("_HANGING_SIGN") ||
            name.endsWith("_WALL_HANGING_SIGN") ||
            name.endsWith("_BANNER") ||
            name.endsWith("_WALL_BANNER") ||
            name.endsWith("_BED")
    }

    private val SPECIAL_MATERIALS =
        setOf(
            Material.BARRIER,
            Material.BEDROCK,
            Material.BEACON,
            Material.BEE_NEST,
            Material.BEEHIVE,
            Material.BREWING_STAND,
            Material.BUDDING_AMETHYST,
            Material.CHEST,
            Material.CHISELED_BOOKSHELF,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.CONDUIT,
            Material.CRAFTER,
            Material.CRYING_OBSIDIAN,
            Material.DECORATED_POT,
            Material.DISPENSER,
            Material.DRAGON_EGG,
            Material.DROPPER,
            Material.ENCHANTING_TABLE,
            Material.ENDER_CHEST,
            Material.END_GATEWAY,
            Material.END_PORTAL,
            Material.END_PORTAL_FRAME,
            Material.HOPPER,
            Material.JIGSAW,
            Material.JUKEBOX,
            Material.LECTERN,
            Material.LIGHT,
            Material.NETHER_PORTAL,
            Material.OBSIDIAN,
            Material.REINFORCED_DEEPSLATE,
            Material.RESPAWN_ANCHOR,
            Material.SPAWNER,
            Material.STRUCTURE_BLOCK,
            Material.SUSPICIOUS_GRAVEL,
            Material.SUSPICIOUS_SAND,
            Material.TRAPPED_CHEST,
            Material.TRIAL_SPAWNER,
            Material.VAULT,
        )

    private val CLEARABLE_ROAD_OBSTRUCTIONS =
        setOf(
            "SHORT_GRASS",
            "TALL_GRASS",
            "FERN",
            "LARGE_FERN",
            "DEAD_BUSH",
            "DANDELION",
            "POPPY",
            "BLUE_ORCHID",
            "ALLIUM",
            "AZURE_BLUET",
            "RED_TULIP",
            "ORANGE_TULIP",
            "WHITE_TULIP",
            "PINK_TULIP",
            "OXEYE_DAISY",
            "CORNFLOWER",
            "LILY_OF_THE_VALLEY",
            "WITHER_ROSE",
            "TORCHFLOWER",
            "PINK_PETALS",
            "WILDFLOWERS",
            "PITCHER_PLANT",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "CRIMSON_ROOTS",
            "WARPED_ROOTS",
            "NETHER_SPROUTS",
            "SNOW",
        )
}

object RoadSettingsLoader {
    const val CONFIG_VERSION = 2
    private const val LEGACY_CONFIG_VERSION = 1

    fun load(config: YamlConfig): RoadSettings {
        val problems = mutableListOf<String>()
        val version = integer(config, "config-version", CONFIG_VERSION, problems)
        if (version !in LEGACY_CONFIG_VERSION..CONFIG_VERSION) {
            problems += "roads.yml config-version must be $LEGACY_CONFIG_VERSION or $CONFIG_VERSION (found $version)"
        }
        val legacy = version == LEGACY_CONFIG_VERSION

        val maxBlocks = versionedInteger(config, "limits.max-planned-blocks", if (legacy) 256 else 2048, legacy, problems)
        val maximumPlan = if (legacy) 1024 else 4096
        if (maxBlocks !in 1..maximumPlan) problems += "limits.max-planned-blocks must be between 1 and $maximumPlan"
        val expiry = versionedInteger(config, "limits.preview-expiry-seconds", if (legacy) 300 else 600, legacy, problems)
        if (expiry !in 10..3600) problems += "limits.preview-expiry-seconds must be between 10 and 3600"
        val depth = versionedInteger(config, "limits.surface-search-depth", if (legacy) 2 else 8, legacy, problems)
        if (depth !in 0..16) problems += "limits.surface-search-depth must be between 0 and 16"
        val maxCrossSlope = versionedInteger(config, "limits.max-cross-slope-blocks", 1, legacy, problems)
        if (maxCrossSlope !in 0..4) problems += "limits.max-cross-slope-blocks must be between 0 and 4"
        val maxSegmentDistance =
            versionedInteger(config, "limits.max-segment-distance-blocks", if (legacy) 16 else 48, legacy, problems)
        val maximumSegmentDistance = if (legacy) 64 else 128
        if (maxSegmentDistance !in 1..maximumSegmentDistance) {
            problems += "limits.max-segment-distance-blocks must be between 1 and $maximumSegmentDistance"
        }
        val maxSegmentHeightDifference =
            versionedInteger(
                config,
                "limits.max-segment-height-difference-blocks",
                if (legacy) 4 else 8,
                legacy,
                problems,
            )
        if (maxSegmentHeightDifference !in 0..16) {
            problems += "limits.max-segment-height-difference-blocks must be between 0 and 16"
        }
        val enabled = boolean(config, "enabled", false, problems)
        val captureWhileFlying = if (legacy) false else boolean(config, "movement.capture-while-flying", true, problems)
        val smoothingEnabled = !legacy && boolean(config, "movement.smoothing.enabled", true, problems)
        val smoothingTolerance =
            if (legacy) 0.0 else decimal(config, "movement.smoothing.tolerance-blocks", 1.0, problems)
        if (!smoothingTolerance.isFinite() || smoothingTolerance !in 0.0..4.0) {
            problems += "movement.smoothing.tolerance-blocks must be between 0.0 and 4.0"
        }
        val smoothingMaxGradeRun =
            if (legacy) 0 else integer(config, "movement.smoothing.max-grade-run-blocks", 3, problems)
        if (smoothingMaxGradeRun !in 0..16) {
            problems += "movement.smoothing.max-grade-run-blocks must be between 0 and 16"
        }

        val clearanceHeight = if (legacy) 0 else integer(config, "clearance.height-blocks", 2, problems)
        if (clearanceHeight !in 0..4) problems += "clearance.height-blocks must be between 0 and 4"
        val rawClearable = if (legacy) emptyList() else config.stringList("clearance.materials")
        if (rawClearable.size > 128) problems += "clearance.materials must contain at most 128 entries"
        val parsedClearable = materials(rawClearable.take(128), "clearance.materials", problems)
        val unsafeClearable = parsedClearable.filterNot(RoadMaterialSafety::isClearableRoadObstruction)
        if (unsafeClearable.isNotEmpty()) {
            problems +=
                "clearance.materials contains unsafe road obstructions: ${unsafeClearable.joinToString { it.name }}"
        }
        val clearable = parsedClearable.filterTo(linkedSetOf(), RoadMaterialSafety::isClearableRoadObstruction)

        val replacementMode = replacementMode(config, legacy, problems)
        val rawReplaceable = config.stringList("replaceable-materials")
        if (rawReplaceable.size > 128) problems += "replaceable-materials must contain at most 128 entries"
        val replaceable =
            materials(rawReplaceable.take(128), "replaceable-materials", problems)
                .filterTo(linkedSetOf(), RoadMaterialSafety::isOrdinarySolid)
        if (replacementMode == RoadReplacementMode.ALLOWLIST && replaceable.isEmpty()) {
            problems += "replaceable-materials must contain at least one ordinary solid block material in allowlist mode"
        }
        val rawProtected = if (legacy) emptyList() else config.stringList("replacement.protected-materials")
        if (rawProtected.size > 128) problems += "replacement.protected-materials must contain at most 128 entries"
        val protected = materials(rawProtected.take(128), "replacement.protected-materials", problems).toSet()

        val returnReplacedBlocks =
            !legacy && boolean(config, "removed-blocks.return-to-survival-inventory", false, problems)
        val heightTransitionsEnabled =
            !legacy && boolean(config, "height-transitions.enabled", false, problems)
        val defaultTransition =
            if (legacy) {
                null
            } else {
                optionalPalette(
                    config,
                    "height-transitions.default-materials",
                    "height-transitions.default-material",
                    problems,
                    RoadMaterialSafety::isHeightTransition,
                )
            }

        val patterns = if (legacy) linkedMapOf() else patterns(config, problems)
        val profiles = profiles(config, legacy, patterns, problems)
        if (heightTransitionsEnabled && defaultTransition == null && profiles.values.any { it.heightTransitionPalette == null }) {
            problems += "height-transitions.default-materials is required when an enabled profile has no override"
        }

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
            maxCrossSlopeBlocks = maxCrossSlope,
            maxSegmentDistanceBlocks = maxSegmentDistance,
            maxSegmentHeightDifferenceBlocks = maxSegmentHeightDifference,
            captureWhileFlying = captureWhileFlying,
            smoothingEnabled = smoothingEnabled,
            smoothingToleranceBlocks = smoothingTolerance,
            smoothingMaxGradeRunBlocks = smoothingMaxGradeRun,
            clearanceHeightBlocks = clearanceHeight,
            clearableMaterials = clearable,
            replacementMode = replacementMode,
            replaceableMaterials = replaceable,
            protectedMaterials = protected,
            returnReplacedBlocksInSurvival = returnReplacedBlocks,
            heightTransitionsEnabled = heightTransitionsEnabled,
            defaultHeightTransitionPalette = defaultTransition,
            decorationPatterns = patterns,
            profiles = profiles,
        )
    }

    private fun replacementMode(
        config: YamlConfig,
        legacy: Boolean,
        problems: MutableList<String>,
    ): RoadReplacementMode {
        if (legacy) return RoadReplacementMode.ALLOWLIST
        return when (config.string("replacement.mode", "safe-solid").trim().lowercase()) {
            "allowlist" -> RoadReplacementMode.ALLOWLIST
            "safe-solid" -> RoadReplacementMode.SAFE_SOLID
            else -> {
                problems += "replacement.mode must be 'allowlist' or 'safe-solid'"
                RoadReplacementMode.SAFE_SOLID
            }
        }
    }

    private fun patterns(
        config: YamlConfig,
        problems: MutableList<String>,
    ): LinkedHashMap<String, RoadDecorationPattern> {
        val result = linkedMapOf<String, RoadDecorationPattern>()
        val names = config.keys("patterns")
        if (names.size > 32) problems += "patterns must contain at most 32 entries"
        names.take(32).forEach { rawName ->
            val name = normalizedName(rawName, "patterns", problems)
            if (result.containsKey(name)) problems += "patterns contains duplicate normalized name '$name'"
            val every = integer(config, "patterns.$rawName.every-blocks", 12, problems)
            if (every !in 1..256) problems += "patterns.$rawName.every-blocks must be between 1 and 256"
            val alternateSides = boolean(config, "patterns.$rawName.alternate-sides", false, problems)
            val rawPlacements = config.value("patterns.$rawName.placements") as? Collection<*>
            if (rawPlacements == null || rawPlacements.isEmpty() || rawPlacements.size > 16) {
                problems += "patterns.$rawName.placements must contain between 1 and 16 entries"
            }
            val placements =
                rawPlacements.orEmpty().take(16).mapIndexedNotNull { index, raw ->
                    parsePlacement(raw, "patterns.$rawName.placements[$index]", problems)
                }
            if (placements.map { Triple(it.forward, it.lateral, it.vertical) }.toSet().size != placements.size) {
                problems += "patterns.$rawName.placements contains duplicate relative coordinates"
            }
            result[name] = RoadDecorationPattern(name, every, alternateSides, placements)
        }
        return result
    }

    private fun parsePlacement(
        raw: Any?,
        path: String,
        problems: MutableList<String>,
    ): RoadDecorationPlacement? {
        val map = raw as? Map<*, *> ?: run {
            problems += "$path must be a map"
            return null
        }
        val forward = integerValue(map["forward"], 0, "$path.forward", problems)
        val lateral = integerValue(map["lateral"], 0, "$path.lateral", problems)
        val vertical = integerValue(map["vertical"], 1, "$path.vertical", problems)
        if (forward !in -8..8) problems += "$path.forward must be between -8 and 8"
        if (lateral !in -16..16) problems += "$path.lateral must be between -16 and 16"
        if (vertical !in 1..16) problems += "$path.vertical must be between 1 and 16"
        val rawPalette = map["materials"] ?: map["material"]
        val palette = materialPalette(rawPalette, "$path.materials", problems, RoadMaterialSafety::isDecoration) ?: return null
        return RoadDecorationPlacement(forward, lateral, vertical, palette)
    }

    private fun profiles(
        config: YamlConfig,
        legacy: Boolean,
        patterns: Map<String, RoadDecorationPattern>,
        problems: MutableList<String>,
    ): LinkedHashMap<String, RoadProfile> {
        val result = linkedMapOf<String, RoadProfile>()
        val names = if (legacy) config.explicitKeys("profiles") else config.keys("profiles")
        if (names.size > 32) problems += "profiles must contain at most 32 entries"
        names.take(32).forEach { rawName ->
            val name = normalizedName(rawName, "profiles", problems)
            if (result.containsKey(name)) problems += "profiles contains duplicate normalized name '$name'"
            val rawLanes = config.value("profiles.$rawName.lanes") as? Collection<*>
            if (rawLanes == null || rawLanes.size !in 1..7 || rawLanes.size % 2 == 0) {
                problems += "profiles.$rawName.lanes must contain an odd number of palettes between 1 and 7"
            }
            val lanes =
                rawLanes.orEmpty().take(7).mapIndexedNotNull { index, raw ->
                    materialPalette(raw, "profiles.$rawName.lanes[$index]", problems, RoadMaterialSafety::isOrdinarySolid)
                }
            val transition =
                if (legacy) {
                    null
                } else {
                    optionalPalette(
                        config,
                        "profiles.$rawName.height-transition-materials",
                        "profiles.$rawName.height-transition-material",
                        problems,
                        RoadMaterialSafety::isHeightTransition,
                    )
                }
            val decorationNames = if (legacy) emptyList() else config.stringList("profiles.$rawName.patterns")
            if (decorationNames.size > 8) problems += "profiles.$rawName.patterns must contain at most 8 entries"
            val decorations =
                decorationNames.take(8).mapNotNull { rawPattern ->
                    val patternName = rawPattern.trim().lowercase()
                    patterns[patternName].also { pattern ->
                        if (pattern == null) problems += "profiles.$rawName.patterns references unknown pattern '$patternName'"
                    }
                }
            val radius = (rawLanes?.size ?: lanes.size) / 2
            decorations.forEach { pattern ->
                if (pattern.placements.any { abs(it.lateral) <= radius }) {
                    problems += "profiles.$rawName pattern '${pattern.name}' must place every element outside the road lanes"
                }
            }
            result[name] = RoadProfile(name, lanes, transition, decorations)
        }
        if (result.isEmpty()) problems += "profiles must define at least one road profile"
        return result
    }

    private fun optionalPalette(
        config: YamlConfig,
        pluralPath: String,
        singularPath: String,
        problems: MutableList<String>,
        materialAllowed: (Material) -> Boolean,
    ): RoadMaterialPalette? {
        val raw =
            when {
                config.existsExplicitly(pluralPath) -> config.value(pluralPath)
                config.existsExplicitly(singularPath) -> config.value(singularPath)
                config.value(pluralPath) != null -> config.value(pluralPath)
                else -> config.value(singularPath)
            } ?: return null
        if (raw is String && raw.isBlank()) return null
        return materialPalette(raw, pluralPath, problems, materialAllowed)
    }

    private fun materialPalette(
        raw: Any?,
        path: String,
        problems: MutableList<String>,
        materialAllowed: (Material) -> Boolean,
    ): RoadMaterialPalette? {
        if (raw is String) {
            val material = material(raw, path, problems) ?: return null
            if (!materialAllowed(material)) {
                problems += "$path uses unsafe material '${material.name}'"
                return null
            }
            return RoadMaterialPalette.single(material)
        }
        val map = raw as? Map<*, *> ?: run {
            problems += "$path must be a material name or percentage map"
            return null
        }
        if (map.isEmpty() || map.size > 16) {
            problems += "$path must contain between 1 and 16 material percentages"
            return null
        }
        val entries =
            map.entries.mapNotNull { (rawMaterial, rawPercentage) ->
                val material = material(rawMaterial?.toString().orEmpty(), path, problems) ?: return@mapNotNull null
                if (!materialAllowed(material)) {
                    problems += "$path uses unsafe material '${material.name}'"
                    return@mapNotNull null
                }
                val percentage = integerValue(rawPercentage, -1, "$path.${material.name}", problems)
                if (percentage !in 1..100) {
                    problems += "$path.${material.name} must be between 1 and 100"
                    return@mapNotNull null
                }
                WeightedRoadMaterial(material, percentage)
            }
        if (entries.map(WeightedRoadMaterial::material).distinct().size != entries.size) {
            problems += "$path contains duplicate materials"
        }
        if (entries.sumOf(WeightedRoadMaterial::percentage) != 100) {
            problems += "$path percentages must total 100"
            return null
        }
        return RoadMaterialPalette(entries)
    }

    private fun normalizedName(
        rawName: String,
        owner: String,
        problems: MutableList<String>,
    ): String {
        val name = rawName.trim().lowercase()
        if (name.length > 32 || !PROFILE_NAME.matches(name)) {
            problems += "$owner.$rawName must use at most 32 lowercase letters, digits, underscores, or hyphens"
        }
        return name
    }

    private fun material(
        raw: String,
        path: String,
        problems: MutableList<String>,
    ): Material? {
        val name = raw.trim().uppercase()
        return Material.getMaterial(name).also { material ->
            if (material == null) problems += "$path uses unknown material '$name'"
        }
    }

    private fun materials(
        values: List<String>,
        path: String,
        problems: MutableList<String>,
    ): List<Material> = values.mapNotNull { material(it, path, problems) }

    private fun integer(
        config: YamlConfig,
        path: String,
        default: Int,
        problems: MutableList<String>,
    ): Int = integerValue(config.value(path), default, path, problems)

    private fun versionedInteger(
        config: YamlConfig,
        path: String,
        default: Int,
        explicitOnly: Boolean,
        problems: MutableList<String>,
    ): Int =
        if (explicitOnly && !config.existsExplicitly(path)) {
            default
        } else {
            integer(config, path, default, problems)
        }

    private fun integerValue(
        raw: Any?,
        default: Int,
        path: String,
        problems: MutableList<String>,
    ): Int {
        if (raw == null) return default
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

    private fun decimal(
        config: YamlConfig,
        path: String,
        default: Double,
        problems: MutableList<String>,
    ): Double {
        val raw = config.value(path) ?: return default
        val value =
            when (raw) {
                is Number -> raw.toDouble().takeIf(Double::isFinite)
                is String -> raw.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                else -> null
            }
        if (value == null) problems += "$path must be a finite number"
        return value ?: default
    }

    private val PROFILE_NAME = Regex("[a-z0-9_-]+")
}
