package ru.ruscrafting.trails.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class RoadGeometryTest :
    FreeSpec({
        "a three-wide cardinal segment is continuous and lane-aware" {
            val cells = RoadGeometry.segment(RoadPoint(0, 0), RoadPoint(2, 0), 3)

            cells.size shouldBe 9
            cells.shouldContainAll(
                RoadCell(0, -1, -1),
                RoadCell(1, 0, 0),
                RoadCell(2, 1, 1),
            )
        }

        "a diagonal segment uses contiguous rows instead of leaving holes" {
            val cells = RoadGeometry.segment(RoadPoint(0, 0), RoadPoint(2, 2), 3)

            cells.size shouldBe 9
            cells.map { it.x to it.z }.toSet() shouldBe
                setOf(
                    -1 to 0,
                    0 to 0,
                    1 to 0,
                    0 to 1,
                    1 to 1,
                    2 to 1,
                    1 to 2,
                    2 to 2,
                    3 to 2,
                )
        }

        "left and right lanes follow travel direction" {
            RoadGeometry.segment(RoadPoint(1, 0), RoadPoint(0, 0), 3).first() shouldBe
                RoadCell(1, 1, -1)
            RoadGeometry.segment(RoadPoint(0, 0), RoadPoint(0, 1), 3).first() shouldBe
                RoadCell(1, 0, -1)
        }

        "an east-dominant diagonal keeps its width perpendicular to travel" {
            val rows = RoadGeometry.rows(RoadPoint(0, 0), RoadPoint(4, 1), 3)

            rows.forEach { row ->
                row.cells.map(RoadCell::x).toSet() shouldBe setOf(row.center.x)
                row.cells.map(RoadCell::z).toSet() shouldBe
                    setOf(row.center.z - 1, row.center.z, row.center.z + 1)
            }
        }

        "smoothing removes one-block steering noise while preserving endpoints" {
            val points =
                listOf(
                    RoadPoint(0, 0),
                    RoadPoint(1, 1),
                    RoadPoint(2, 0),
                    RoadPoint(3, 1),
                    RoadPoint(4, 0),
                )

            RoadGeometry.smooth(points, toleranceBlocks = 1.0) shouldBe
                listOf(RoadPoint(0, 0), RoadPoint(4, 0))
        }

        "smoothing keeps a deliberate corner beyond the configured tolerance" {
            val points =
                listOf(
                    RoadPoint(0, 0),
                    RoadPoint(4, 0),
                    RoadPoint(4, 4),
                )

            RoadGeometry.smooth(points, toleranceBlocks = 1.0) shouldBe points
        }

        "smoothing zero preserves the captured route exactly" {
            val points = listOf(RoadPoint(0, 0), RoadPoint(1, 1), RoadPoint(2, 0))

            RoadGeometry.smooth(points, toleranceBlocks = 0.0) shouldBe points
        }

        "a disconnected landing row uses only the endpoint even for a huge jump" {
            val landing = RoadPoint(30_000_000, 30_000_000)

            RoadGeometry.row(landing, RoadPoint(-30_000_000, -30_000_000), landing, 3).cells.size shouldBe 3
        }

        "grade smoothing removes only isolated one-block bumps and depressions" {
            RoadGeometry.smoothIsolatedGrades(listOf(64, 65, 64, 63, 64)) shouldBe
                listOf(64, 64, 64, 64, 64)
            RoadGeometry.smoothIsolatedGrades(listOf(64, 65, 65, 66)) shouldBe
                listOf(64, 65, 65, 66)
        }

        "grade smoothing removes a short plateau bounded by the same road height" {
            RoadGeometry.smoothIsolatedGrades(listOf(64, 65, 65, 65, 64), maxRunLength = 3) shouldBe
                listOf(64, 64, 64, 64, 64)
            RoadGeometry.smoothIsolatedGrades(listOf(64, 63, 63, 64), maxRunLength = 2) shouldBe
                listOf(64, 64, 64, 64)
            RoadGeometry.smoothIsolatedGrades(listOf(64, 65, 65, 65, 65, 64), maxRunLength = 3) shouldBe
                listOf(64, 65, 65, 65, 65, 64)
        }
    })
