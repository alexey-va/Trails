package ru.ruscrafting.trails.domain

import kotlin.random.Random

class TrailCatalog(
    definitions: List<TrailDefinition>,
    private val strictLinks: Boolean,
    private val chooseIndex: (Int) -> Int = { Random.nextInt(it) },
) {
    private val byIdentity = definitions.flatMap { it.stages }.associateBy { it.identity }
    private val byMaterial = definitions.flatMap { it.stages }.groupBy { it.material }
    private val startsByMaterial = definitions.groupBy { it.stages.first().material }

    init {
        require(definitions.isNotEmpty()) { "definitions must not be empty" }
    }

    fun resolve(
        material: String,
        storedIdentity: TrailIdentity?,
    ): TrailStage? {
        val normalized = material.uppercase()
        storedIdentity?.let { identity ->
            byIdentity[identity]?.takeIf { it.material == normalized }?.let { return it }
            byMaterial[normalized]
                ?.filter { it.trailName == identity.trailName }
                ?.minByOrNull { it.index }
                ?.let { return it }
        }

        val candidates = byMaterial[normalized].orEmpty()
        val starts = startsByMaterial[normalized].orEmpty()
        if (starts.isNotEmpty()) return chooseWeighted(starts).stages.first()
        if (strictLinks) return null
        return candidates.minWithOrNull(compareBy(TrailStage::index, TrailStage::trailName))
    }

    fun next(stage: TrailStage): TrailStage? = byIdentity[TrailIdentity(stage.trailName, stage.index + 1)]

    fun previous(stage: TrailStage): TrailStage? =
        if (stage.index == 0) null else byIdentity[TrailIdentity(stage.trailName, stage.index - 1)]

    fun allStages(): Collection<TrailStage> = byIdentity.values

    private fun chooseWeighted(definitions: List<TrailDefinition>): TrailDefinition {
        val totalWeight = definitions.sumOf(TrailDefinition::selectionWeight)
        var roll = chooseIndex(totalWeight)
        require(roll in 0 until totalWeight) { "chooseIndex returned $roll for total weight $totalWeight" }
        for (definition in definitions) {
            if (roll < definition.selectionWeight) return definition
            roll -= definition.selectionWeight
        }
        error("Could not select a trail definition")
    }
}
