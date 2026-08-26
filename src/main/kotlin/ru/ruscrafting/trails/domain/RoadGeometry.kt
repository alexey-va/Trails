package ru.ruscrafting.trails.domain

import kotlin.math.abs
import kotlin.math.pow

data class RoadPoint(
    val x: Int,
    val z: Int,
)

data class RoadCell(
    val x: Int,
    val z: Int,
    val lane: Int,
)

data class RoadRow(
    val center: RoadPoint,
    val cells: List<RoadCell>,
)

object RoadGeometry {
    /**
     * Simplifies captured steering noise while keeping the first point, last point, and deliberate turns.
     * The returned points are always members of [points], so callers can retain metadata associated with anchors.
     */
    fun smooth(
        points: List<RoadPoint>,
        toleranceBlocks: Double,
    ): List<RoadPoint> {
        require(toleranceBlocks.isFinite() && toleranceBlocks in 0.0..4.0) {
            "Road smoothing tolerance must be finite and between 0.0 and 4.0"
        }
        val normalized = points.fold(mutableListOf<RoadPoint>()) { result, point ->
            if (result.lastOrNull() != point) result += point
            result
        }
        if (normalized.size <= 2 || toleranceBlocks == 0.0) return normalized

        val retained = BooleanArray(normalized.size)
        retained[0] = true
        retained[normalized.lastIndex] = true
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending += 0 to normalized.lastIndex
        val toleranceSquared = toleranceBlocks.pow(2)
        while (pending.isNotEmpty()) {
            val (start, end) = pending.removeLast()
            var farthestIndex = -1
            var farthestDistance = toleranceSquared
            for (index in start + 1 until end) {
                val distance = distanceSquared(normalized[index], normalized[start], normalized[end])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthestIndex = index
                }
            }
            if (farthestIndex >= 0) {
                retained[farthestIndex] = true
                pending += start to farthestIndex
                pending += farthestIndex to end
            }
        }
        return normalized.filterIndexed { index, _ -> retained[index] }
    }

    fun segment(
        from: RoadPoint,
        to: RoadPoint,
        width: Int,
    ): List<RoadCell> = rows(from, to, width).flatMap(RoadRow::cells)

    /** Removes short one-block bumps or depressions without flattening a sustained grade change. */
    fun smoothIsolatedGrades(
        heights: List<Int?>,
        maxAdjustmentBlocks: Int = 1,
        maxRunLength: Int = 1,
    ): List<Int?> {
        require(maxAdjustmentBlocks in 0..4) { "Road grade adjustment must be between 0 and 4" }
        require(maxRunLength in 0..16) { "Road grade run length must be between 0 and 16" }
        if (heights.size < 3 || maxAdjustmentBlocks == 0 || maxRunLength == 0) return heights.toList()
        val smoothed = heights.toMutableList()
        var start = 1
        while (start < heights.lastIndex) {
            val current = heights[start]
            if (current == null) {
                start++
                continue
            }
            var end = start
            while (end + 1 < heights.lastIndex && heights[end + 1] == current) end++
            val previous = heights[start - 1]
            val next = heights[end + 1]
            if (
                previous != null &&
                previous == next &&
                end - start + 1 <= maxRunLength &&
                abs(current - previous) <= maxAdjustmentBlocks
            ) {
                for (index in start..end) smoothed[index] = previous
            }
            start = end + 1
        }
        return smoothed
    }

    fun rows(
        from: RoadPoint,
        to: RoadPoint,
        width: Int,
    ): List<RoadRow> {
        require(width in 1..7 && width % 2 == 1) { "Road width must be odd and between 1 and 7" }
        require(from != to) { "Road segment direction cannot be stationary" }
        val centers = bresenham(from, to)
        return centers.map { center -> row(center, from, to, width) }
    }

    fun row(
        center: RoadPoint,
        directionFrom: RoadPoint,
        directionTo: RoadPoint,
        width: Int,
    ): RoadRow {
        require(width in 1..7 && width % 2 == 1) { "Road width must be odd and between 1 and 7" }
        require(directionFrom != directionTo) { "Road row direction cannot be stationary" }
        val xDelta = directionTo.x - directionFrom.x
        val zDelta = directionTo.z - directionFrom.z
        val offsetAlongX = abs(zDelta) >= abs(xDelta)
        val xDirection = xDelta.compareTo(0)
        val zDirection = zDelta.compareTo(0)
        val radius = width / 2
        return RoadRow(
            center,
            (-radius..radius).map { lane ->
                if (offsetAlongX) RoadCell(center.x - lane * zDirection, center.z, lane)
                else RoadCell(center.x, center.z + lane * xDirection, lane)
            },
        )
    }

    private fun bresenham(from: RoadPoint, to: RoadPoint): List<RoadPoint> =
        buildList {
            var x = from.x
            var z = from.z
            val dx = abs(to.x - from.x)
            val dz = abs(to.z - from.z)
            val sx = if (from.x < to.x) 1 else -1
            val sz = if (from.z < to.z) 1 else -1
            var error = dx - dz
            while (true) {
                add(RoadPoint(x, z))
                if (x == to.x && z == to.z) break
                val doubled = error * 2
                if (doubled > -dz) {
                    error -= dz
                    x += sx
                }
                if (doubled < dx) {
                    error += dx
                    z += sz
                }
            }
        }

    private fun distanceSquared(
        point: RoadPoint,
        start: RoadPoint,
        end: RoadPoint,
    ): Double {
        val xDelta = (end.x - start.x).toDouble()
        val zDelta = (end.z - start.z).toDouble()
        val lengthSquared = xDelta * xDelta + zDelta * zDelta
        if (lengthSquared == 0.0) {
            val x = (point.x - start.x).toDouble()
            val z = (point.z - start.z).toDouble()
            return x * x + z * z
        }
        val progress =
            (((point.x - start.x) * xDelta + (point.z - start.z) * zDelta) / lengthSquared)
                .coerceIn(0.0, 1.0)
        val projectedX = start.x + progress * xDelta
        val projectedZ = start.z + progress * zDelta
        val x = point.x - projectedX
        val z = point.z - projectedZ
        return x * x + z * z
    }
}
