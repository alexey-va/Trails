package ru.ruscrafting.trails.storage

import ru.arc.persistence.CoalescingAsyncWriter
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.persistence.DurableAcknowledgementOutcome
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Detached byte snapshots only. Commit/close preparation belongs to the codec/cleanup worker. */
internal class AsyncTrailChunkJournal(
    private val journal: TrailChunkRecoveryJournal,
    private val executor: Executor,
    private val stopExecutor: () -> CompletableFuture<Unit>,
) {
    constructor(journal: TrailChunkRecoveryJournal, executor: BoundedAsyncExecutor = BoundedAsyncExecutor("Trails-chunk-journal")) :
        this(journal, executor, executor::closeAsync)

    private data class Key(val worldId: UUID, val chunkKey: Long)
    private class Pending(val writer: CoalescingAsyncWriter<TrailChunkSnapshot>) {
        @Volatile lateinit var snapshot: TrailChunkSnapshot
        @Volatile lateinit var completion: CompletableFuture<Unit>
        @Volatile var clearDurable: CompletableFuture<Unit>? = null
    }
    private val admission = Any()
    private var closed = false
    private val closeCompletion = CompletableFuture<Unit>()
    private val pending = ConcurrentHashMap<Key, Pending>()
    private val pendingSize = AtomicInteger()
    private val failures = AtomicInteger()
    private data class Acknowledgement(val snapshot: TrailChunkSnapshot, @Volatile var completion: CompletableFuture<DurableAcknowledgementOutcome>)
    private val acknowledgements = ConcurrentHashMap<Key, Acknowledgement>()
    private val failedCommits = ConcurrentHashMap.newKeySet<Key>()
    private val failedAcknowledgements = ConcurrentHashMap.newKeySet<Key>()
    private data class Maintenance(val key: Key, val acknowledgement: Boolean)
    private val maintenance = ConcurrentLinkedQueue<Maintenance>()
    private val auxiliary = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    fun load(worldId: UUID, chunkKey: Long): TrailChunkSnapshot? =
        pending[Key(worldId, chunkKey)]?.snapshot ?: journal.load(worldId, chunkKey)

    /** Unlike the immediate overlay, this returns the exact durable revision after older writes. */
    fun loadDurable(worldId: UUID, chunkKey: Long): CompletableFuture<TrailChunkSnapshot?> = synchronized(admission) {
        check(!closed) { "trail chunk journal is closed" }
        val predecessor = pending[Key(worldId, chunkKey)]?.completion ?: CompletableFuture.completedFuture(Unit)
        track(predecessor.thenCompose { execute { journal.load(worldId, chunkKey) } }).copy()
    }

    fun clearPositions(worldId: UUID, chunkKey: Long, positions: Set<Int>): CompletableFuture<TrailChunkSnapshot> = synchronized(admission) {
        check(!closed) { "trail chunk journal is closed" }
        val predecessor = pending[Key(worldId, chunkKey)]?.completion ?: CompletableFuture.completedFuture(Unit)
        track(predecessor.handle { _, _ -> Unit }.thenCompose {
            synchronized(admission) {
                val previous = pending[Key(worldId, chunkKey)]
                val uncleared = previous?.takeIf {
                    !it.snapshot.baseKnown && (!it.completion.isDone || it.completion.isCompletedExceptionally)
                }?.snapshot?.clearedPositions.orEmpty()
                val request = TrailChunkSnapshot(worldId, chunkKey, byteArrayOf(), baseKnown = false,
                    clearedPositions = (uncleared + positions).toSet())
                submit(request).thenApply {
                    request.persistedSnapshot ?: requireNotNull(journal.load(worldId, chunkKey)) { "committed trail clear is missing" }
                }
            }
        }).copy()
    }

    fun hasUncommittedClear(worldId: UUID, chunkKey: Long): Boolean {
        val entry = pending[Key(worldId, chunkKey)] ?: return false
        return !entry.snapshot.baseKnown && (!entry.completion.isDone || entry.completion.isCompletedExceptionally)
    }

    /** A transient failed attempt retains this signal until retry succeeds or actual close fails. */
    fun awaitClear(worldId: UUID, chunkKey: Long): CompletableFuture<Unit> =
        pending[Key(worldId, chunkKey)]?.takeIf { !it.snapshot.baseKnown }?.clearDurable?.copy()
            ?: CompletableFuture.completedFuture(Unit)

    fun commit(snapshot: TrailChunkSnapshot): CompletableFuture<Unit> = synchronized(admission) {
        check(!closed) { "trail chunk journal is closed" }
        submit(snapshot)
    }

    /** Admission lock; a previously accepted clear may enter here after external admission closes. */
    private fun submit(snapshot: TrailChunkSnapshot): CompletableFuture<Unit> {
        val key = Key(snapshot.worldId, snapshot.chunkKey)
        val previous = pending[key]
        if (snapshot.baseKnown && previous != null && !previous.snapshot.baseKnown &&
            (!previous.completion.isDone || previous.completion.isCompletedExceptionally)) {
            throw TrailChunkRevisionChanged("a full trail write must wait for its accepted foreign clear")
        }
        val current = journal.load(snapshot.worldId, snapshot.chunkKey)
        if (previous == null && current != null && current.baseKnown && current.clearedPositions.isEmpty() &&
            current.revision == snapshot.revision && current.encodedStates.contentEquals(snapshot.encodedStates)) {
            snapshot.persistedRevision = current.revision
            snapshot.persistedSnapshot = current
            return CompletableFuture.completedFuture(Unit)
        }
        if (previous != null && same(previous.snapshot, snapshot)) {
            val previousSnapshot = previous.snapshot
            if (previous.completion.isCompletedExceptionally) {
                previous.completion = previous.writer.submit(snapshot)
                observeClear(previous)
            }
            return previous.completion.thenApply {
                snapshot.persistedRevision = previousSnapshot.persistedRevision
                snapshot.persistedSnapshot = previousSnapshot.persistedSnapshot
                Unit
            }.copy()
        }
        val entry = previous ?: Pending(CoalescingAsyncWriter { value ->
            val completion = CompletableFuture<Unit>()
            try {
                executor.execute {
                    try {
                        if (value.baseKnown) journal.commit(value) else {
                            val committed = journal.clearPositions(value.worldId, value.chunkKey, value.clearedPositions)
                            value.persistedRevision = committed.revision
                            value.persistedSnapshot = committed
                        }
                        completion.complete(Unit)
                    } catch (failure: Throwable) {
                        completion.completeExceptionally(failure)
                    }
                }
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
            }
            completion
        })
        if (!snapshot.baseKnown && (previous == null || previous.snapshot.baseKnown ||
                previous.completion.isDone && !previous.completion.isCompletedExceptionally)) {
            entry.clearDurable = CompletableFuture()
        }
        entry.snapshot = snapshot
        entry.completion = entry.writer.submit(snapshot)
        observeClear(entry)
        if (previous == null) {
            pending[key] = entry
            maintenance += Maintenance(key, false)
            pendingSize.set(pending.size)
        }
        return entry.completion.copy()
    }

    fun acknowledge(snapshot: TrailChunkSnapshot): CompletableFuture<DurableAcknowledgementOutcome> = synchronized(admission) {
        check(!closed) { "trail chunk journal is closed" }
        // A load can only acknowledge disk state, never an outstanding in-memory update.
        val key = Key(snapshot.worldId, snapshot.chunkKey)
        val pendingWrite = pending[key]?.completion
        if (pendingWrite != null && (!pendingWrite.isDone || pendingWrite.isCompletedExceptionally)) {
            return@synchronized CompletableFuture.completedFuture(DurableAcknowledgementOutcome.CONTENT_MISMATCH)
        }
        val previous = acknowledgements[key]
        if (previous != null && same(previous.snapshot, snapshot)) return@synchronized previous.completion.copy()
        val next = Acknowledgement(snapshot, submitAcknowledgement(snapshot))
        acknowledgements[key] = next
        if (previous == null) {
            maintenance += Maintenance(key, true)
        }
        next.completion.copy()
    }

    /** Bounded maintenance on the codec worker; this never scans the entire cache on Paper. */
    fun reap() {
        if (synchronized(admission) { closed }) return
        var firstFailure: Throwable? = null
        repeat(minOf(MAINTENANCE_STEPS, pending.size + acknowledgements.size)) {
            val next = maintenance.poll() ?: return@repeat
            synchronized(admission) {
                if (closed) return@synchronized
                val key = next.key
                if (next.acknowledgement) {
                    val entry = acknowledgements[key] ?: return@synchronized
                    if (entry.completion.isDone && !entry.completion.isCompletedExceptionally) {
                        failedAcknowledgements.remove(key)
                        acknowledgements.remove(key, entry)
                    } else {
                        if (entry.completion.isCompletedExceptionally) {
                            if (failedAcknowledgements.add(key)) firstFailure = runCatching { entry.completion.join() }.exceptionOrNull()
                            entry.completion = submitAcknowledgement(entry.snapshot)
                        }
                        maintenance += next
                    }
                } else {
                    val entry = pending[key] ?: return@synchronized
                    if (entry.completion.isDone && !entry.completion.isCompletedExceptionally) {
                        failedCommits.remove(key)
                        pending.remove(key, entry)
                    } else {
                        if (entry.completion.isCompletedExceptionally) {
                            if (failedCommits.add(key)) firstFailure = runCatching { entry.completion.join() }.exceptionOrNull()
                            entry.completion = entry.writer.submit(entry.snapshot)
                            observeClear(entry)
                        }
                        maintenance += next
                    }
                }
            }
        }
        pendingSize.set(pending.size)
        failures.set(failedCommits.size + failedAcknowledgements.size)
        firstFailure?.let { throw IllegalStateException("Trail journal I/O failed; retained for retry", it) }
    }

    fun pendingCount(): Int = pendingSize.get()

    fun failureCount(): Int = failures.get()

    /** Worker-only collection; accepted writes keep their executor until their actual completion. */
    fun closeAsync(): CompletableFuture<Unit> {
        synchronized(admission) {
            if (closed) return closeCompletion.copy()
            closed = true
        }
        // Accepted clears can still be waiting for an older full snapshot; seal writers only after
        // those admissions have reached their actual completion, not before their continuation runs.
        CompletableFuture.allOf(*auxiliary.toTypedArray()).handle { _, _ -> Unit }.thenCompose {
            val completions = synchronized(admission) {
                pending.values.forEach { entry ->
                    if (entry.completion.isCompletedExceptionally) {
                        entry.completion = entry.writer.submit(entry.snapshot)
                        observeClear(entry)
                    }
                }
                acknowledgements.values.forEach { entry ->
                    if (entry.completion.isCompletedExceptionally) entry.completion = submitAcknowledgement(entry.snapshot)
                }
                pending.values.map { runCatching { it.writer.closeAsync() }.getOrElse { CompletableFuture.failedFuture<Unit>(it) } } +
                    pending.values.map { it.completion } + acknowledgements.values.map { it.completion }
            }
            CompletableFuture.allOf(*completions.toTypedArray()).handle { _, _ ->
                var failure: Throwable? = null
                completions.forEach { completion ->
                    try { completion.join() } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error && failure.cause !== error.cause) failure.addSuppressed(error)
                    }
                }
                pending.values.forEach { entry ->
                    if (!entry.snapshot.baseKnown && entry.completion.isCompletedExceptionally) {
                        val error = runCatching { entry.completion.join() }.exceptionOrNull()
                        entry.clearDurable?.completeExceptionally(requireNotNull(error))
                    }
                }
                failure
            }
        }.thenCompose { writeFailure ->
            val stopped = runCatching(stopExecutor).getOrElse { CompletableFuture.failedFuture(it) }
            stopped.handle { _, stopFailure ->
                val failure = writeFailure ?: stopFailure
                if (failure != null && stopFailure != null && failure !== stopFailure) failure.addSuppressed(stopFailure)
                if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure)
                Unit
            }
        }
        return closeCompletion.copy()
    }

    fun close() { closeAsync().join() }

    private fun submitAcknowledgement(snapshot: TrailChunkSnapshot): CompletableFuture<DurableAcknowledgementOutcome> =
        execute { journal.acknowledge(snapshot) }

    private fun <T> execute(operation: () -> T): CompletableFuture<T> =
        runCatching { CompletableFuture.supplyAsync(operation, executor) }
            .getOrElse { CompletableFuture.failedFuture(it) }

    private fun <T> track(completion: CompletableFuture<T>): CompletableFuture<T> {
        auxiliary += completion
        completion.whenComplete { _, _ -> auxiliary.remove(completion) }
        return completion
    }

    private fun observeClear(entry: Pending) {
        val snapshot = entry.snapshot
        val signal = entry.clearDurable ?: return
        if (snapshot.baseKnown) return
        entry.completion.whenComplete { _, error ->
            if (error == null && entry.snapshot === snapshot) signal.complete(Unit)
        }
    }

    private fun same(first: TrailChunkSnapshot, second: TrailChunkSnapshot): Boolean =
        sameTrailChunkSnapshot(first, second)

    private companion object { const val MAINTENANCE_STEPS = 32 }
}
