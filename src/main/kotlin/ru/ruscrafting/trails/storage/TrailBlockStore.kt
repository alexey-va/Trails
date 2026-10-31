package ru.ruscrafting.trails.storage

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.ruscrafting.trails.domain.TrailIdentity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TrailBlockState(
    val identity: TrailIdentity?,
    val walks: Int,
) {
    init { require(walks >= 0) { "walks must not be negative" } }
}

interface TrailBlockStore {
    fun read(block: Block): TrailBlockState?
    fun write(block: Block, state: TrailBlockState)
    fun clear(block: Block)
    fun trackedBlocks(chunk: Chunk, limit: Int = Int.MAX_VALUE): Collection<Block>
    fun nextTrackedBlock(chunk: Chunk, afterPosition: Int?): Pair<Int, Block>?
    fun isReady(chunk: Chunk): Boolean = true
    fun prepare(chunk: Chunk): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}

/** Native capture/publication is budgeted; decode, indexing, encoding and durable IO use workers. */
class ChunkPersistentTrailStore internal constructor(
    private val plugin: Plugin,
    private val nativeWork: BudgetedTaskQueue,
    private val recoveryJournal: TrailChunkRecoveryJournal,
    private val corruptionSink: (Chunk, Throwable) -> Unit = { chunk, error ->
        plugin.logger.warning("Discarding invalid trail block data in ${chunk.world.name} ${chunk.x},${chunk.z}: " +
            "${error.javaClass.simpleName}: ${error.message}")
    },
    private val persistenceFailureSink: (Chunk, Throwable) -> Unit = { chunk, error ->
        plugin.logger.severe("Could not persist trail block data in ${chunk.world.name} ${chunk.x},${chunk.z}: " +
            "${error.javaClass.simpleName}: ${error.message}")
    },
    private val persistence: TrailChunkPersistence = BukkitTrailChunkPersistence,
    private val asyncJournal: AsyncTrailChunkJournal = AsyncTrailChunkJournal(recoveryJournal),
    private val codec: BoundedAsyncExecutor = BoundedAsyncExecutor("Trails-chunk-codec"),
    private val onForeignClear: (UUID, Long, Int) -> Unit = { _, _, _ -> },
) : TrailBlockStore, Listener, AutoCloseable {
    internal val storageKey = NamespacedKey(plugin, "block_states_v1")
    private val scheduler = plugin.server.scheduler
    private var chunks = linkedMapOf<ChunkId, CachedChunk>()
    private var dirtyQueue = linkedSetOf<ChunkId>()
    private var loadedAfterStart = mutableSetOf<ChunkId>()
    private val retired = ConcurrentHashMap<ChunkId, CachedChunk>()
    private val failedFlushes = ConcurrentHashMap<ChunkId, Long>()
    private val cachedChunks = AtomicInteger()
    private val dirtyChunks = AtomicInteger()
    private val durabilityPendingChunks = AtomicInteger()
    private val corruptChunks = AtomicInteger()
    private val closed = AtomicBoolean()
    private val maintenancePending = AtomicBoolean()
    private val closeCompletion = CompletableFuture<Unit>()
    private val pendingOperations = AtomicInteger()
    private var pendingPositions = ConcurrentHashMap<PositionId, PendingPosition>()
    @Volatile private var foreignBarrier = CompletableFuture.completedFuture(Unit)
    private var foreignSequence = 0L
    private var ownSequence = 0L
    @Volatile private var foreignCursor: ForeignCursor? = null
    private val foreignRetryScheduled = AtomicBoolean()
    private var sequence = 0L

    override fun isReady(chunk: Chunk): Boolean {
        requirePrimaryThread()
        return cached(chunk).states != null
    }

    fun isSettled(chunk: Chunk): Boolean {
        requirePrimaryThread()
        val entry = cached(chunk)
        return foreignBarrier.isDone && entry.states != null && entry.tail.isDone &&
            !entry.tail.isCompletedExceptionally && settleForeign(entry)
    }

    /** Only a live in-flight operation can reuse an accepted, separately invalidated surface read. */
    internal fun hasPendingSpeedRead(chunk: Chunk): Boolean {
        requirePrimaryThread()
        val entry = cached(chunk)
        return entry.states != null && !entry.tail.isCompletedExceptionally && !foreignBarrier.isCompletedExceptionally &&
            (!entry.tail.isDone || !foreignBarrier.isDone || entry.foreignClearPending)
    }

    internal fun hasFailedSpeedRead(chunk: Chunk): Boolean {
        requirePrimaryThread()
        val entry = cached(chunk)
        return entry.ready.isCompletedExceptionally || entry.tail.isCompletedExceptionally || foreignBarrier.isCompletedExceptionally
    }

    override fun prepare(chunk: Chunk): CompletableFuture<Unit> {
        requirePrimaryThread()
        return cached(chunk).ready.copy()
    }

    /** Readiness/predecessor/foreign barriers never occupy a shared native queue slot. */
    internal fun whenReady(chunk: Chunk, guard: MutationGuard? = null, predecessor: CompletableFuture<*>? = null, operation: () -> Unit): CompletableFuture<Unit> {
        requirePrimaryThread()
        val entry = cached(chunk)
        if (pendingOperations.incrementAndGet() > MAX_PENDING_OPERATIONS) {
            pendingOperations.decrementAndGet()
            guard?.close()
            return CompletableFuture.failedFuture(RejectedExecutionException("trail operation capacity is exhausted"))
        }
        val chunkPredecessor = entry.tail
        val result = CompletableFuture<Unit>()
        entry.tail = result
        fun admit() {
            CompletableFuture.allOf(entry.ready, chunkPredecessor.handle { _, _ -> Unit },
                predecessor?.handle { _, _ -> Unit } ?: CompletableFuture.completedFuture(Unit)).thenCompose { awaitForeignBarrier() }
                .thenCompose { asyncJournal.awaitClear(entry.id.worldId, entry.id.chunkKey) }.thenCompose {
                nativeWork.call({ !closed.get() && entry.valid && chunks[entry.id] === entry }) {
                    if (!foreignBarrier.isDone) false else {
                        check(settleForeign(entry)) { "foreign trail clear has not reached durable acknowledgement" }
                        if (guard == null || guard.isCurrent()) operation()
                        true
                    }
                }
            }.whenComplete { done, error ->
                if (error != null) result.completeExceptionally(error)
                else if (done == true) result.complete(Unit)
                else admit()
            }
        }
        result.whenComplete { _, _ -> pendingOperations.decrementAndGet(); guard?.close() }
        admit()
        return result.copy()
    }

    /** Native world changes invalidate older deferred walks without confusing this store's own progression. */
    internal fun mutationGuard(block: Block): MutationGuard {
        requirePrimaryThread()
        val entry = cached(block.chunk)
        val key = PositionId(entry.id, position(block))
        val registry = pendingPositions
        val pending = requireNotNull(registry.compute(key) { _, previous ->
            (previous ?: PendingPosition()).also { it.count++ }
        })
        val capturedSequence = foreignSequence
        val ownRevision = pending.ownRevision
        val before = block.blockData.asString
        return MutationGuard({
            !closed.get() && entry.valid && chunks[entry.id] === entry &&
                pending.foreignSequence <= capturedSequence &&
                block.world.isChunkLoaded(block.x shr 4, block.z shr 4) &&
                block.blockData.asString.let { actual ->
                    actual == before || pending.ownRevision > ownRevision && pending.ownData == actual
                }
        }, {
            registry.computeIfPresent(key) { _, current ->
                if (current !== pending) current else current.takeIf { --it.count > 0 }
            }
        })
    }

    private fun awaitForeignBarrier(): CompletableFuture<Unit> {
        val observed = foreignBarrier
        return observed.handle { _, _ -> Unit }.thenCompose {
            if (foreignBarrier === observed) CompletableFuture.completedFuture(Unit) else awaitForeignBarrier()
        }
    }

    override fun read(block: Block): TrailBlockState? {
        requirePrimaryThread()
        return ready(block.chunk).states!!.get(position(block))
    }

    override fun write(block: Block, state: TrailBlockState) {
        requirePrimaryThread()
        TrailChunkCodec.validateState(state)
        val entry = ready(block.chunk)
        val states = requireNotNull(entry.states)
        val position = position(block)
        val previous = states[position]
        if (previous == null) check(entry.count.get() < TrailChunkCodec.MAX_ENTRIES) { "trail chunk entry capacity is exhausted" }
        noteOwnMutation(entry, position, block)
        if (previous == state) return
        states[position] = state
        if (previous == null) entry.count.incrementAndGet()
        markDirty(entry)
    }

    override fun clear(block: Block) {
        requirePrimaryThread()
        val entry = ready(block.chunk)
        val position = position(block)
        noteOwnMutation(entry, position, block)
        if (entry.states!!.remove(position) != null) {
            entry.count.decrementAndGet()
            markDirty(entry)
        }
    }

    private fun noteOwnMutation(entry: CachedChunk, position: Int, block: Block) {
        pendingPositions[PositionId(entry.id, position)]?.let {
            it.ownRevision = ++ownSequence
            it.ownData = block.blockData.asString
        }
    }

    private fun settleForeign(entry: CachedChunk): Boolean {
        if (!entry.foreignClearPending) return true
        if (asyncJournal.hasUncommittedClear(entry.id.worldId, entry.id.chunkKey)) return false
        entry.journalRevision.set(recoveryJournal.load(entry.id.worldId, entry.id.chunkKey)?.revision ?: 0L)
        entry.foreignClearPending = false
        return true
    }

    /**
     * Retains the event's reference snapshot once; native coordinates are captured one per step.
     * The unavoidable event-list copy is proportional to the native event list, with no new gameplay
     * cutoff. No event object or Bukkit getter is used by a worker, including after chunk unload.
     */
    internal fun <T : Any> clearForeign(values: Collection<T>, blockOf: (T) -> Block): CompletableFuture<Unit> {
        requirePrimaryThread()
        check(!closed.get()) { "trail block store is closed" }
        if (values.isEmpty()) return CompletableFuture.completedFuture(Unit)
        if ((foreignCursor?.batches?.size ?: 0) >= MAX_PENDING_OPERATIONS)
            throw RejectedExecutionException("trail foreign event capacity is exhausted")
        foreignSequence = Math.addExact(foreignSequence, 1L)
        val eventSequence = foreignSequence
        val iterator = values.toList().iterator()
        val batch = ForeignBatch(eventSequence, iterator::hasNext) { if (iterator.hasNext()) blockOf(iterator.next()) else null }
        val cursor = foreignCursor ?: ForeignCursor(CompletableFuture<Unit>()).also {
            foreignCursor = it
            foreignBarrier = it.completion
        }
        cursor.batches.addLast(batch)
        scheduleForeign(cursor)
        return cursor.completion.copy()
    }

    private fun scheduleForeign(cursor: ForeignCursor) {
        if (closed.get() || foreignCursor !== cursor || cursor.running || cursor.waitingForIo) return
        cursor.running = true
        val admitted = nativeWork.submit({ !closed.get() && foreignCursor === cursor }) { foreignStep(cursor) }
        admitted.whenComplete { _, error ->
            cursor.running = false
            if (error != null) {
                if (!closed.get() && error.rootCause() is RejectedExecutionException) retryForeign(cursor)
                else cursor.completion.completeExceptionally(error)
            } else if (!closed.get() && foreignCursor === cursor && !cursor.waitingForIo && cursor.applying != null) {
                scheduleForeign(cursor)
            }
        }
    }

    /** One retry tick for the whole retained barrier; rejected work keeps its numeric intent. */
    private fun retryForeign(cursor: ForeignCursor) {
        if (closed.get() || foreignCursor !== cursor || !foreignRetryScheduled.compareAndSet(false, true)) return
        try {
            scheduler.runTaskLater(plugin, Runnable {
                foreignRetryScheduled.set(false)
                if (!closed.get() && foreignCursor === cursor) {
                    resumeForeign(cursor)
                    scheduleForeign(cursor)
                }
            }, 1L)
        } catch (error: Throwable) {
            foreignRetryScheduled.set(false)
            cursor.completion.completeExceptionally(error)
        }
    }

    private fun resumeForeign(cursor: ForeignCursor) {
        val resumed = cursor.resume.getAndSet(null) ?: return
        if (cursor.failure == null) cursor.failure = resumed.failure
        cursor.waitingForIo = false
        if (resumed.retryAdmission) return
        cursor.applying = resumed.positions.asSequence().flatMap { (id, positions) -> positions.asSequence().map { id to it } }.iterator()
    }

    /** Only fixed native work occupies the shared queue; I/O returns the cursor's slot. */
    private fun foreignStep(cursor: ForeignCursor): Boolean {
        val applying = cursor.applying
        if (applying != null) {
            if (applying.hasNext()) {
                val (id, position) = applying.next()
                onForeignClear(id.worldId, id.chunkKey, position)
                chunks[id]?.let { entry ->
                    entry.foreignEpoch.incrementAndGet()
                    entry.foreignClearPending = true
                    if (entry.states?.remove(position) != null) entry.count.decrementAndGet()
                    if (entry.states != null) markDirty(entry)
                }
                return false
            }
            cursor.applying = null
            cursor.staged = linkedMapOf()
        }
        var batch = cursor.current
        if (batch == null) {
            batch = cursor.batches.removeFirstOrNull()
            cursor.current = batch
        }
        if (batch != null) {
            val block = batch.next()
            if (block != null) {
                val id = ChunkId(block.world.uid, chunkKey(block.x shr 4, block.z shr 4))
                val packed = position(block)
                pendingPositions[PositionId(id, packed)]?.let { it.foreignSequence = maxOf(it.foreignSequence, batch.sequence) }
                chunks[id]?.let { it.foreignEpoch.incrementAndGet(); it.foreignClearPending = true }
                cursor.staged.getOrPut(id) { linkedSetOf() }.add(packed)
                return false
            }
            cursor.current = null
            return false
        }
        if (cursor.staged.isNotEmpty()) {
            val captured = cursor.staged
            cursor.waitingForIo = true
            codec.submit {
                val writes = captured.mapValues { (id, positions) ->
                    runCatching { asyncJournal.clearPositions(id.worldId, id.chunkKey, positions) }
                        .getOrElse { CompletableFuture.failedFuture(it) }
                }
                CompletableFuture.allOf(*writes.values.toTypedArray()).handle { _, _ ->
                    var failure: Throwable? = null
                    writes.values.forEach { write ->
                        try { write.join() } catch (error: Throwable) {
                            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                        }
                    }
                    failure
                }
            }.thenCompose { it }.whenComplete { failure, error ->
                if (error?.rootCause() is RejectedExecutionException) {
                    // The codec lane has not admitted the numeric intent. Keep the barrier and
                    // the staged coordinates until one coalesced retry tick can dispatch them.
                    cursor.resume.set(ForeignResume(captured, null, retryAdmission = true))
                    retryForeign(cursor)
                    return@whenComplete
                }
                // This callback only prepares a plain numeric iterator. Native state is resumed on Main.
                cursor.resume.set(ForeignResume(captured, error ?: failure))
                val next = nativeWork.call({ !closed.get() && foreignCursor === cursor }) {
                    resumeForeign(cursor)
                    scheduleForeign(cursor)
                }
                next.whenComplete { _, admissionFailure ->
                    if (admissionFailure != null) {
                        if (!closed.get() && admissionFailure.rootCause() is RejectedExecutionException) {
                            // The immutable numeric batch stays retained; the one retry tick resumes it.
                            retryForeign(cursor)
                        } else cursor.completion.completeExceptionally(admissionFailure)
                    }
                }
            }
            return true
        }
        foreignCursor = null
        val failure = cursor.failure
        if (failure == null) cursor.completion.complete(Unit) else cursor.completion.completeExceptionally(failure)
        return true
    }

    override fun trackedBlocks(chunk: Chunk, limit: Int): Collection<Block> {
        requirePrimaryThread()
        require(limit >= 0) { "tracked block limit must not be negative" }
        val states = cached(chunk).states ?: return emptyList()
        return states.keys.asSequence().take(limit).map { nativeBlock(chunk, it) }.toList()
    }

    override fun nextTrackedBlock(chunk: Chunk, afterPosition: Int?): Pair<Int, Block>? {
        requirePrimaryThread()
        val states = cached(chunk).states ?: return null
        val position = if (afterPosition == null) states.firstEntry()?.key else states.higherKey(afterPosition)
        return position?.let { it to nativeBlock(chunk, it) }
    }

    /** Shares at most eight encode attempts between loaded and retired chunks; Paper never waits for I/O. */
    fun flushDirty(): Int {
        requirePrimaryThread()
        check(!closed.get()) { "trail block store is closed" }
        var started = 0
        dirtyQueue.take(8).forEach { id ->
            dirtyQueue.remove(id)
            val detached = retired[id]
            val entry = chunks[id]
            if (detached != null) {
                if (persistDetached(detached)) started++
            } else if (entry != null && flushDurably(entry)) started++
            if (retired.containsKey(id) || entry?.dirty == true || entry?.durabilityPending == true) dirtyQueue += id
        }
        if (maintenancePending.compareAndSet(false, true)) {
            codec.submit { asyncJournal.reap() }.whenComplete { _, error ->
                maintenancePending.set(false)
                if (error != null && !closed.get()) plugin.logger.severe("Trail journal maintenance failed: $error")
            }
        }
        return started
    }

    fun healthContribution(): RuntimeHealthContribution {
        val failed = failedFlushes.size + asyncJournal.failureCount()
        return RuntimeHealthContribution(
            state = if (corruptChunks.get() == 0 && failed == 0) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
            recoveryBacklog = maxOf(dirtyChunks.get(), durabilityPendingChunks.get()) +
                maxOf(recoveryJournal.size(), asyncJournal.pendingCount()) + retired.size,
            activeLeases = cachedChunks.get(),
            schemas = mapOf("block-storage" to TrailChunkCodec.SCHEMA_VERSION),
            dependencies = mapOf("chunk-pdc" to (failed == 0)),
        )
    }

    internal fun cachedChunkCount(): Int = cachedChunks.get()
    internal fun pendingOperationCount(): Int = pendingOperations.get()
    internal fun pendingPositionCount(): Int = pendingPositions.size

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        requirePrimaryThread()
        if (closed.get()) return
        val id = id(event.chunk)
        if (failedFlushes.containsKey(id) || asyncJournal.load(id.worldId, id.chunkKey) != null || retired.containsKey(id)) {
            loadedAfterStart += id
            prepare(event.chunk)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        requirePrimaryThread()
        val id = id(event.chunk)
        val entry = chunks[id] ?: return
        if (entry.dirty || entry.durabilityPending || asyncJournal.load(id.worldId, id.chunkKey) != null) event.isSaveChunk = true
        entry.valid = false
        entry.ready.completeExceptionally(IllegalStateException("trail chunk unloaded during preparation"))
        if (entry.dirty || entry.durabilityPending) {
            retired[id] = entry
            persistDetached(entry)
        }
        evict(entry)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldSave(@Suppress("UNUSED_PARAMETER") event: WorldSaveEvent) { flushDirty() }

    /** O(1) native detach. The durable recovery journal is authoritative when no shutdown tick remains. */
    override fun close() { closeAsync() }

    fun closeAsync(): CompletableFuture<Unit> {
        requirePrimaryThread()
        if (!closed.compareAndSet(false, true)) return closeCompletion.copy()
        val detached = chunks
        val detachedForeign = foreignCursor
        // Only Main may inspect the detached native-reference cursor. Already captured numeric
        // clears are drained below; an unobserved event tail cannot be reported as durable.
        val unfinishedForeignCapture = detachedForeign?.let {
            it.current?.hasNext?.invoke() == true || it.batches.isNotEmpty()
        } == true
        foreignCursor = null
        detachedForeign?.completion?.completeExceptionally(IllegalStateException("trail foreign capture stopped during shutdown"))
        chunks = linkedMapOf()
        dirtyQueue = linkedSetOf()
        loadedAfterStart = mutableSetOf()
        pendingPositions = ConcurrentHashMap()
        cachedChunks.set(0)
        dirtyChunks.set(0)
        durabilityPendingChunks.set(0)
        corruptChunks.set(0)
        codec.closeAsync {
            var failure: Throwable? = if (unfinishedForeignCapture)
                IllegalStateException("trail foreign event capture was incomplete at shutdown") else null
            fun attempt(operation: () -> Unit) {
                try { operation() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                }
            }
            // No Bukkit getter or native reference is consulted by this finalizer.
            detachedForeign?.staged?.forEach { (id, positions) ->
                if (positions.isNotEmpty()) attempt { asyncJournal.clearPositions(id.worldId, id.chunkKey, positions).join() }
            }
            val latest = HashMap<ChunkId, CachedChunk>()
            (retired.values.asSequence() + detached.values.asSequence()).forEach { entry ->
                if ((latest[entry.id]?.sequence ?: -1) < entry.sequence) latest[entry.id] = entry
            }
            latest.values.forEach { entry ->
                if (entry.dirty || entry.durabilityPending) attempt {
                    val encoded = requireNotNull(encodeStable(entry, foldRecovery = true)) { "sealed trail revision changed" }
                    asyncJournal.commit(encoded.snapshot).join()
                }
            }
            attempt { asyncJournal.closeAsync().join() }
            failure?.let { throw it }
        }.whenComplete { _, failure ->
            if (failure == null) closeCompletion.complete(Unit) else closeCompletion.completeExceptionally(failure)
        }
        return closeCompletion.copy()
    }

    private fun ready(chunk: Chunk): CachedChunk = cached(chunk).also {
        check(it.states != null) { "trail chunk must finish asynchronous preparation before native use" }
    }

    private fun cached(chunk: Chunk): CachedChunk {
        check(!closed.get()) { "trail block store is closed" }
        val id = id(chunk)
        val previous = chunks[id]
        if (previous != null) {
            val failure = if (previous.ready.isCompletedExceptionally) runCatching { previous.ready.join() }.exceptionOrNull()?.rootCause() else null
            if (failure !is RejectedExecutionException) return previous
            previous.valid = false
            evict(previous)
        }
        return CachedChunk(chunk, id, ++sequence).also { entry ->
            chunks[id] = entry
            cachedChunks.incrementAndGet()
            prepareEntry(entry).whenComplete { _, error ->
                if (error != null) {
                    entry.ready.completeExceptionally(error)
                    if (!closed.get() && entry.valid) {
                        markFlushFailure(entry)
                        plugin.logger.severe("Could not prepare trail chunk $id: $error")
                    }
                }
            }
        }
    }

    private fun prepareEntry(entry: CachedChunk): CompletableFuture<Unit> =
        awaitForeignBarrier().thenCompose { asyncJournal.awaitClear(entry.id.worldId, entry.id.chunkKey) }.thenCompose {
            nativeWork.call({ !closed.get() && entry.valid && chunks[entry.id] === entry }) {
                // One indivisible native BYTE_ARRAY extraction. Validation and all indexing stay on the codec worker.
                NativeCapture(persistence.read(entry.chunk, storageKey), entry.chunk.world.minHeight,
                    entry.chunk.world.maxHeight, entry.id in loadedAfterStart, entry.foreignEpoch.get())
            }
        }.thenCompose { captured ->
            codec.submit { decode(entry, captured) }.thenCompose { prepared ->
                nativeWork.call({ !closed.get() && entry.valid && chunks[entry.id] === entry }) {
                    if (prepared == null || !foreignBarrier.isDone || entry.foreignEpoch.get() != captured.foreignEpoch) false
                    else {
                        entry.states = prepared.states
                        entry.count.set(prepared.count)
                        entry.journalRevision.set(prepared.journalRevision)
                        entry.foreignClearPending = false
                        entry.corrupt = prepared.corruption != null
                        if (entry.corrupt) {
                            corruptChunks.incrementAndGet()
                            corruptionSink(entry.chunk, requireNotNull(prepared.corruption))
                        }
                        if (prepared.dirty) markDirty(entry) else clearFlushFailure(entry)
                        loadedAfterStart.remove(entry.id)
                        entry.ready.complete(Unit)
                        true
                    }
                }.thenCompose { published ->
                    if (published) CompletableFuture.completedFuture(Unit) else prepareEntry(entry)
                }
            }
        }

    private fun decode(entry: CachedChunk, captured: NativeCapture): Prepared? {
        repeat(8) {
            try {
                var recovery = asyncJournal.loadDurable(entry.id.worldId, entry.id.chunkKey).join()
                val detached = retired[entry.id]
                var corruption: Throwable? = null
                val states = if (detached?.states != null) {
                    val revision = detached.revision.get()
                    LinkedHashMap(requireNotNull(detached.states)).also {
                        if (detached.revision.get() != revision) throw TrailChunkRevisionChanged("detached trail revision changed")
                    }
                } else if (recovery?.baseKnown == true) TrailChunkCodec.decode(recovery.encodedStates)
                else try {
                    captured.bytes?.let(TrailChunkCodec::decode) ?: linkedMapOf()
                } catch (error: Throwable) {
                    corruption = error
                    linkedMapOf()
                }
                validatePositions(states.keys, captured.minimumY, captured.maximumY)
                if (recovery?.clearedPositions?.isNotEmpty() == true) {
                    recovery.clearedPositions.forEach(states::remove)
                    val folded = TrailChunkSnapshot(entry.id.worldId, entry.id.chunkKey, TrailChunkCodec.encode(states), revision = recovery.revision)
                    asyncJournal.commit(folded).join()
                    recovery = asyncJournal.loadDurable(entry.id.worldId, entry.id.chunkKey).join()
                    if (recovery == null || recovery.clearedPositions.isNotEmpty() || folded.persistedRevision != recovery.revision)
                        throw TrailChunkRevisionChanged("trail clear changed while folding the native base")
                }
                val encoded = TrailChunkCodec.encode(states)
                val persisted = if (states.isEmpty()) captured.bytes == null else encoded.contentEquals(captured.bytes ?: byteArrayOf())
                var journalRevision = recovery?.revision ?: 0L
                if (recovery != null && persisted && captured.loadedLater && !closed.get() && entry.valid) {
                    if (asyncJournal.acknowledge(recovery).join() == ru.arc.persistence.DurableAcknowledgementOutcome.CONTENT_MISMATCH)
                        throw TrailChunkRevisionChanged("trail recovery changed before exact PDC acknowledgement")
                    journalRevision = 0L
                }
                return Prepared(ConcurrentSkipListMap(states), states.size, !persisted || detached != null,
                    corruption, journalRevision)
            } catch (error: Throwable) {
                if (error.rootCause() !is TrailChunkRevisionChanged &&
                    !asyncJournal.hasUncommittedClear(entry.id.worldId, entry.id.chunkKey)) throw error
            }
        }
        return null
    }

    private fun validatePositions(positions: Collection<Int>, minimumY: Int, maximumY: Int) {
        positions.forEach { if (TrailBlockPosition.y(it) !in minimumY until maximumY) throw TrailChunkFormatException("block position is outside the world height") }
    }

    private fun flushDurably(entry: CachedChunk): Boolean {
        if (!foreignBarrier.isDone || !settleForeign(entry)) return false
        if ((!entry.dirty && !entry.durabilityPending) || !entry.flushing.compareAndSet(false, true)) return false
        codec.submit {
            val encoded = encodeStable(entry)
            if (encoded == null) CompletableFuture.completedFuture<Encoded?>(null)
            else asyncJournal.commit(encoded.snapshot).thenApply<Encoded?> { encoded }
        }.thenCompose { it }.whenComplete { encoded, error ->
            nativeWork.call({ !closed.get() && entry.valid && chunks[entry.id] === entry }) {
                try {
                    if (error != null && error.rootCause() !is TrailChunkRevisionChanged) recordFlushFailure(entry, error)
                    else if (encoded != null && entry.revision.get() == encoded.revision &&
                        entry.foreignEpoch.get() == encoded.foreignEpoch && foreignBarrier.isDone &&
                        encoded.snapshot.persistedRevision >= 0) {
                        entry.journalRevision.set(encoded.snapshot.persistedRevision)
                        persistence.write(entry.chunk, storageKey, encoded.snapshot.encodedStates.takeUnless { encoded.empty })
                        markPdcFlushed(entry)
                        clearFlushFailure(entry)
                    }
                } catch (failure: Throwable) { recordFlushFailure(entry, failure) }
            }.whenComplete { _, _ -> entry.flushing.set(false) }
        }
        return true
    }

    private fun persistDetached(entry: CachedChunk): Boolean {
        if (!entry.flushing.compareAndSet(false, true)) return false
        codec.submit {
            val encoded = requireNotNull(encodeStable(entry, foldRecovery = true)) { "detached trail revision changed" }
            asyncJournal.commit(encoded.snapshot)
        }.thenCompose { it }.whenComplete { _, error ->
            try {
                if (error == null) {
                    retired.remove(entry.id, entry)
                    clearFlushFailure(entry)
                } else if (markFlushFailure(entry)) {
                    plugin.logger.severe("Could not persist unloaded trail chunk ${entry.id}: $error")
                }
            } finally { entry.flushing.set(false) }
        }
        return true
    }

    private fun encodeStable(entry: CachedChunk, foldRecovery: Boolean = false): Encoded? {
        val revision = entry.revision.get()
        val foreignEpoch = entry.foreignEpoch.get()
        var journalRevision = entry.journalRevision.get()
        val states = LinkedHashMap(requireNotNull(entry.states))
        if (entry.revision.get() != revision || entry.foreignEpoch.get() != foreignEpoch) return null
        if (foldRecovery) {
            val recovery = asyncJournal.loadDurable(entry.id.worldId, entry.id.chunkKey).join()
            if (recovery != null) {
                recovery.clearedPositions.forEach(states::remove)
                journalRevision = recovery.revision
            } else journalRevision = 0L
        }
        val encoded = TrailChunkCodec.encode(states)
        if (entry.revision.get() != revision || entry.foreignEpoch.get() != foreignEpoch ||
            !foldRecovery && entry.journalRevision.get() != journalRevision) return null
        return Encoded(revision, TrailChunkSnapshot(entry.id.worldId, entry.id.chunkKey, encoded, revision = journalRevision), states.isEmpty(), foreignEpoch)
    }

    private fun markDirty(entry: CachedChunk) {
        entry.revision.incrementAndGet()
        dirtyQueue += entry.id
        if (!entry.dirty) { entry.dirty = true; dirtyChunks.incrementAndGet() }
        if (!entry.durabilityPending) { entry.durabilityPending = true; durabilityPendingChunks.incrementAndGet() }
    }

    private fun markPdcFlushed(entry: CachedChunk) {
        if (entry.dirty) { entry.dirty = false; dirtyChunks.decrementAndGet() }
        if (entry.durabilityPending) { entry.durabilityPending = false; durabilityPendingChunks.decrementAndGet() }
        if (entry.corrupt) { entry.corrupt = false; corruptChunks.decrementAndGet() }
    }

    private fun recordFlushFailure(entry: CachedChunk, error: Throwable) {
        if (markFlushFailure(entry)) persistenceFailureSink(entry.chunk, error)
    }

    private fun markFlushFailure(entry: CachedChunk): Boolean {
        var first = false
        failedFlushes.compute(entry.id) { _, previous ->
            first = previous == null
            if (previous == null || previous < entry.sequence) entry.sequence else previous
        }
        return first
    }

    private fun clearFlushFailure(entry: CachedChunk) {
        failedFlushes.computeIfPresent(entry.id) { _, previous -> previous.takeIf { it > entry.sequence } }
    }

    private fun evict(entry: CachedChunk) {
        if (chunks.remove(entry.id, entry)) {
            if (!retired.containsKey(entry.id)) dirtyQueue.remove(entry.id)
            loadedAfterStart.remove(entry.id)
            cachedChunks.decrementAndGet()
            if (entry.dirty) dirtyChunks.decrementAndGet()
            if (entry.durabilityPending) durabilityPendingChunks.decrementAndGet()
            if (entry.corrupt) corruptChunks.decrementAndGet()
        }
    }

    private fun nativeBlock(chunk: Chunk, packed: Int): Block =
        chunk.getBlock(TrailBlockPosition.localX(packed), TrailBlockPosition.y(packed), TrailBlockPosition.localZ(packed))
    private fun position(block: Block): Int = TrailBlockPosition.pack(block.x and 15, block.y, block.z and 15)
    private fun id(chunk: Chunk): ChunkId = ChunkId(chunk.world.uid, chunk.chunkKey)
    private fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffff_ffffL) or (z.toLong() shl 32)
    private fun requirePrimaryThread() { check(Bukkit.isPrimaryThread()) { "trail block storage must be accessed on the Paper primary thread" } }

    private data class ChunkId(val worldId: UUID, val chunkKey: Long)
    private class CachedChunk(val chunk: Chunk, val id: ChunkId, val sequence: Long) {
        @Volatile var valid = true
        @Volatile var states: ConcurrentSkipListMap<Int, TrailBlockState>? = null
        val ready = CompletableFuture<Unit>()
        var tail = CompletableFuture.completedFuture(Unit)
        val foreignEpoch = AtomicLong()
        val journalRevision = AtomicLong()
        var foreignClearPending = false
        val revision = AtomicLong()
        val count = AtomicInteger()
        val flushing = AtomicBoolean()
        @Volatile var dirty = false
        @Volatile var durabilityPending = false
        var corrupt = false
    }
    private data class NativeCapture(val bytes: ByteArray?, val minimumY: Int, val maximumY: Int, val loadedLater: Boolean, val foreignEpoch: Long)
    private data class Prepared(val states: ConcurrentSkipListMap<Int, TrailBlockState>, val count: Int, val dirty: Boolean, val corruption: Throwable?, val journalRevision: Long)
    private data class Encoded(val revision: Long, val snapshot: TrailChunkSnapshot, val empty: Boolean, val foreignEpoch: Long)
    private data class PositionId(val chunk: ChunkId, val position: Int)
    private class PendingPosition {
        var count = 0
        @Volatile var foreignSequence = 0L
        @Volatile var ownRevision = 0L
        @Volatile var ownData: String? = null
    }
    internal class MutationGuard(val isCurrent: () -> Boolean, private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    private data class ForeignBatch(val sequence: Long, val hasNext: () -> Boolean, val next: () -> Block?)
    private data class ForeignResume(val positions: Map<ChunkId, Set<Int>>, val failure: Throwable?, val retryAdmission: Boolean = false)
    private class ForeignCursor(val completion: CompletableFuture<Unit>) {
        val batches = ArrayDeque<ForeignBatch>()
        var current: ForeignBatch? = null
        var staged = linkedMapOf<ChunkId, MutableSet<Int>>()
        var applying: Iterator<Pair<ChunkId, Int>>? = null
        val resume = AtomicReference<ForeignResume?>()
        var running = false
        var waitingForIo = false
        var failure: Throwable? = null
    }
    private companion object { const val MAX_PENDING_OPERATIONS = 4096 }
}

private fun Throwable.rootCause(): Throwable =
    if ((this is CompletionException || this is java.util.concurrent.ExecutionException) && cause != null) requireNotNull(cause).rootCause() else this
