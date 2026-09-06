package ru.ruscrafting.trails.storage

import ru.arc.persistence.CoalescingAsyncWriter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only detached byte snapshots cross the executor boundary; all queue access is on the primary thread. */
internal class AsyncTrailChunkJournal(
    private val journal: TrailChunkRecoveryJournal,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Trails-chunk-journal").apply { isDaemon = true }
    },
) {
    private data class Key(val worldId: UUID, val chunkKey: Long)
    private class Pending(val writer: CoalescingAsyncWriter<TrailChunkSnapshot>) {
        lateinit var snapshot: TrailChunkSnapshot
        lateinit var completion: CompletableFuture<Unit>
    }
    private val pending = linkedMapOf<Key, Pending>()
    private val pendingSize = AtomicInteger()
    private val failures = AtomicInteger()
    private data class Acknowledgement(val snapshot: TrailChunkSnapshot, var completion: CompletableFuture<*>)
    private val acknowledgements = linkedMapOf<Key, Acknowledgement>()
    private val failedCommits = mutableSetOf<Key>()
    private val failedAcknowledgements = mutableSetOf<Key>()

    fun load(worldId: UUID, chunkKey: Long): TrailChunkSnapshot? =
        pending[Key(worldId, chunkKey)]?.snapshot ?: journal.load(worldId, chunkKey)

    fun commit(snapshot: TrailChunkSnapshot): CompletableFuture<Unit> {
        val key = Key(snapshot.worldId, snapshot.chunkKey)
        val previous = pending[key]
        if (previous == null && journal.load(snapshot.worldId, snapshot.chunkKey)?.let { same(it, snapshot) } == true) {
            return CompletableFuture.completedFuture(Unit)
        }
        if (previous != null && same(previous.snapshot, snapshot)) {
            return previous.completion
        }
        val entry = previous ?: Pending(CoalescingAsyncWriter { value ->
            val completion = CompletableFuture<Unit>()
            executor.execute {
                try {
                    journal.commit(value)
                    completion.complete(Unit)
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
            completion
        }).also { pending[key] = it; pendingSize.set(pending.size) }
        entry.snapshot = snapshot
        entry.completion = entry.writer.submit(snapshot)
        return entry.completion
    }

    fun acknowledge(snapshot: TrailChunkSnapshot) {
        // A load can only acknowledge disk state, never an outstanding in-memory update.
        if (pending.containsKey(Key(snapshot.worldId, snapshot.chunkKey))) return
        val key = Key(snapshot.worldId, snapshot.chunkKey)
        acknowledgements.getOrPut(key) {
            Acknowledgement(snapshot, CompletableFuture.supplyAsync({ journal.acknowledge(snapshot) }, executor))
        }
    }

    fun reap() {
        var firstFailure: Throwable? = null
        pending.entries.removeIf { (key, entry) ->
            if (entry.completion.isDone && !entry.completion.isCompletedExceptionally) {
                failedCommits.remove(key)
                true
            } else {
                if (entry.completion.isCompletedExceptionally) {
                    if (failedCommits.add(key)) firstFailure = runCatching { entry.completion.join() }.exceptionOrNull()
                    // Retain and retry detached snapshots after their Bukkit chunk has been unloaded.
                    entry.completion = entry.writer.submit(entry.snapshot)
                }
                false
            }
        }
        acknowledgements.entries.removeIf { (key, entry) ->
            if (entry.completion.isDone && !entry.completion.isCompletedExceptionally) {
                failedAcknowledgements.remove(key)
                true
            } else {
                if (entry.completion.isCompletedExceptionally) {
                    if (failedAcknowledgements.add(key)) firstFailure = runCatching { entry.completion.join() }.exceptionOrNull()
                    entry.completion = CompletableFuture.supplyAsync({ journal.acknowledge(entry.snapshot) }, executor)
                }
                false
            }
        }
        pendingSize.set(pending.size)
        failures.set(failedCommits.size + failedAcknowledgements.size)
        firstFailure?.let { throw IllegalStateException("Trail journal I/O failed; retained for retry", it) }
    }

    fun pendingCount(): Int = pendingSize.get()

    fun failureCount(): Int = failures.get()

    fun close(timeoutSeconds: Long = 10) {
        val completions = pending.values.map { it.writer.closeAsync() } +
            pending.values.map { it.completion } + acknowledgements.values.map { it.completion }
        try {
            CompletableFuture.allOf(*completions.toTypedArray()).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
            throw failure
        }
        executor.shutdown()
        check(executor.awaitTermination(1, TimeUnit.SECONDS)) { "Trail journal executor did not terminate" }
        reap()
    }

    private fun same(first: TrailChunkSnapshot, second: TrailChunkSnapshot): Boolean =
        first.worldId == second.worldId && first.chunkKey == second.chunkKey &&
            first.encodedStates.contentEquals(second.encodedStates)
}
