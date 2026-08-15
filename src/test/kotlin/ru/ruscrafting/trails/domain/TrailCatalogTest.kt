package ru.ruscrafting.trails.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TrailCatalogTest :
    FreeSpec({
        val first = TrailStage("Dirt", 0, "DIRT", 2, 100.0, 1.0)
        val second = TrailStage("Dirt", 1, "PATH", 3, 100.0, 1.2)
        val alternate = TrailStage("Alternate", 0, "DIRT", 4, 100.0, 1.0)
        val definitions =
            listOf(
                TrailDefinition("Dirt", listOf(first, second)),
                TrailDefinition("Alternate", listOf(alternate)),
            )

        "uses stored identity when material still matches" {
            TrailCatalog(definitions, strictLinks = false).resolve("PATH", second.identity) shouldBe second
        }

        "chooses among multiple starting links through the injected chooser" {
            TrailCatalog(definitions, strictLinks = false, chooseIndex = { 1 }).resolve("DIRT", null) shouldBe alternate
        }

        "selection weights control the starting definition" {
            val weighted =
                listOf(
                    TrailDefinition("Dirt", listOf(first, second), selectionWeight = 3),
                    TrailDefinition("Alternate", listOf(alternate), selectionWeight = 1),
                )

            TrailCatalog(weighted, strictLinks = false, chooseIndex = { 2 }).resolve("DIRT", null) shouldBe first
            TrailCatalog(weighted, strictLinks = false, chooseIndex = { 3 }).resolve("DIRT", null) shouldBe alternate
        }

        "strict links reject a natural block that is only a later stage" {
            TrailCatalog(definitions, strictLinks = true).resolve("PATH", null) shouldBe null
        }

        "non-strict links retain legacy later-stage discovery" {
            TrailCatalog(definitions, strictLinks = false).resolve("PATH", null) shouldBe second
        }
    })
