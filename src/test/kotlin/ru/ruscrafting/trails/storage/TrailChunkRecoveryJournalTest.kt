package ru.ruscrafting.trails.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.persistence.DurableAcknowledgementOutcome
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.zip.CRC32

class TrailChunkRecoveryJournalTest : FreeSpec({
    "unknown-base clear survives restart and preserves every unmentioned PDC position" {
        val root = Files.createTempDirectory("trails-clear-unknown-")
        val journal = TrailChunkRecoveryJournal(root)
        val removed = TrailBlockPosition.pack(1, 64, 1)
        val retained = TrailBlockPosition.pack(2, 64, 2)
        val pdc = mapOf(removed to TrailBlockState(null, 9), retained to TrailBlockState(null, 7))
        journal.clearPositions(WORLD, 3L, setOf(removed))

        val reopened = TrailChunkRecoveryJournal(root)
        val recovery = requireNotNull(reopened.load(WORLD, 3L))
        recovery.baseKnown shouldBe false
        recovery.encodedStates.isEmpty() shouldBe true
        val folded = pdc.toMutableMap().also { values -> recovery.clearedPositions.forEach(values::remove) }
        val committed = reopened.commit(full(3L, folded, recovery.revision))
        TrailChunkCodec.decode(committed.encodedStates) shouldBe mapOf(retained to TrailBlockState(null, 7))
        committed.clearedPositions shouldBe emptySet()
    }

    "folding a clear allows a newer walk but rejects a snapshot captured before the event" {
        val journal = TrailChunkRecoveryJournal(Files.createTempDirectory("trails-clear-new-walk-"))
        val position = TrailBlockPosition.pack(1, 64, 1)
        val old = journal.commit(full(4L, mapOf(position to TrailBlockState(null, 20))))
        val clear = journal.clearPositions(WORLD, 4L, setOf(position))
        val folded = journal.commit(full(4L, emptyMap(), clear.revision))
        val latest = journal.commit(full(4L, mapOf(position to TrailBlockState(null, 1)), folded.revision))

        shouldThrow<IllegalStateException> { journal.commit(full(4L, mapOf(position to TrailBlockState(null, 20)), old.revision)) }
        TrailChunkCodec.decode(requireNotNull(journal.load(WORLD, 4L)).encodedStates)[position]?.walks shouldBe 1
        journal.acknowledge(folded) shouldBe DurableAcknowledgementOutcome.CONTENT_MISMATCH
        journal.load(WORLD, 4L)?.recordToken shouldBe latest.recordToken
    }

    "an old PDC acknowledgement cannot retire a later clear patch" {
        val root = Files.createTempDirectory("trails-clear-stale-ack-")
        val journal = TrailChunkRecoveryJournal(root)
        val position = TrailBlockPosition.pack(1, 64, 1)
        val old = journal.commit(full(5L, mapOf(position to TrailBlockState(null, 10))))
        val patch = journal.clearPositions(WORLD, 5L, setOf(position))

        journal.acknowledge(old) shouldBe DurableAcknowledgementOutcome.CONTENT_MISMATCH
        val reopened = requireNotNull(TrailChunkRecoveryJournal(root).load(WORLD, 5L))
        reopened.recordToken shouldBe patch.recordToken
        reopened.clearedPositions shouldBe setOf(position)
    }

    "a repeated old ACK cannot delete a new record with identical bytes and numeric revision" {
        val journal = TrailChunkRecoveryJournal(Files.createTempDirectory("trails-clear-token-"))
        val position = TrailBlockPosition.pack(1, 64, 1)
        val firstPatch = journal.clearPositions(WORLD, 6L, setOf(position))
        val firstFull = journal.commit(full(6L, emptyMap(), firstPatch.revision))
        journal.acknowledge(firstFull) shouldBe DurableAcknowledgementOutcome.ACKNOWLEDGED
        val nextPatch = journal.clearPositions(WORLD, 6L, setOf(position))
        val nextFull = journal.commit(full(6L, emptyMap(), nextPatch.revision))
        firstFull.revision shouldBe nextFull.revision
        firstFull.encodedStates.contentEquals(nextFull.encodedStates) shouldBe true

        journal.acknowledge(firstFull) shouldBe DurableAcknowledgementOutcome.CONTENT_MISMATCH
        journal.load(WORLD, 6L)?.recordToken shouldBe nextFull.recordToken
    }

    "clear acknowledgement and close wait for actual existing journal executor work" {
        val root = Files.createTempDirectory("trails-clear-delayed-")
        val journal = TrailChunkRecoveryJournal(root)
        val queued = ArrayDeque<Runnable>()
        val async = AsyncTrailChunkJournal(journal, Executor { queued.addLast(it) }) { CompletableFuture.completedFuture(Unit) }
        val position = TrailBlockPosition.pack(1, 64, 1)
        val clear = async.clearPositions(WORLD, 7L, setOf(position))
        val observer = async.closeAsync()
        observer.cancel(false)
        val actual = async.closeAsync()
        clear.isDone shouldBe false
        actual.isDone shouldBe false
        journal.load(WORLD, 7L) shouldBe null

        while (queued.isNotEmpty()) queued.removeFirst().run()
        clear.join().clearedPositions shouldBe setOf(position)
        actual.join()
        TrailChunkRecoveryJournal(root).load(WORLD, 7L)?.clearedPositions shouldBe setOf(position)
    }

    "legacy v1 full snapshots retain the original identity and state" {
        val states = TrailChunkCodec.encode(mapOf(TrailBlockPosition.pack(1, -32, 2) to TrailBlockState(null, 8)))
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x5452434a)
                output.writeByte(1)
                output.writeLong(WORLD.mostSignificantBits)
                output.writeLong(WORLD.leastSignificantBits)
                output.writeLong(8L)
                output.writeInt(states.size)
                output.write(states)
            }
        }.toByteArray()
        val encoded = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(payload)
                output.writeInt(CRC32().apply { update(payload) }.value.toInt())
            }
        }.toByteArray()
        val decoded = TrailChunkSnapshotCodec.decode(encoded)
        decoded.worldId shouldBe WORLD
        decoded.chunkKey shouldBe 8L
        decoded.baseKnown shouldBe true
        decoded.revision shouldBe 0L
        decoded.clearedPositions shouldBe emptySet()
        decoded.encodedStates.contentEquals(states) shouldBe true
    }
})

private val WORLD = UUID(0L, 1234L)
private fun full(chunkKey: Long, states: Map<Int, TrailBlockState>, revision: Long = 0L) =
    TrailChunkSnapshot(WORLD, chunkKey, TrailChunkCodec.encode(states), revision = revision)
