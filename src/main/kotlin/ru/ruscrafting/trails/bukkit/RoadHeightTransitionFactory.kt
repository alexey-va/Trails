package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import ru.ruscrafting.trails.domain.RoadPoint
import kotlin.math.abs

internal object RoadHeightTransitionFactory {
    fun create(
        material: Material,
        highSide: BlockFace,
    ): BlockData {
        require(highSide in CARDINAL_FACES) { "Road transition high side must be cardinal" }
        val data = material.createBlockData()
        when (data) {
            is Stairs -> {
                // BlockData facing is the direction in which a bottom stair ascends.
                data.facing = highSide
                data.half = Bisected.Half.BOTTOM
                data.shape = Stairs.Shape.STRAIGHT
                data.isWaterlogged = false
            }
            is Slab -> {
                data.type = Slab.Type.BOTTOM
                data.isWaterlogged = false
            }
            else -> throw IllegalArgumentException("Height transition material must be stairs or a slab")
        }
        return data
    }

    private val CARDINAL_FACES = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

    internal fun travelFace(from: RoadPoint, to: RoadPoint): BlockFace {
        val xDelta = to.x - from.x
        val zDelta = to.z - from.z
        require(xDelta != 0 || zDelta != 0) { "Road transition direction cannot be stationary" }
        return if (abs(xDelta) > abs(zDelta)) {
            if (xDelta > 0) BlockFace.EAST else BlockFace.WEST
        } else {
            if (zDelta > 0) BlockFace.SOUTH else BlockFace.NORTH
        }
    }

    /**
     * Keeps every stair in a transition row aligned with the owning road segment. At a turn, an
     * inside lane can move sideways even though the road continues forward; using that lane-local
     * delta would rotate one stair across the rest of the row.
     */
    internal fun transitionTravelFace(
        from: RoadPoint,
        to: RoadPoint,
        headingFrom: RoadPoint,
        headingTo: RoadPoint,
    ): BlockFace {
        require(from != to || headingFrom != headingTo) { "Road transition direction cannot be stationary" }
        return travelFace(headingFrom, headingTo)
    }
}
