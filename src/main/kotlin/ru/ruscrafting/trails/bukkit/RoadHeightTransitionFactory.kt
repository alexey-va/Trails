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
     * Resolves the direction of one rasterized road step. Bresenham can move on both axes in a
     * single step even when one axis clearly dominates the road segment. Using that diagonal as
     * an arbitrary tie made isolated stair rows turn sideways. The segment heading breaks only
     * diagonal (or stationary inside-corner lane) ties; genuine cardinal steps remain unchanged.
     */
    internal fun transitionTravelFace(
        from: RoadPoint,
        to: RoadPoint,
        headingFrom: RoadPoint,
        headingTo: RoadPoint,
    ): BlockFace {
        val xDelta = to.x - from.x
        val zDelta = to.z - from.z
        return if (xDelta == 0 || zDelta == 0) {
            if (xDelta == 0 && zDelta == 0) travelFace(headingFrom, headingTo) else travelFace(from, to)
        } else {
            val headingX = headingTo.x - headingFrom.x
            val headingZ = headingTo.z - headingFrom.z
            if (abs(headingX) >= abs(headingZ)) {
                if (xDelta > 0) BlockFace.EAST else BlockFace.WEST
            } else {
                if (zDelta > 0) BlockFace.SOUTH else BlockFace.NORTH
            }
        }
    }
}
