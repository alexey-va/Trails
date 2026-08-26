package ru.ruscrafting.trails.domain

import kotlin.math.abs

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
    fun segment(
        from: RoadPoint,
        to: RoadPoint,
        width: Int,
    ): List<RoadCell> = rows(from, to, width).flatMap(RoadRow::cells)

    fun rows(
        from: RoadPoint,
        to: RoadPoint,
        width: Int,
    ): List<RoadRow> {
        require(width in 1..7 && width % 2 == 1) { "Road width must be odd and between 1 and 7" }
        val centers = bresenham(from, to)
        val xDelta = to.x - from.x
        val zDelta = to.z - from.z
        val offsetAlongX = abs(zDelta) >= abs(xDelta)
        val xDirection = xDelta.compareTo(0)
        val zDirection = zDelta.compareTo(0)
        val radius = width / 2
        return centers.map { center ->
            RoadRow(
                center,
                (-radius..radius).map { lane ->
                    if (offsetAlongX) RoadCell(center.x - lane * zDirection, center.z, lane)
                    else RoadCell(center.x, center.z + lane * xDirection, lane)
                },
            )
        }
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
}
