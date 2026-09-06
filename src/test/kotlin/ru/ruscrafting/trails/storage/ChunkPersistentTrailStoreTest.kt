package ru.ruscrafting.trails.storage

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.domain.TrailIdentity
import kotlin.concurrent.thread

class ChunkPersistentTrailStoreTest :
    FreeSpec({
        lateinit var runtime: MockBukkitTestRuntime

        beforeTest { runtime = MockBukkitTestRuntime.open() }
        afterTest { runtime.close() }

        "coalesces block mutations into one chunk PDC write and reloads them" {
            val plugin = runtime.createSimplePlugin("TrailStoreTest")
            val world = runtime.addSimpleWorld("world")
            val first = world.getBlockAt(1, 64, 2).also { it.type = Material.GRASS_BLOCK }
            val second = world.getBlockAt(15, 70, 15).also { it.type = Material.STONE }
            val store = testStore(plugin)
            val firstState = TrailBlockState(TrailIdentity("DirtPath", 0), 4)
            val secondState = TrailBlockState(null, 0)

            store.write(first, firstState)
            store.write(second, secondState)

            store.read(first) shouldBe firstState
            store.read(second) shouldBe secondState
            first.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            store.flushDirty() shouldBe 1
            first.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe true

            val reopened = testStore(plugin)
            reopened.read(first) shouldBe firstState
            reopened.read(second) shouldBe secondState
            reopened.trackedBlocks(first.chunk) shouldContainExactlyInAnyOrder listOf(first, second)

            reopened.close()
            store.close()
        }

        "world save processes only eight dirty chunks and later ticks drain the remainder" {
            val plugin = runtime.createSimplePlugin("TrailBatchTest")
            val world = runtime.addSimpleWorld("world")
            val blocks = (0 until 24).map { world.getBlockAt(it * 16, 64, 0) }
            val store = testStore(plugin)
            blocks.forEach { store.write(it, TrailBlockState(null, 1)) }

            store.onWorldSave(org.bukkit.event.world.WorldSaveEvent(world))
            blocks.count { it.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) } shouldBe 8
            store.flushDirty() shouldBe 8
            store.flushDirty() shouldBe 8
            store.flushDirty() shouldBe 0
            blocks.count { it.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) } shouldBe 24
            store.close()
        }

        "chunk unload flushes and evicts cached state" {
            val plugin = runtime.createSimplePlugin("TrailUnloadTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(3, 80, 5).also { it.type = Material.DIRT }
            val store = testStore(plugin)
            runtime.server.pluginManager.registerEvents(store, plugin)
            store.write(block, TrailBlockState(TrailIdentity("DirtPath", 1), 2))

            runtime.callEvent(ChunkUnloadEvent(block.chunk, true))

            store.cachedChunkCount() shouldBe 0
            block.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe true
            store.close()
        }

        "failed chunk PDC writes recover from the durable journal after a real reload" {
            val plugin = runtime.createSimplePlugin("TrailRecoveryTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(7, 81, 9).also { it.type = Material.DIRT }
            val expected = TrailBlockState(TrailIdentity("DirtPath", 2), 6)
            val failures = mutableListOf<Throwable>()
            val failingPersistence =
                object : TrailChunkPersistence by BukkitTrailChunkPersistence {
                    override fun write(chunk: org.bukkit.Chunk, key: org.bukkit.NamespacedKey, encoded: ByteArray?) {
                        throw IllegalStateException("simulated PDC failure")
                    }
                }
            val store =
                testStore(
                    plugin = plugin,
                    persistenceFailureSink = { _, error -> failures += error },
                    persistence = failingPersistence,
                )
            store.write(block, expected)
            val failedUnload = ChunkUnloadEvent(block.chunk, false)

            store.onChunkUnload(failedUnload)

            failedUnload.isSaveChunk shouldBe true
            store.cachedChunkCount() shouldBe 0
            store.healthContribution().state shouldBe RuntimeHealthState.DEGRADED
            store.healthContribution().recoveryBacklog shouldBe 1
            failures.size shouldBe 1
            block.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            store.close()

            val recovered = testStore(plugin)
            recovered.onChunkLoad(ChunkLoadEvent(block.chunk, false))
            recovered.read(block) shouldBe expected
            recovered.flushDirty() shouldBe 1
            recovered.healthContribution().recoveryBacklog shouldBe 1
            recovered.onChunkUnload(ChunkUnloadEvent(block.chunk, true))
            recovered.onChunkLoad(ChunkLoadEvent(block.chunk, false))

            recovered.read(block) shouldBe expected
            recovered.healthContribution().recoveryBacklog shouldBe 0
            recovered.healthContribution().state shouldBe RuntimeHealthState.UP
            recovered.close()
        }

        "clearing the final entry removes the chunk PDC payload" {
            val plugin = runtime.createSimplePlugin("TrailClearTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(4, 66, 7)
            val store = testStore(plugin)
            store.write(block, TrailBlockState(null, 0))
            store.flushDirty()

            store.clear(block)
            store.flushDirty()

            block.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            store.trackedBlocks(block.chunk) shouldBe emptyList()
            store.close()
        }

        "corrupt chunk data is bounded, reported, and replaced with an empty payload" {
            val plugin = runtime.createSimplePlugin("TrailCorruptionTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(4, 66, 7)
            val corruptions = mutableListOf<Throwable>()
            val store =
                testStore(
                    plugin = plugin,
                    corruptionSink = { _, error -> corruptions += error },
                )
            block.chunk.persistentDataContainer.set(
                store.storageKey,
                PersistentDataType.BYTE_ARRAY,
                byteArrayOf(1, 2, 3, 4, 5),
            )

            store.read(block) shouldBe null

            corruptions.size shouldBe 1
            store.healthContribution().state shouldBe RuntimeHealthState.DEGRADED
            store.flushDirty() shouldBe 1
            block.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            store.healthContribution().state shouldBe RuntimeHealthState.UP
            store.close()
        }

        "close flushes the final dirty snapshot" {
            val plugin = runtime.createSimplePlugin("TrailCloseTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(6, 70, 8)
            val expected = TrailBlockState(TrailIdentity("DirtPath", 0), 3)
            val store = testStore(plugin)
            store.write(block, expected)

            store.close()

            val reopened = testStore(plugin)
            reopened.read(block) shouldBe expected
            reopened.close()
        }

        "world save and unload never wait for blocked journal I/O and reload sees the latest queued state" {
            val plugin = runtime.createSimplePlugin("TrailAsyncTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(0, 64, 0)
            val journal = TrailChunkRecoveryJournal(plugin.dataFolder.toPath())
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            executor.execute {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            entered.await(1, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            val store = ChunkPersistentTrailStore(plugin, recoveryJournal = journal,
                asyncJournal = AsyncTrailChunkJournal(journal, executor))
            val first = TrailBlockState(TrailIdentity("DirtPath", 0), 1)
            val newest = first.copy(walks = 9)
            try {
                store.write(block, first)
                store.onWorldSave(org.bukkit.event.world.WorldSaveEvent(world))
                store.write(block, newest)
                store.onChunkUnload(ChunkUnloadEvent(block.chunk, true))
                store.cachedChunkCount() shouldBe 0
                // Neither handler waited for the worker, and PDC has not advanced ahead of WAL.
                release.count shouldBe 1L
                journal.size() shouldBe 0
                block.chunk.persistentDataContainer.has(store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
                store.onChunkLoad(ChunkLoadEvent(block.chunk, false))
                store.read(block) shouldBe newest
            } finally {
                release.countDown()
                store.close()
            }
            val reopened = testStore(plugin)
            reopened.read(block) shouldBe newest
            reopened.close()
        }

        "rejects Bukkit storage access away from the primary thread" {
            val plugin = runtime.createSimplePlugin("TrailThreadTest")
            val world = runtime.addSimpleWorld("world")
            val block = world.getBlockAt(0, 64, 0)
            val store = testStore(plugin)
            var failure: Throwable? = null

            thread(name = "trail-storage-test") {
                failure = runCatching { store.read(block) }.exceptionOrNull()
            }.join()

            failure.shouldBeInstanceOf<IllegalStateException>()
            store.close()
        }
    })


private fun testStore(
    plugin: org.bukkit.plugin.Plugin,
    corruptionSink: (org.bukkit.Chunk, Throwable) -> Unit = { _, _ -> },
    persistenceFailureSink: (org.bukkit.Chunk, Throwable) -> Unit = { _, _ -> },
    persistence: TrailChunkPersistence = BukkitTrailChunkPersistence,
): ChunkPersistentTrailStore {
    val journal = TrailChunkRecoveryJournal(plugin.dataFolder.toPath())
    return ChunkPersistentTrailStore(
        plugin, corruptionSink, persistenceFailureSink, persistence, journal,
        AsyncTrailChunkJournal(journal, InlineJournalExecutor()),
    )
}

internal class InlineJournalExecutor : java.util.concurrent.AbstractExecutorService() {
    private var stopped = false
    override fun execute(command: Runnable) { check(!stopped); command.run() }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown(); return mutableListOf() }
    override fun isShutdown(): Boolean = stopped
    override fun isTerminated(): Boolean = stopped
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = stopped
}
