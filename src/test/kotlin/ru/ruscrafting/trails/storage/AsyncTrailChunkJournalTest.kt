package ru.ruscrafting.trails.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import ru.arc.persistence.DurableAcknowledgementOutcome

class AsyncTrailChunkJournalTest :
    FreeSpec({
        "keeps independent chunk snapshots and survives journal reopen" {
            val root = Files.createTempDirectory("trails-async-journal-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val first = snapshot(1, 1)
            val second = snapshot(2, 2)

            async.commit(first)
            async.commit(second)
            assertSnapshot(async.load(first.worldId, first.chunkKey), first)
            assertSnapshot(async.load(second.worldId, second.chunkKey), second)

            executor.runAll()

            journal.size() shouldBe 2
            TrailChunkRecoveryJournal(root).let { reopened ->
                assertSnapshot(reopened.load(first.worldId, first.chunkKey), first)
                assertSnapshot(reopened.load(second.worldId, second.chunkKey), second)
            }
            async.close()
        }

        "coalesces one chunk to the newest snapshot without losing completion" {
            val root = Files.createTempDirectory("trails-async-coalesce-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val old = snapshot(3, 3)
            val latest = snapshot(3, 9)

            val oldCompletion = async.commit(old)
            val latestCompletion = async.commit(latest)
            executor.runNext()
            executor.runNext()

            oldCompletion.isDone shouldBe true
            latestCompletion.isDone shouldBe true
            assertSnapshot(journal.load(latest.worldId, latest.chunkKey), latest)
            async.close()
        }

        "overlays a pending snapshot before its disk commit runs" {
            val root = Files.createTempDirectory("trails-async-overlay-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val pending = snapshot(4, 4)

            async.commit(pending)
            journal.load(pending.worldId, pending.chunkKey) shouldBe null
            assertSnapshot(async.load(pending.worldId, pending.chunkKey), pending)

            executor.runAll()
            assertSnapshot(journal.load(pending.worldId, pending.chunkKey), pending)
            async.close()
        }

        "does not let an acknowledgement delete a newer snapshot" {
            val root = Files.createTempDirectory("trails-async-ack-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val old = snapshot(5, 1)
            val latest = snapshot(5, 2)

            async.commit(old)
            executor.runAll()
            async.reap()
            journal.commit(latest)
            async.acknowledge(old)
            executor.runAll()

            assertSnapshot(journal.load(latest.worldId, latest.chunkKey), latest)
            async.close()
        }

        "retains an overlay and retries a failed commit" {
            val root = Files.createTempDirectory("trails-async-retry-")
            val executor = ManualExecutor(rejectNext = true)
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val pending = snapshot(6, 6)

            val completion = async.commit(pending)
            completion.isCompletedExceptionally shouldBe true
            assertSnapshot(async.load(pending.worldId, pending.chunkKey), pending)
            journal.load(pending.worldId, pending.chunkKey) shouldBe null

            shouldThrow<IllegalStateException> { async.reap() }
            executor.runAll()
            async.reap()

            assertSnapshot(journal.load(pending.worldId, pending.chunkKey), pending)
            async.failureCount() shouldBe 0
            async.close()
        }

        "retains a failed acknowledgement and retries it" {
            val executor = ManualExecutor()
            val recovery = mockk<TrailChunkRecoveryJournal>()
            var attempts = 0
            every { recovery.acknowledge(any()) } answers {
                attempts++
                if (attempts == 1) throw IllegalStateException("simulated acknowledgement failure")
                DurableAcknowledgementOutcome.ACKNOWLEDGED
            }
            val async = manualJournal(recovery, executor)

            async.acknowledge(snapshot(8, 8))
            executor.runNext()
            shouldThrow<IllegalStateException> { async.reap() }
            async.failureCount() shouldBe 1

            executor.runNext()
            async.reap()
            async.failureCount() shouldBe 0
            attempts shouldBe 2
            async.close()
        }

        "failed clear retains its exact pending intent and readiness waits for a successful retry" {
            val root = Files.createTempDirectory("trails-clear-retry-")
            val executor = ManualExecutor(rejectNext = true)
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val position = TrailBlockPosition.pack(1, 64, 2)
            val firstAttempt = async.clearPositions(UUID(0L, 7L), 9L, setOf(position))
            firstAttempt.isCompletedExceptionally shouldBe true
            async.hasUncommittedClear(UUID(0L, 7L), 9L) shouldBe true
            val durable = async.awaitClear(UUID(0L, 7L), 9L)
            durable.isDone shouldBe false

            shouldThrow<IllegalStateException> { async.reap() }
            executor.runAll()
            durable.join()
            async.hasUncommittedClear(UUID(0L, 7L), 9L) shouldBe false
            journal.load(UUID(0L, 7L), 9L)?.clearedPositions shouldBe setOf(position)
            async.reap()
            async.close()
        }

        "a new clear after a durable predecessor does not erase a later walk at the old position" {
            val root = Files.createTempDirectory("trails-clear-after-walk-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            val first = TrailBlockPosition.pack(1, 64, 2)
            val second = TrailBlockPosition.pack(2, 64, 2)
            val cleared = async.clearPositions(UUID(0L, 7L), 10L, setOf(first))
            executor.runAll()
            val base = cleared.join()
            val newer = TrailChunkSnapshot(base.worldId, base.chunkKey,
                TrailChunkCodec.encode(mapOf(first to TrailBlockState(null, 1))), revision = base.revision)
            async.commit(newer)
            executor.runAll()
            val later = async.clearPositions(base.worldId, base.chunkKey, setOf(second))
            executor.runAll()
            later.join().clearedPositions shouldBe setOf(second)
            TrailChunkCodec.decode(requireNotNull(journal.load(base.worldId, base.chunkKey)).encodedStates)[first]?.walks shouldBe 1
            async.close()
        }

        "close dispatch retains accepted work and observer cancellation cannot cancel its actual drain" {
            val root = Files.createTempDirectory("trails-async-close-")
            val executor = ManualExecutor()
            val journal = TrailChunkRecoveryJournal(root)
            val async = manualJournal(journal, executor)
            async.commit(snapshot(7, 7))

            val observer = async.closeAsync()
            observer.isDone shouldBe false
            observer.cancel(false)
            val actualObserver = async.closeAsync()
            actualObserver.isDone shouldBe false
            executor.isShutdown shouldBe false
            executor.runAll()
            actualObserver.join()
            executor.isShutdown shouldBe true
            executor.cancelledTasks shouldBe 0
        }
    })

private fun snapshot(
    chunkKey: Long,
    walks: Int,
): TrailChunkSnapshot =
    TrailChunkSnapshot(
        worldId = UUID(0L, 7L),
        chunkKey = chunkKey,
        encodedStates = TrailChunkCodec.encode(
            mapOf(
                TrailBlockPosition.pack(1, 64, 2) to TrailBlockState(null, walks),
            ),
        ),
    )

private fun assertSnapshot(
    actual: TrailChunkSnapshot?,
    expected: TrailChunkSnapshot,
) {
    actual?.worldId shouldBe expected.worldId
    actual?.chunkKey shouldBe expected.chunkKey
    actual?.encodedStates?.contentEquals(expected.encodedStates) shouldBe true
}

private class ManualExecutor(
    private var rejectNext: Boolean = false,
) : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false
    var cancelledTasks: Int = 0
        private set

    override fun execute(command: Runnable) {
        if (rejectNext) {
            rejectNext = false
            throw RejectedExecutionException("manual rejection")
        }
        check(!stopped) { "executor is stopped" }
        tasks += command
    }

    fun runNext() {
        check(tasks.isNotEmpty()) { "no queued task" }
        tasks.removeFirst().run()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }

    override fun shutdown() {
        stopped = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        stopped = true
        val cancelled = tasks.toMutableList()
        cancelledTasks += cancelled.size
        tasks.clear()
        return cancelled
    }

    override fun isShutdown(): Boolean = stopped

    override fun isTerminated(): Boolean = stopped && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
}

private fun manualJournal(journal: TrailChunkRecoveryJournal, executor: ManualExecutor) =
    AsyncTrailChunkJournal(journal, executor) { executor.shutdown(); CompletableFuture.completedFuture(Unit) }
