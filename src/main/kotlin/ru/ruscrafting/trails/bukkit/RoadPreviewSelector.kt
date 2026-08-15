package ru.ruscrafting.trails.bukkit

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox

internal data class RoadPreviewSelection(
    val blockData: BlockData,
    val substituted: Boolean,
)

internal class RoadPreviewSelector(
    private val fullCube: (BlockData, Location) -> Boolean = { blockData, location ->
        isFullCube(blockData.getCollisionShape(location).boundingBoxes)
    },
) {
    fun select(
        target: BlockData,
        location: Location,
    ): RoadPreviewSelection {
        val useTarget = runCatching { fullCube(target, location) }.getOrDefault(false)
        return if (useTarget) {
            RoadPreviewSelection(target, false)
        } else {
            RoadPreviewSelection(Material.YELLOW_CONCRETE.createBlockData(), true)
        }
    }

    companion object {
        private const val EPSILON = 1.0e-7

        internal fun isFullCube(boxes: Collection<BoundingBox>): Boolean =
            boxes.any { box ->
                box.minX <= EPSILON &&
                    box.minY <= EPSILON &&
                    box.minZ <= EPSILON &&
                    box.maxX >= 1.0 - EPSILON &&
                    box.maxY >= 1.0 - EPSILON &&
                    box.maxZ >= 1.0 - EPSILON
            }
    }
}
