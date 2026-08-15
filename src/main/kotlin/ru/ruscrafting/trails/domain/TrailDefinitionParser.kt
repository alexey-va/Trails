package ru.ruscrafting.trails.domain

class TrailDefinitionException(
    val problems: List<String>,
) : IllegalArgumentException(problems.joinToString(separator = "\n"))

class TrailDefinitionParser(
    private val materialExists: (String) -> Boolean,
) {
    fun parse(entries: Map<String, String>): List<TrailDefinition> {
        val problems = mutableListOf<String>()
        val definitions =
            entries.mapNotNull { (rawName, serialized) ->
                parseOne(rawName, serialized, problems)
            }
        if (definitions.isEmpty()) problems += "Trails must contain at least one valid definition"
        if (problems.isNotEmpty()) throw TrailDefinitionException(problems)
        return definitions
    }

    private fun parseOne(
        rawName: String,
        serialized: String,
        problems: MutableList<String>,
    ): TrailDefinition? {
        val name = rawName.trim()
        if (name.isEmpty() || ':' in name) {
            problems += "Trail name '$rawName' must be non-blank and must not contain ':'"
            return null
        }
        val rawStages = serialized.split('>').map(String::trim).filter(String::isNotEmpty)
        if (rawStages.isEmpty()) {
            problems += "Trail '$name' must contain at least one stage"
            return null
        }
        val stages =
            rawStages.mapIndexedNotNull { index, rawStage ->
                parseStage(name, index, rawStage, problems)
            }
        if (stages.size != rawStages.size) return null
        return TrailDefinition(name, stages)
    }

    private fun parseStage(
        trailName: String,
        index: Int,
        rawStage: String,
        problems: MutableList<String>,
    ): TrailStage? {
        val fields = rawStage.split(':').map(String::trim)
        if (fields.size !in 3..4) {
            problems += "Trail '$trailName' stage ${index + 1} must use MATERIAL:WALKS:CHANCE[:SPEED]"
            return null
        }
        val material = fields[0].uppercase()
        val walks = fields[1].toIntOrNull()
        val chance = fields[2].toDoubleOrNull()
        val speed = fields.getOrNull(3)?.toDoubleOrNull() ?: 1.0
        var valid = true
        if (!materialExists(material)) {
            problems += "Trail '$trailName' stage ${index + 1} uses unknown material '$material'"
            valid = false
        }
        if (walks == null || walks <= 0) {
            problems += "Trail '$trailName' stage ${index + 1} walks must be a positive integer"
            valid = false
        }
        if (chance == null || chance !in 0.0..100.0) {
            problems += "Trail '$trailName' stage ${index + 1} chance must be between 0 and 100"
            valid = false
        }
        if (speed !in 0.0..5.0) {
            problems += "Trail '$trailName' stage ${index + 1} speed must be between 0 and 5"
            valid = false
        }
        if (!valid) return null
        return TrailStage(trailName, index, material, walks!!, chance!!, speed)
    }
}
