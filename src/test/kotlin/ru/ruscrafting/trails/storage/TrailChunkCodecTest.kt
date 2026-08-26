package ru.ruscrafting.trails.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.trails.domain.TrailIdentity

class TrailChunkCodecTest :
    FreeSpec({
        "round-trips a deterministic compact chunk payload" {
            val states =
                linkedMapOf(
                    TrailBlockPosition.pack(15, -64, 0) to TrailBlockState(TrailIdentity("DirtPath", 3), 17),
                    TrailBlockPosition.pack(0, 319, 15) to TrailBlockState(null, 0),
                    TrailBlockPosition.pack(4, 72, 9) to TrailBlockState(TrailIdentity("ForestWalk", 1), 2),
                )

            val first = TrailChunkCodec.encode(states)
            val second = TrailChunkCodec.encode(states.entries.reversed().associate { it.toPair() })

            first.contentEquals(second) shouldBe true
            TrailChunkCodec.decode(first) shouldBe states
        }

        "rejects a payload whose checksum no longer matches" {
            val encoded =
                TrailChunkCodec.encode(
                    mapOf(TrailBlockPosition.pack(1, 64, 2) to TrailBlockState(TrailIdentity("DirtPath", 0), 4)),
                )
            encoded[encoded.lastIndex - Int.SIZE_BYTES] = (encoded[encoded.lastIndex - Int.SIZE_BYTES].toInt() xor 0x40).toByte()

            shouldThrow<TrailChunkFormatException> { TrailChunkCodec.decode(encoded) }
        }

        "bounds persisted trail identity names" {
            val oversizedName = "x".repeat(TrailChunkCodec.MAX_TRAIL_NAME_BYTES + 1)

            shouldThrow<IllegalArgumentException> {
                TrailChunkCodec.encode(
                    mapOf(TrailBlockPosition.pack(0, 64, 0) to TrailBlockState(TrailIdentity(oversizedName, 0), 0)),
                )
            }
        }

        "packs every default Paper build height without collisions" {
            val packed =
                buildSet {
                    for (y in -64 until 320) {
                        for (x in 0..15) {
                            for (z in 0..15) add(TrailBlockPosition.pack(x, y, z))
                        }
                    }
                }

            packed.size shouldBe 16 * 16 * 384
            packed.forEach { value ->
                TrailBlockPosition.pack(
                    TrailBlockPosition.localX(value),
                    TrailBlockPosition.y(value),
                    TrailBlockPosition.localZ(value),
                ) shouldBe value
            }
        }
    })
