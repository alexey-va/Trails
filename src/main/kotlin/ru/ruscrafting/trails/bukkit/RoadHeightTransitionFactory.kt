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
        from: RoadPoint,
        to: RoadPoint,
        ascending: Boolean,
    ): BlockData {
        val data = material.createBlockData()
        when (data) {
            is Stairs -> {
                data.facing = travelFace(from, to).let { if (ascending) it else it.oppositeFace }
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
}
