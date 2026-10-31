package ru.ruscrafting.trails.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.domain.TrailIdentity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ChunkPersistentTrailStoreTest : FreeSpec({
    "cold capture returns while decode is blocked and does not reserve a polling queue slot" {
        withStore { f ->
            val block = f.block()
            val states = (0 until 10_000).associate { it to TrailBlockState(null, it) }
            block.chunk.persistentDataContainer.set(f.store.storageKey, PersistentDataType.BYTE_ARRAY, TrailChunkCodec.encode(states))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                val ready = f.store.prepare(block.chunk)
                repeat(20) { f.tick() }
                ready.isDone shouldBe false
                f.reads shouldBe 1
                var unrelated = false
                val other = f.queue.call({ true }) { unrelated = true }
                f.await { other.isDone }
                unrelated shouldBe true
                f.reads shouldBe 1
                release.countDown()
                f.finish(ready)
                f.store.read(f.world.getBlockAt(0, 1, 0))?.walks shouldBe 256
            } finally { release.countDown() }
        }
    }

    "coalesces mutations into one native PDC write and later load acknowledges exact durable bytes" {
        withStore { f ->
            val first = f.block(1, 64, 2)
            val second = f.block(15, 70, 15)
            f.warm(first)
            val firstState = TrailBlockState(TrailIdentity("DirtPath", 0), 4)
            val secondState = TrailBlockState(null, 0)
            f.store.write(first, firstState)
            f.store.write(second, secondState)
            first.chunk.persistentDataContainer.has(f.store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            f.store.flushDirty() shouldBe 1
            f.await { f.writes == 1 }
            f.store.read(first) shouldBe firstState
            f.store.read(second) shouldBe secondState
            f.store.trackedBlocks(first.chunk) shouldContainExactlyInAnyOrder listOf(first, second)
            f.closeStore()

            f.reopen()
            f.store.onChunkLoad(ChunkLoadEvent(first.chunk, false))
            f.warm(first)
            f.store.read(first) shouldBe firstState
            f.store.read(second) shouldBe secondState
            f.journal.size() shouldBe 0
        }
    }

    "world save starts at most eight encodes and later flushes drain remaining chunks" {
        withStore { f ->
            val blocks = (0 until 24).map { f.block(it * 16, 64, 0) }
            blocks.forEach { f.warm(it); f.store.write(it, TrailBlockState(null, 1)) }
            f.store.onWorldSave(org.bukkit.event.world.WorldSaveEvent(f.world))
            f.await { f.writes == 8 }
            f.store.flushDirty() shouldBe 8
            f.await { f.writes == 16 }
            f.store.flushDirty() shouldBe 8
            f.await { f.writes == 24 }
            f.store.flushDirty() shouldBe 0
        }
    }

    "periodic flush coalesces maintenance while the codec worker is blocked" {
        withStore { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                repeat(40) { f.store.flushDirty() shouldBe 0 }
                val unrelated = f.codec.submit {}
                unrelated.isCompletedExceptionally shouldBe false
                release.countDown()
                f.finish(unrelated)
            } finally { release.countDown() }
        }
    }

    "unload performs no native PDC mutation and retains the detached state in durable recovery" {
        withStore { f ->
            val block = f.block(3, 80, 5)
            f.warm(block)
            val expected = TrailBlockState(TrailIdentity("DirtPath", 1), 2)
            f.store.write(block, expected)
            val event = ChunkUnloadEvent(block.chunk, false)
            f.store.onChunkUnload(event)
            event.isSaveChunk shouldBe true
            f.store.cachedChunkCount() shouldBe 0
            f.writes shouldBe 0
            f.await { f.journal.load(f.world.uid, block.chunk.chunkKey) != null }
            f.closeStore()
            f.reopen()
            f.store.onChunkLoad(ChunkLoadEvent(block.chunk, false))
            f.warm(block)
            f.store.read(block) shouldBe expected
        }
    }

    "periodic flush retries unloaded snapshots after full codec admission within the shared eight chunk limit" {
        withStore { f ->
            val blocks = (0 until 12).map { f.block(it * 16, 64, 0) }
            blocks.forEachIndexed { index, block ->
                f.warm(block)
                f.store.write(block, TrailBlockState(null, index + 1))
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking = f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val queued = List(32) { f.codec.submit {} }
            try {
                blocks.forEach { f.store.onChunkUnload(ChunkUnloadEvent(it.chunk, false)) }
                f.store.cachedChunkCount() shouldBe 0
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe false
                f.journal.size() shouldBe 0
                f.writes shouldBe 0
                release.countDown()
                f.finish(CompletableFuture.allOf(blocking, *queued.toTypedArray()))

                f.store.flushDirty() shouldBe 8
                f.await { f.journal.size() == 8 }
                f.store.flushDirty() shouldBe 4
                f.await { f.journal.size() == 12 && f.store.healthContribution().dependencies["chunk-pdc"] == true }
                f.store.healthContribution().recoveryBacklog shouldBe 12
                f.writes shouldBe 0
                f.closeStore()
                f.reopen()
                blocks.forEachIndexed { index, block ->
                    f.warm(block)
                    f.store.read(block) shouldBe TrailBlockState(null, index + 1)
                }
            } finally { release.countDown() }
        }
    }

    "retired retry success preserves a newer preparation failure for the same chunk" {
        withStore { f ->
            val block = f.block()
            f.warm(block)
            f.store.write(block, TrailBlockState(null, 9))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking = f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val queued = List(32) { f.codec.submit {} }
            try {
                f.store.onChunkUnload(ChunkUnloadEvent(block.chunk, false))
                val preparation = f.store.prepare(block.chunk)
                f.await { preparation.isDone }
                preparation.isCompletedExceptionally shouldBe true
                release.countDown()
                f.finish(CompletableFuture.allOf(blocking, *queued.toTypedArray()))
                f.store.flushDirty() shouldBe 1
                f.await { f.journal.size() == 1 }
                f.finish(f.io.submit {})
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe false

                f.warm(block)
                f.store.read(block) shouldBe TrailBlockState(null, 9)
                f.store.flushDirty() shouldBe 1
                f.await { f.writes == 1 }
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe true
            } finally { release.countDown() }
        }
    }

    "shutdown drains a retired snapshot whose first codec admission was rejected" {
        withStore { f ->
            val block = f.block()
            f.warm(block)
            f.store.write(block, TrailBlockState(null, 9))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            repeat(32) { f.codec.submit {} }
            try {
                f.store.onChunkUnload(ChunkUnloadEvent(block.chunk, false))
                val closing = f.store.closeAsync()
                closing.isDone shouldBe false
                f.journal.size() shouldBe 0
                release.countDown()
                f.finish(closing)
                f.closeStore()
                f.reopen()
                f.warm(block)
                f.store.read(block) shouldBe TrailBlockState(null, 9)
                f.writes shouldBe 0
            } finally { release.countDown() }
        }
    }

    "rejected preparation of an unloaded chunk retains recovery until the next real load" {
        withStore { f ->
            val block = f.block()
            val chunk = block.chunk
            val expected = TrailBlockState(TrailIdentity("DirtPath", 1), 9)
            val encoded = TrailChunkCodec.encode(mapOf(TrailBlockPosition.pack(0, 64, 0) to expected))
            f.journal.commit(TrailChunkSnapshot(f.world.uid, chunk.chunkKey, encoded))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocking = f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val queued = List(32) { f.codec.submit {} }
            try {
                val preparation = f.store.prepare(chunk)
                f.await { preparation.isDone }
                preparation.isCompletedExceptionally shouldBe true
                f.store.onChunkUnload(ChunkUnloadEvent(chunk, false))
                release.countDown()
                f.finish(CompletableFuture.allOf(blocking, *queued.toTypedArray()))
                repeat(40) { f.store.flushDirty() shouldBe 0 }
                f.finish(f.codec.submit {})
                f.store.cachedChunkCount() shouldBe 0
                f.reads shouldBe 1
                f.writes shouldBe 0
                f.journal.load(f.world.uid, chunk.chunkKey)?.encodedStates?.contentEquals(encoded) shouldBe true
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe false
                f.store.healthContribution().recoveryBacklog shouldBe 1

                f.store.onChunkLoad(ChunkLoadEvent(chunk, false))
                f.warm(block)
                f.store.read(block) shouldBe expected
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe false
                f.store.flushDirty() shouldBe 1
                f.await { f.writes == 1 }
                f.store.healthContribution().dependencies["chunk-pdc"] shouldBe true
                f.store.onChunkUnload(ChunkUnloadEvent(chunk, true))
                f.store.onChunkLoad(ChunkLoadEvent(chunk, false))
                f.warm(block)
                f.journal.size() shouldBe 0
            } finally { release.countDown() }
        }
    }

    "failed native PDC mutation leaves the acknowledged journal authoritative until a later capture" {
        withStore { f ->
            val block = f.block(7, 81, 9)
            f.warm(block)
            val expected = TrailBlockState(TrailIdentity("DirtPath", 2), 6)
            f.store.write(block, expected)
            f.failWrites = true
            f.store.flushDirty()
            f.await { f.failures.isNotEmpty() }
            f.store.healthContribution().state shouldBe RuntimeHealthState.DEGRADED
            block.chunk.persistentDataContainer.has(f.store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            f.closeStore()
            f.failWrites = false
            f.reopen()
            f.store.onChunkLoad(ChunkLoadEvent(block.chunk, false))
            f.warm(block)
            f.store.read(block) shouldBe expected
            f.store.flushDirty()
            f.await { block.chunk.persistentDataContainer.has(f.store.storageKey, PersistentDataType.BYTE_ARRAY) }
            f.store.onChunkUnload(ChunkUnloadEvent(block.chunk, true))
            f.store.onChunkLoad(ChunkLoadEvent(block.chunk, false))
            f.warm(block)
            f.journal.size() shouldBe 0
        }
    }

    "clearing a final entry removes the PDC payload after worker durability" {
        withStore { f ->
            val block = f.block(4, 66, 7)
            f.warm(block)
            f.store.write(block, TrailBlockState(null, 0))
            f.store.flushDirty()
            f.await { f.writes == 1 }
            f.store.clear(block)
            f.store.flushDirty()
            f.await { f.writes == 2 }
            block.chunk.persistentDataContainer.has(f.store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            f.store.trackedBlocks(block.chunk) shouldBe emptyList()
        }
    }

    "corrupt native payload is reported on Main and is repaired by the durable worker pipeline" {
        withStore { f ->
            val block = f.block(4, 66, 7)
            block.chunk.persistentDataContainer.set(f.store.storageKey, PersistentDataType.BYTE_ARRAY, byteArrayOf(1, 2, 3, 4, 5))
            f.warm(block)
            f.store.read(block) shouldBe null
            f.corruptions.size shouldBe 1
            f.store.healthContribution().state shouldBe RuntimeHealthState.DEGRADED
            f.store.flushDirty()
            f.await { f.writes == 1 }
            block.chunk.persistentDataContainer.has(f.store.storageKey, PersistentDataType.BYTE_ARRAY) shouldBe false
            f.store.healthContribution().state shouldBe RuntimeHealthState.UP
        }
    }

    "close returns during blocked journal IO and retains the latest unloaded revision after actual drain" {
        withStore { f ->
            val block = f.block()
            f.warm(block)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.io.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                f.store.write(block, TrailBlockState(null, 1))
                f.store.flushDirty()
                f.store.write(block, TrailBlockState(null, 9))
                f.store.onChunkUnload(ChunkUnloadEvent(block.chunk, true))
                repeat(8) { f.store.flushDirty() shouldBe 0 }
                val closing = f.store.closeAsync()
                closing.isDone shouldBe false
                f.writes shouldBe 0
                val observer = f.store.closeAsync()
                observer.cancel(false)
                release.countDown()
                f.finish(closing)
                f.closeStore()
                f.reopen()
                f.warm(block)
                f.store.read(block)?.walks shouldBe 9
            } finally { release.countDown() }
        }
    }

    "foreign reference can become unloaded before capture without any cold chunk getter" {
        withStore { f ->
            val real = f.block()
            val packed = TrailBlockPosition.pack(0, 64, 0)
            real.chunk.persistentDataContainer.set(f.store.storageKey, PersistentDataType.BYTE_ARRAY,
                TrailChunkCodec.encode(mapOf(packed to TrailBlockState(null, 9))))
            val coldWorld = mockk<World>()
            every { coldWorld.uid } returns f.world.uid
            every { coldWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns false
            val reference = mockk<Block>()
            every { reference.world } returns coldWorld
            every { reference.x } returns 0
            every { reference.y } returns 64
            every { reference.z } returns 0
            every { reference.chunk } throws AssertionError("foreign cursor cold-loaded a chunk")
            val cleared = f.store.clearForeign(listOf(reference)) { it }
            f.finish(cleared)
            val patch = requireNotNull(f.journal.load(f.world.uid, real.chunk.chunkKey))
            patch.baseKnown shouldBe false
            patch.clearedPositions shouldBe setOf(packed)
            f.closeStore()
            f.reopen()
            f.warm(real)
            f.store.read(real) shouldBe null
            f.store.write(real, TrailBlockState(null, 1))
            f.store.flushDirty()
            f.await { f.writes == 1 }
            f.store.read(real)?.walks shouldBe 1
        }
    }

    "foreign barrier invalidates only older affected guards and preserves a newer walk" {
        withStore { f ->
            val affected = f.block()
            val unaffected = f.block(1, 64, 0)
            f.warm(affected)
            f.store.write(affected, TrailBlockState(null, 8))
            var oldApplied = false
            var unrelatedApplied = false
            val old = f.store.whenReady(affected.chunk, f.store.mutationGuard(affected)) { oldApplied = true }
            val unrelated = f.store.whenReady(unaffected.chunk, f.store.mutationGuard(unaffected)) { unrelatedApplied = true }
            val cleared = f.store.clearForeign(listOf(affected)) { it }
            val newest = f.store.whenReady(affected.chunk, f.store.mutationGuard(affected)) {
                f.store.write(affected, TrailBlockState(null, 1))
            }
            f.finish(CompletableFuture.allOf(old, unrelated, cleared, newest))
            oldApplied shouldBe false
            unrelatedApplied shouldBe true
            f.store.read(affected)?.walks shouldBe 1
            f.store.pendingPositionCount() shouldBe 0
        }
    }

    "4096 delayed operations do not occupy native admission and reject only the excess operation" {
        withStore { f ->
            val block = f.block()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                var completed = 0
                val accepted = (0 until 4096).map { f.store.whenReady(block.chunk) { completed++ } }
                f.store.whenReady(block.chunk) {}.isCompletedExceptionally shouldBe true
                f.store.pendingOperationCount() shouldBe 4096
                val cleared = f.store.clearForeign(listOf(block)) { it }
                var unrelated = false
                val sentinel = f.queue.call({ true }) { unrelated = true }
                f.finish(sentinel)
                unrelated shouldBe true
                completed shouldBe 0
                release.countDown()
                // Each same-chunk continuation deliberately waits for its predecessor and
                // native.call has separate marshal/admission ticks. Drive all 4096 actual
                // completions instead of imposing the small IO-fixture's eight-second budget.
                f.finish(CompletableFuture.allOf(*accepted.toTypedArray(), cleared), timeoutSeconds = 30)
                completed shouldBe 4096
                f.store.pendingOperationCount() shouldBe 0
            } finally { release.countDown() }
        }
    }

    "foreign cursor completes beyond the legacy allocation hint while earlier work remains pending" {
        withStore(maximumPending = 1) { f ->
            var release = false
            val blocker = f.queue.submit({ true }) { release }
            val cleared = f.store.clearForeign(listOf(f.block())) { it }
            f.finish(cleared)
            blocker.isDone shouldBe false
            f.journal.size() shouldBe 1
            release = true
            f.finish(blocker)
        }
    }

    "shutdown persists captured foreign coordinates and fails the actual drain for an uncaptured native tail" {
        withStore { f ->
            val blocks = (0 until 20).map { f.block(it, 64, 0) }
            val clearing = f.store.clearForeign(blocks) { it }
            f.tick()
            clearing.isDone shouldBe false
            shouldThrow<java.util.concurrent.CompletionException> { f.closeStore() }
            clearing.isCompletedExceptionally shouldBe true
            val persisted = f.journal.load(f.world.uid, blocks.first().chunk.chunkKey)
            requireNotNull(persisted).clearedPositions.size shouldBe 7
        }
    }

    "codec admission overload retains the numeric foreign clear and its unfinished barrier until retry" {
        withStore { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.codec.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            repeat(32) { f.codec.submit {} }
            try {
                val clearing = f.store.clearForeign(listOf(f.block())) { it }
                repeat(20) { f.tick() }
                clearing.isDone shouldBe false
                f.journal.size() shouldBe 0
                var unrelated = false
                f.finish(f.queue.call({ true }) { unrelated = true })
                unrelated shouldBe true
                release.countDown()
                f.finish(clearing)
                requireNotNull(f.journal.load(f.world.uid, f.block().chunk.chunkKey)).clearedPositions shouldBe
                    setOf(TrailBlockPosition.pack(0, 64, 0))
            } finally { release.countDown() }
        }
    }

    "storage rejects off-thread Bukkit access" {
        withStore { f ->
            val block = f.block()
            var failure: Throwable? = null
            thread(name = "trail-storage-test") { failure = runCatching { f.store.read(block) }.exceptionOrNull() }.join()
            failure.shouldBeInstanceOf<IllegalStateException>()
            shouldThrow<IllegalStateException> { f.store.read(block) }
        }
    }
})

private inline fun withStore(maximumPending: Int = 4096, block: (StoreFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val fixture = StoreFixture(paper, maximumPending)
        try { block(fixture) } finally { fixture.close() }
    }
}

private class StoreFixture(private val paper: MockBukkitTestRuntime, maximumPending: Int) : AutoCloseable {
    val plugin = paper.createSimplePlugin("TrailStoreTest")
    val world = paper.addSimpleWorld("world")
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    val queue = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L }, maximumPending = maximumPending)
    var journal = TrailChunkRecoveryJournal(plugin.dataFolder.toPath())
        private set
    var io = BoundedAsyncExecutor("trail-test-journal", 1, 32)
        private set
    var codec = BoundedAsyncExecutor("trail-test-codec", 1, 32)
        private set
    var reads = 0
    var writes = 0
    var failWrites = false
    val corruptions = mutableListOf<Throwable>()
    val failures = mutableListOf<Throwable>()
    private val persistence = object : TrailChunkPersistence {
        override fun read(chunk: Chunk, key: NamespacedKey): ByteArray? {
            Bukkit.isPrimaryThread() shouldBe true
            reads++
            return BukkitTrailChunkPersistence.read(chunk, key)
        }
        override fun write(chunk: Chunk, key: NamespacedKey, encoded: ByteArray?) {
            Bukkit.isPrimaryThread() shouldBe true
            if (failWrites) throw IllegalStateException("simulated native PDC failure")
            writes++
            BukkitTrailChunkPersistence.write(chunk, key, encoded)
        }
    }
    var store = createStore()
        private set
    private var closedStore = false

    private fun createStore() = ChunkPersistentTrailStore(plugin, queue, journal,
        corruptionSink = { _, error -> Bukkit.isPrimaryThread() shouldBe true; corruptions += error },
        persistenceFailureSink = { _, error -> Bukkit.isPrimaryThread() shouldBe true; failures += error },
        persistence = persistence, asyncJournal = AsyncTrailChunkJournal(journal, io), codec = codec)

    fun block(x: Int = 0, y: Int = 64, z: Int = 0): Block = world.getBlockAt(x, y, z).also { it.type = Material.GRASS_BLOCK }
    fun warm(block: Block) { finish(store.prepare(block.chunk)) }
    fun tick() { scheduler.tick(); paper.performTicks(1); Thread.sleep(1) }
    fun await(timeoutSeconds: Long = 8, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition() && System.nanoTime() < deadline) tick()
        condition() shouldBe true
    }
    fun finish(future: CompletableFuture<*>, timeoutSeconds: Long = 8) { await(timeoutSeconds) { future.isDone }; future.join() }
    fun closeStore() {
        if (closedStore) return
        try { finish(store.closeAsync()) } finally { closedStore = true }
    }
    fun reopen() {
        check(closedStore)
        journal = TrailChunkRecoveryJournal(plugin.dataFolder.toPath())
        io = BoundedAsyncExecutor("trail-test-journal-reopened", 1, 32)
        codec = BoundedAsyncExecutor("trail-test-codec-reopened", 1, 32)
        store = createStore()
        closedStore = false
    }
    override fun close() {
        try { closeStore() } finally { queue.close(); scope.close() }
    }
}
