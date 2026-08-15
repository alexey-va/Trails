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
    })
