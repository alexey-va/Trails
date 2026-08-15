package ru.ruscrafting.trails.domain

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class TrailIdentityTest :
    FreeSpec({
        "round trips the legacy name and index representation" {
            val identity = TrailIdentity("DirtPath", 3)
            TrailIdentity.parse(identity.serialize()) shouldBe identity
        }

        "rejects malformed and negative legacy values" {
            TrailIdentity.parse(null) shouldBe null
            TrailIdentity.parse("") shouldBe null
            TrailIdentity.parse("DirtPath") shouldBe null
            TrailIdentity.parse("DirtPath:nope") shouldBe null
            TrailIdentity.parse("DirtPath:-1") shouldBe null
            TrailIdentity.parse(":1") shouldBe null
        }
    })
