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
    })
