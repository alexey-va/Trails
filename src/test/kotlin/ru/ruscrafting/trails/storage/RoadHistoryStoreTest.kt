package ru.ruscrafting.trails.storage

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.trails.domain.TrailIdentity
import java.nio.file.Files
import java.util.UUID

class RoadHistoryStoreTest :
    FreeSpec({
        "persists exact block data and prior trail metadata for restart-safe undo" {
            val folder = Files.createTempDirectory("trails-road-history-")
            try {
                val player = UUID.randomUUID()
                val world = UUID.randomUUID()
                val expected =
                    linkedMapOf(
                        player to
                            RoadCommitRecord(
                                world,
                                1234L,
                                listOf(
                                    RoadBlockRecord(
                                        1,
                                        64,
                                        -2,
                                        "minecraft:grass_block[snowy=false]",
                                        "minecraft:dirt_path",
                                        TrailBlockState(TrailIdentity("DirtPath", 1), 7),
                                        TrailBlockState(TrailIdentity("DirtPath", 3), 0),
                                    ),
                                ),
                            ),
                    )
                val store = RoadHistoryStore(folder)

                store.save(expected)

                store.load() shouldBe expected
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "preserves decorative road state without a natural trail identity" {
            val folder = Files.createTempDirectory("trails-decorative-road-history-")
            try {
                val player = UUID.randomUUID()
                val expected =
                    linkedMapOf(
                        player to
                            RoadCommitRecord(
                                UUID.randomUUID(),
                                5678L,
                                listOf(
                                    RoadBlockRecord(
                                        4,
                                        70,
                                        9,
                                        "minecraft:grass_block[snowy=false]",
                                        "minecraft:stone_bricks",
                                        null,
                                        TrailBlockState(identity = null, walks = 0),
                                    ),
                                ),
                            ),
                    )
                val store = RoadHistoryStore(folder)

                store.save(expected)

                store.load() shouldBe expected
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "preserves an intentionally cleared block without an air PDC entry" {
            val folder = Files.createTempDirectory("trails-cleared-road-history-")
            try {
                val expected =
                    linkedMapOf(
                        UUID.randomUUID() to
                            RoadCommitRecord(
                                UUID.randomUUID(),
                                6789L,
                                listOf(
                                    RoadBlockRecord(
                                        2,
                                        65,
                                        3,
                                        "minecraft:short_grass",
                                        "minecraft:air",
                                        null,
                                        null,
                                    ),
                                ),
                            ),
                    )
                val store = RoadHistoryStore(folder)

                store.save(expected)

                store.load() shouldBe expected
            } finally {
                folder.toFile().deleteRecursively()
            }
        }

        "accepts only exact safe road-history coordinates and counters" {
            val folder = Files.createTempDirectory("trails-corrupt-road-history-")
            try {
                val valid = UUID.randomUUID()
                val fractionalCoordinate = UUID.randomUUID()
                val outOfRangeCoordinate = UUID.randomUUID()
                val outOfRangeCounter = UUID.randomUUID()
                val negativeCounter = UUID.randomUUID()
                val duplicatePosition = UUID.randomUUID()
                val world = UUID.randomUUID()
                Files.writeString(
                    folder.resolve("road-history.yml"),
                    """
                    config-version: 2
                    history:
                      $valid:
                        world: $world
                        committed-at: 1
                        blocks:
                          - x: -2147483648
                            y: 64
                            z: 2147483647
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            previous-present: true
                            after-present: true
                      $fractionalCoordinate:
                        world: $world
                        committed-at: 2
                        blocks:
                          - x: 1.5
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: 1
                      $outOfRangeCoordinate:
                        world: $world
                        committed-at: 3
                        blocks:
                          - x: 2147483648
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: 1
                      $outOfRangeCounter:
                        world: $world
                        committed-at: 4
                        blocks:
                          - x: 1
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: 2147483648
                      $negativeCounter:
                        world: $world
                        committed-at: 5
                        blocks:
                          - x: 1
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: -1
                      $duplicatePosition:
                        world: $world
                        committed-at: 6
                        blocks:
                          - x: 1
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: 1
                          - x: 1
                            y: 64
                            z: 1
                            before: minecraft:stone
                            after: minecraft:dirt_path
                            after-present: true
                            after-walks: 1
                    """.trimIndent(),
                )

                val expected =
                    linkedMapOf(
                        valid to
                            RoadCommitRecord(
                                world,
                                1L,
                                listOf(
                                    RoadBlockRecord(
                                        Int.MIN_VALUE,
                                        64,
                                        Int.MAX_VALUE,
                                        "minecraft:stone",
                                        "minecraft:dirt_path",
                                        TrailBlockState(null, 0),
                                        TrailBlockState(null, 0),
                                    ),
                                ),
                        ),
                    )
                val store = RoadHistoryStore(folder)
                val loaded = store.load()
                loaded shouldBe expected
                store.save(loaded)
                store.load() shouldBe expected
            } finally {
                folder.toFile().deleteRecursively()
            }
        }
    })
