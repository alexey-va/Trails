package ru.ruscrafting.trails.storage

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.ruscrafting.trails.domain.TrailIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class TrailBlockState(
    val identity: TrailIdentity?,
    val walks: Int,
) {
    init {
        require(walks >= 0) { "walks must not be negative" }
    }
}

interface TrailBlockStore {
    fun read(block: Block): TrailBlockState?

    fun write(block: Block, state: TrailBlockState)

    fun clear(block: Block)

    fun trackedBlocks(chunk: Chunk): Collection<Block>
}

/**
 * Main-thread chunk-local storage for ordinary block positions.
 *
 * Each loaded chunk is decoded once into memory. Mutations are coalesced until
 * [flushDirty], while unload, world-save, and [close] synchronously flush the
 * complete checksum-protected payload before evicting cache state.
 */
class ChunkPersistentTrailStore internal constructor(
    private val plugin: Plugin,
    private val corruptionSink: (Chunk, Throwable) -> Unit = { chunk, error ->
        plugin.logger.warning(
            "Discarding invalid trail block data in ${chunk.world.name} ${chunk.x},${chunk.z}: " +
                "${error.javaClass.simpleName}: ${error.message}",
        )
    },
    private val persistenceFailureSink: (Chunk, Throwable) -> Unit = { chunk, error ->
        plugin.logger.severe(
            "Could not persist trail block data in ${chunk.world.name} ${chunk.x},${chunk.z}: " +
                "${error.javaClass.simpleName}: ${error.message}",
        )
    },
) : TrailBlockStore, Listener, AutoCloseable {
    internal val storageKey = NamespacedKey(plugin, "block_states_v1")
    private val chunks = linkedMapOf<ChunkId, CachedChunk>()
    private val failedFlushes = mutableSetOf<ChunkId>()
    private val cachedChunks = AtomicInteger()
    private val dirtyChunks = AtomicInteger()
    private val corruptChunks = AtomicInteger()
    private val failedChunks = AtomicInteger()
    private var closed = false

    override fun read(block: Block): TrailBlockState? {
        requirePrimaryThread()
        return cached(block.chunk).states[position(block)]
    }

    override fun write(block: Block, state: TrailBlockState) {
        requirePrimaryThread()
        TrailChunkCodec.validateState(state)
        val chunk = cached(block.chunk)
        val position = position(block)
        if (position !in chunk.states) {
            check(chunk.states.size < TrailChunkCodec.MAX_ENTRIES) { "trail chunk entry capacity is exhausted" }
        }
        if (chunk.states[position] == state) return
        chunk.states[position] = state
        markDirty(chunk)
    }

    override fun clear(block: Block) {
        requirePrimaryThread()
        val chunk = cached(block.chunk)
        if (chunk.states.remove(position(block)) != null) markDirty(chunk)
    }

    override fun trackedBlocks(chunk: Chunk): Collection<Block> {
        requirePrimaryThread()
        val cached = cached(chunk)
        return cached.states.keys.map { packed ->
            chunk.getBlock(
                TrailBlockPosition.localX(packed),
                TrailBlockPosition.y(packed),
                TrailBlockPosition.localZ(packed),
            )
        }
    }

    /** Flushes every dirty loaded chunk once and returns the successful count. */
    fun flushDirty(): Int {
        requirePrimaryThread()
        check(!closed) { "trail block store is closed" }
        var flushed = 0
        chunks.values.filter(CachedChunk::dirty).toList().forEach { cached ->
            runCatching { flush(cached) }
                .onSuccess { flushed++ }
                .onFailure { error -> recordFlushFailure(cached, error) }
        }
        return flushed
    }

    fun healthContribution(): RuntimeHealthContribution {
        val corrupt = corruptChunks.get()
        val failed = failedChunks.get()
        return RuntimeHealthContribution(
            state = if (corrupt == 0 && failed == 0) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
            recoveryBacklog = dirtyChunks.get(),
            activeLeases = cachedChunks.get(),
            schemas = mapOf("block-storage" to TrailChunkCodec.SCHEMA_VERSION),
            dependencies = mapOf("chunk-pdc" to (failed == 0)),
        )
    }

    internal fun cachedChunkCount(): Int = cachedChunks.get()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        requirePrimaryThread()
        val id = id(event.chunk)
        val cached = chunks[id] ?: return
        if (cached.dirty) {
            runCatching { flush(cached) }.onFailure { error -> recordFlushFailure(cached, error) }
        }
        evict(id, cached)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldSave(event: WorldSaveEvent) {
        requirePrimaryThread()
        flushWorld(event.world)
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        var firstFailure: Throwable? = null
        chunks.values.filter(CachedChunk::dirty).toList().forEach { cached ->
            try {
                flush(cached)
            } catch (failure: Throwable) {
                recordFlushFailure(cached, failure)
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        chunks.toMap().forEach { (id, cached) -> evict(id, cached) }
        closed = true
        firstFailure?.let { throw IllegalStateException("Could not flush every trail chunk", it) }
    }

    private fun flushWorld(world: World) {
        chunks.values.filter { it.chunk.world.uid == world.uid && it.dirty }.toList().forEach { cached ->
            runCatching { flush(cached) }.onFailure { error -> recordFlushFailure(cached, error) }
        }
    }

    private fun cached(chunk: Chunk): CachedChunk {
        check(!closed) { "trail block store is closed" }
        val id = id(chunk)
        return chunks.getOrPut(id) {
            val encoded = chunk.persistentDataContainer.get(storageKey, PersistentDataType.BYTE_ARRAY)
            var corrupt = false
            val states =
                if (encoded == null) {
                    linkedMapOf()
                } else {
                    runCatching { TrailChunkCodec.decode(encoded).also { validatePositions(chunk, it.keys) } }
                        .getOrElse { error ->
                            corrupt = true
                            corruptChunks.incrementAndGet()
                            corruptionSink(chunk, error)
                            linkedMapOf()
                        }
                }
            cachedChunks.incrementAndGet()
            CachedChunk(chunk, states, dirty = corrupt, corrupt = corrupt).also {
                if (corrupt) dirtyChunks.incrementAndGet()
            }
        }
    }

    private fun validatePositions(
        chunk: Chunk,
        positions: Collection<Int>,
    ) {
        positions.forEach { packed ->
            val y = TrailBlockPosition.y(packed)
            if (y !in chunk.world.minHeight until chunk.world.maxHeight) {
                throw TrailChunkFormatException("block position is outside the world height")
            }
        }
    }

    private fun flush(cached: CachedChunk) {
        if (!cached.dirty) return
        val container = cached.chunk.persistentDataContainer
        if (cached.states.isEmpty()) {
            container.remove(storageKey)
        } else {
            container.set(storageKey, PersistentDataType.BYTE_ARRAY, TrailChunkCodec.encode(cached.states))
        }
        cached.dirty = false
        dirtyChunks.decrementAndGet()
        if (cached.corrupt) {
            cached.corrupt = false
            corruptChunks.decrementAndGet()
        }
        val id = id(cached.chunk)
        if (failedFlushes.remove(id)) failedChunks.decrementAndGet()
    }

    private fun markDirty(cached: CachedChunk) {
        if (cached.dirty) return
        cached.dirty = true
        dirtyChunks.incrementAndGet()
    }

    private fun recordFlushFailure(
        cached: CachedChunk,
        error: Throwable,
    ) {
        val id = id(cached.chunk)
        if (failedFlushes.add(id)) {
            failedChunks.incrementAndGet()
            persistenceFailureSink(cached.chunk, error)
        }
    }

    private fun evict(
        id: ChunkId,
        cached: CachedChunk,
    ) {
        if (chunks.remove(id) == null) return
        cachedChunks.decrementAndGet()
        if (cached.dirty) dirtyChunks.decrementAndGet()
        if (cached.corrupt) corruptChunks.decrementAndGet()
        if (failedFlushes.remove(id)) failedChunks.decrementAndGet()
    }

    private fun position(block: Block): Int =
        TrailBlockPosition.pack(block.x and 15, block.y, block.z and 15)

    private fun id(chunk: Chunk): ChunkId = ChunkId(chunk.world.uid, chunk.chunkKey)

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "trail block storage must be accessed on the Paper primary thread" }
    }

    private data class ChunkId(
        val worldId: UUID,
        val chunkKey: Long,
    )

    private data class CachedChunk(
        val chunk: Chunk,
        val states: LinkedHashMap<Int, TrailBlockState>,
        var dirty: Boolean,
        var corrupt: Boolean,
    )

}
