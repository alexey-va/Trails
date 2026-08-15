package ru.ruscrafting.trails.domain

class StructuredTrailDefinitionParser(
    private val materialExists: (String) -> Boolean,
) {
    fun parse(rawDefinitions: Any?): List<TrailDefinition> {
        val problems = mutableListOf<String>()
        val definitionsMap = rawDefinitions.stringMapOrNull()
        if (definitionsMap == null) {
            throw TrailDefinitionException(listOf("trails must be a map of trail definitions"))
        }

        val definitions =
            definitionsMap.mapNotNull { (name, rawDefinition) ->
                parseDefinition(name, rawDefinition, problems)
            }
        if (definitions.isEmpty()) problems += "trails must contain at least one valid definition"
        if (problems.isNotEmpty()) throw TrailDefinitionException(problems)
        return definitions
    }

    private fun parseDefinition(
        rawName: String,
        rawDefinition: Any?,
        problems: MutableList<String>,
    ): TrailDefinition? {
        val path = "trails.$rawName"
        val name = rawName.trim()
        if (name.isEmpty() || ':' in name) {
            problems += "$path must use a non-blank id without ':'"
            return null
        }
        val definition = rawDefinition.stringMapOrNull()
        if (definition == null) {
            problems += "$path must be a map"
            return null
        }

        val rawWeight = definition["selection-weight"]
        val weight = rawWeight.intOrNull() ?: 1
        if ((rawWeight != null && rawWeight.intOrNull() == null) || weight <= 0) {
            problems += "$path.selection-weight must be a positive integer"
        }

        val rawStages = definition["stages"] as? Collection<*>
        if (rawStages.isNullOrEmpty()) {
            problems += "$path.stages must contain at least one stage"
            return null
        }
        val stages =
            rawStages.mapIndexedNotNull { index, rawStage ->
                parseStage(name, index, rawStages.size, rawStage, problems)
            }
        if (stages.size != rawStages.size || (rawWeight != null && rawWeight.intOrNull() == null) || weight <= 0) return null
        return TrailDefinition(name, stages, weight)
    }

    private fun parseStage(
        trailName: String,
        index: Int,
        stageCount: Int,
        rawStage: Any?,
        problems: MutableList<String>,
    ): TrailStage? {
        val path = "trails.$trailName.stages[$index]"
        val stage = rawStage.stringMapOrNull()
        if (stage == null) {
            problems += "$path must be a map"
            return null
        }

        val material = stage["material"]?.toString()?.trim()?.uppercase().orEmpty()
        val terminal = index == stageCount - 1
        val rawRequiredWalks = stage["required-walks"]
        val rawChancePercent = stage["count-chance-percent"]
        val rawSpeedMultiplier = stage["speed-multiplier"]
        val requiredWalks = rawRequiredWalks.intOrNull() ?: if (terminal && rawRequiredWalks == null) 1 else null
        val chancePercent = rawChancePercent.doubleOrNull() ?: if (terminal && rawChancePercent == null) 100.0 else null
        val speedMultiplier = rawSpeedMultiplier.doubleOrNull() ?: if (rawSpeedMultiplier == null) 1.0 else null
        var valid = true

        if (material.isEmpty() || !materialExists(material)) {
            problems += "$path.material uses unknown material '$material'"
            valid = false
        }
        if (requiredWalks == null || requiredWalks <= 0) {
            problems += "$path.required-walks must be a positive integer${if (terminal) " when specified" else ""}"
            valid = false
        }
        if (chancePercent == null || chancePercent !in 0.0..100.0) {
            problems += "$path.count-chance-percent must be between 0 and 100"
            valid = false
        }
        if (speedMultiplier == null || speedMultiplier !in 0.0..5.0) {
            problems += "$path.speed-multiplier must be between 0 and 5"
            valid = false
        }
        if (!valid) return null
        return TrailStage(
            trailName = trailName,
            index = index,
            material = material,
            requiredWalks = requiredWalks!!,
            chancePercent = chancePercent!!,
            speedMultiplier = speedMultiplier!!,
        )
    }

    private fun Any?.stringMapOrNull(): Map<String, Any?>? {
        val map = this as? Map<*, *> ?: return null
        if (map.keys.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, Any?>
    }

    private fun Any?.intOrNull(): Int? =
        when (this) {
            is Number -> {
                val value = toDouble()
                value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()
            }
            is String -> trim().toIntOrNull()
            else -> null
        }

    private fun Any?.doubleOrNull(): Double? =
        when (this) {
            is Number -> toDouble()
            is String -> trim().toDoubleOrNull()
            else -> null
        }
}
