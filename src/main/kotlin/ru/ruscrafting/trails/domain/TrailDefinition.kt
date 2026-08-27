package ru.ruscrafting.trails.domain

data class TrailStage(
    val trailName: String,
    val index: Int,
    val material: String,
    val requiredWalks: Int,
    val chancePercent: Double,
    val speedMultiplier: Double,
) {
    init {
        require(trailName.isNotBlank()) { "trailName must not be blank" }
        require(index >= 0) { "index must be non-negative" }
        require(material.isNotBlank()) { "material must not be blank" }
        require(requiredWalks > 0) { "requiredWalks must be positive" }
        require(chancePercent in 0.0..100.0) { "chancePercent must be between 0 and 100" }
        require(speedMultiplier in 0.0..5.0) { "speedMultiplier must be between 0 and 5" }
    }

    val identity: TrailIdentity = TrailIdentity(trailName, index)
}

data class TrailDefinition(
    val name: String,
    val stages: List<TrailStage>,
    val selectionWeight: Int = 1,
    val conditions: TrailConditions = TrailConditions(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(':' !in name) { "name must not contain ':'" }
        require(stages.isNotEmpty()) { "stages must not be empty" }
        require(selectionWeight > 0) { "selectionWeight must be positive" }
        require(stages.map { it.index } == stages.indices.toList()) { "stage indexes must be contiguous" }
        require(stages.all { it.trailName == name }) { "every stage must belong to $name" }
    }
}

data class TrailEnvironment(
    val world: String,
    val biome: String,
)

data class TrailConditions(
    val worlds: Set<String> = emptySet(),
    val biomes: Set<String> = emptySet(),
) {
    val constrained: Boolean = worlds.isNotEmpty() || biomes.isNotEmpty()

    fun matches(environment: TrailEnvironment): Boolean =
        (worlds.isEmpty() || worlds.any { it.equals(environment.world, ignoreCase = true) }) &&
            (biomes.isEmpty() || environment.biome.lowercase() in biomes)
}
