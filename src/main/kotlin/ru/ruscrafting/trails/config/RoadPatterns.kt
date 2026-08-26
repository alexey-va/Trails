package ru.ruscrafting.trails.config

import org.bukkit.Material

data class WeightedRoadMaterial(
    val material: Material,
    val percentage: Int,
)

data class RoadMaterialPalette(
    val entries: List<WeightedRoadMaterial>,
) {
    init {
        require(entries.isNotEmpty()) { "Road material palette cannot be empty" }
        require(entries.all { it.percentage in 1..100 }) { "Road material percentages must be between 1 and 100" }
        require(entries.map(WeightedRoadMaterial::material).distinct().size == entries.size) {
            "Road material palette cannot contain duplicate materials"
        }
        require(entries.sumOf(WeightedRoadMaterial::percentage) == 100) { "Road material percentages must total 100" }
    }

    val materials: Set<Material> = entries.mapTo(linkedSetOf(), WeightedRoadMaterial::material)

    fun select(sample: Long): Material {
        val bucket = Math.floorMod(sample, 100L).toInt()
        var upperBound = 0
        entries.forEach { entry ->
            upperBound += entry.percentage
            if (bucket < upperBound) return entry.material
        }
        return entries.last().material
    }

    companion object {
        fun single(material: Material): RoadMaterialPalette = RoadMaterialPalette(listOf(WeightedRoadMaterial(material, 100)))
    }
}

data class RoadDecorationPlacement(
    val forward: Int,
    val lateral: Int,
    val vertical: Int,
    val palette: RoadMaterialPalette,
)

data class RoadDecorationPattern(
    val name: String,
    val everyBlocks: Int,
    val alternateSides: Boolean,
    val placements: List<RoadDecorationPlacement>,
)

data class RoadProfile(
    val name: String,
    val lanePalettes: List<RoadMaterialPalette>,
    val heightTransitionPalette: RoadMaterialPalette? = null,
    val decorationPatterns: List<RoadDecorationPattern> = emptyList(),
) {
    val width: Int = lanePalettes.size
    val laneMaterials: Set<Material> = lanePalettes.flatMapTo(linkedSetOf(), RoadMaterialPalette::materials)
}
