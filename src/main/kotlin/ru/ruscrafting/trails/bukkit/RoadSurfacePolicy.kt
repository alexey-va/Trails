package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.Waterlogged
import ru.ruscrafting.trails.config.RoadMaterialSafety
import ru.ruscrafting.trails.config.RoadReplacementMode
import ru.ruscrafting.trails.config.RoadSettings

internal class RoadSurfacePolicy(
    private val settings: RoadSettings,
) {
    fun canReplace(block: Block): Boolean {
        val material = block.type
        if (!RoadMaterialSafety.isOrdinarySolid(material) || material in settings.protectedMaterials) return false
        if (block.state is TileState) return false
        if ((block.blockData as? Waterlogged)?.isWaterlogged == true) return false
        return when (settings.replacementMode) {
            RoadReplacementMode.ALLOWLIST -> material in settings.paintableMaterials
            RoadReplacementMode.SAFE_SOLID -> true
        }
    }

    fun hasWalkableTop(block: Block): Boolean {
        if (!block.type.isSolid || block.type.isAir) return false
        val above = block.getRelative(0, 1, 0)
        val aboveMaterial = above.type
        return aboveMaterial.isAir ||
            (!aboveMaterial.isSolid &&
                aboveMaterial != Material.WATER &&
                aboveMaterial != Material.LAVA &&
                above.state !is TileState)
    }
}
