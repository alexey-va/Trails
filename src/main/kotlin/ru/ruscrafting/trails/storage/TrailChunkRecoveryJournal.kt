package ru.ruscrafting.trails.storage

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecordJournal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32

internal class TrailChunkRevisionChanged(message: String) : IllegalStateException(message)

internal data class TrailChunkSnapshot(
    val worldId: UUID,
    val chunkKey: Long,
    val encodedStates: ByteArray,
    val baseKnown: Boolean = true,
    val clearedPositions: Set<Int> = emptySet(),
    val revision: Long = 0,
    val lastClearRevision: Long = 0,
    val recordToken: UUID = UUID(0L, 0L),
) {
    /** A coalesced request may complete without being the value written to disk. */
    @Volatile var persistedRevision: Long = -1
        internal set
    @Volatile var persistedSnapshot: TrailChunkSnapshot? = null
        internal set
}

internal fun sameTrailChunkSnapshot(first: TrailChunkSnapshot, second: TrailChunkSnapshot): Boolean =
    first.worldId == second.worldId && first.chunkKey == second.chunkKey &&
        first.baseKnown == second.baseKnown && first.revision == second.revision &&
        first.lastClearRevision == second.lastClearRevision && first.recordToken == second.recordToken &&
        first.clearedPositions == second.clearedPositions &&
        first.encodedStates.contentEquals(second.encodedStates)

internal interface TrailChunkPersistence {
    fun read(chunk: Chunk, key: NamespacedKey): ByteArray?

    fun write(chunk: Chunk, key: NamespacedKey, encoded: ByteArray?)
}

internal object BukkitTrailChunkPersistence : TrailChunkPersistence {
    override fun read(chunk: Chunk, key: NamespacedKey): ByteArray? =
        chunk.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY)

    override fun write(chunk: Chunk, key: NamespacedKey, encoded: ByteArray?) {
        if (encoded == null) {
            chunk.persistentDataContainer.remove(key)
        } else {
            chunk.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY, encoded)
        }
    }
}

/**
 * Crash-safe write-ahead snapshots for chunk PDC. Records deliberately survive a successful PDC
 * mutation until a later chunk load proves that Paper persisted the same state to disk. A foreign
 * event may clear positions before this worker has seen the chunk's PDC. Such a record retains an
 * unknown base plus a clear patch; it must never be treated as an empty full snapshot.
 *
 * The vanilla event precedes the asynchronous clear acknowledgement. This journal does not claim
 * pre-effect durability for that interval; deferred local writes wait for the actual acknowledgement.
 */
internal class TrailChunkRecoveryJournal(root: Path) {
    private val journal =
        DurableRecordJournal(
            root = root,
            relativeDirectory = Path.of("recovery", "trail-chunks"),
            maxRecordBytes = MAX_RECORD_BYTES,
            encode = TrailChunkSnapshotCodec::encode,
            decode = TrailChunkSnapshotCodec::decode,
            validate = TrailChunkSnapshotCodec::validate,
        )
    private val records = java.util.concurrent.ConcurrentHashMap<ChunkId, TrailChunkSnapshot>()
    private val mutations = Any()

    init {
        journal.loadAll().forEach { record ->
            val snapshot = record.value
            check(record.recordId == recordId(snapshot.worldId, snapshot.chunkKey)) {
                "Trail recovery record identity does not match its contents"
            }
            check(records.put(ChunkId(snapshot.worldId, snapshot.chunkKey), snapshot) == null) {
                "Duplicate trail recovery record"
            }
        }
    }

    fun load(worldId: UUID, chunkKey: Long): TrailChunkSnapshot? = records[ChunkId(worldId, chunkKey)]

    /** Full snapshots come from the one codec owner; a later clear invalidates every older request. */
    fun commit(snapshot: TrailChunkSnapshot): TrailChunkSnapshot = synchronized(mutations) {
        require(snapshot.baseKnown && snapshot.clearedPositions.isEmpty()) { "full trail snapshot expected" }
        val key = ChunkId(snapshot.worldId, snapshot.chunkKey)
        val current = records[key]
        if (current == null) {
            if (snapshot.revision != 0L) throw TrailChunkRevisionChanged("trail recovery base was already acknowledged")
        } else {
            if (snapshot.revision !in current.lastClearRevision..current.revision)
                throw TrailChunkRevisionChanged("trail snapshot predates a foreign clear or references a newer recovery revision")
            if (current.clearedPositions.isNotEmpty() && snapshot.revision != current.revision)
                throw TrailChunkRevisionChanged("trail clear patch must be folded from its exact recovery revision")
        }
        val next = snapshot.copy(
            revision = Math.addExact(current?.revision ?: 0L, 1L),
            lastClearRevision = current?.lastClearRevision ?: 0L,
            recordToken = UUID.randomUUID(),
        )
        persist(key, next).also {
            snapshot.persistedRevision = it.revision
            snapshot.persistedSnapshot = it
        }
    }

    /** Worker-only durable overlay; coordinates are numeric and never require a live chunk. */
    fun clearPositions(worldId: UUID, chunkKey: Long, positions: Set<Int>): TrailChunkSnapshot = synchronized(mutations) {
        require(positions.isNotEmpty()) { "a trail clear patch must contain positions" }
        val key = ChunkId(worldId, chunkKey)
        val current = records[key]
        val revision = Math.addExact(current?.revision ?: 0L, 1L)
        val cleared = (current?.clearedPositions.orEmpty() + positions).toSet()
        require(cleared.size <= TrailChunkCodec.MAX_ENTRIES) { "trail clear patch contains too many positions" }
        persist(key, TrailChunkSnapshot(
            worldId, chunkKey, current?.encodedStates ?: byteArrayOf(),
            baseKnown = current?.baseKnown ?: false,
            clearedPositions = cleared,
            revision = revision,
            lastClearRevision = revision,
            recordToken = UUID.randomUUID(),
        ))
    }

    private fun persist(key: ChunkId, snapshot: TrailChunkSnapshot): TrailChunkSnapshot {
        val committed = journal.commit(recordId(snapshot.worldId, snapshot.chunkKey), snapshot)
        committed.persistedRevision = committed.revision
        records[key] = committed
        return committed
    }

    fun acknowledge(snapshot: TrailChunkSnapshot): DurableAcknowledgementOutcome = synchronized(mutations) {
        val outcome =
            journal.acknowledgeExactly(
                recordId(snapshot.worldId, snapshot.chunkKey),
                snapshot,
                ::sameTrailChunkSnapshot,
            )
        if (outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
            val key = ChunkId(snapshot.worldId, snapshot.chunkKey)
            records.computeIfPresent(key) { _, current ->
                current.takeUnless { sameTrailChunkSnapshot(snapshot, current) }
            }
        }
        outcome
    }

    fun size(): Int = records.size

    private fun recordId(worldId: UUID, chunkKey: Long): String =
        "${worldId.toString().replace("-", "")}-${chunkKey.toULong().toString(16)}"

    private data class ChunkId(val worldId: UUID, val chunkKey: Long)

    private companion object {
        const val MAX_RECORD_BYTES = TrailChunkCodec.MAX_ENCODED_BYTES.toLong() + TrailChunkCodec.MAX_ENTRIES * 4L + 128L
    }
}

internal object TrailChunkSnapshotCodec {
    private const val MAGIC = 0x5452434a // TRCJ
    private const val VERSION = 2
    private const val LEGACY_HEADER_BYTES = Int.SIZE_BYTES + 1 + Long.SIZE_BYTES * 3 + Int.SIZE_BYTES
    private const val HEADER_BYTES = LEGACY_HEADER_BYTES + Long.SIZE_BYTES * 4 + 1 + Int.SIZE_BYTES
    private const val CHECKSUM_BYTES = Int.SIZE_BYTES
    private const val MAX_RECORD_BYTES = TrailChunkCodec.MAX_ENCODED_BYTES + TrailChunkCodec.MAX_ENTRIES * 4 + 128

    fun encode(snapshot: TrailChunkSnapshot): ByteArray {
        validate(snapshot)
        val payload =
            ByteArrayOutputStream(HEADER_BYTES + snapshot.encodedStates.size + snapshot.clearedPositions.size * 4).also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeByte(VERSION)
                    output.writeLong(snapshot.worldId.mostSignificantBits)
                    output.writeLong(snapshot.worldId.leastSignificantBits)
                    output.writeLong(snapshot.chunkKey)
                    output.writeLong(snapshot.revision)
                    output.writeLong(snapshot.lastClearRevision)
                    output.writeLong(snapshot.recordToken.mostSignificantBits)
                    output.writeLong(snapshot.recordToken.leastSignificantBits)
                    output.writeBoolean(snapshot.baseKnown)
                    output.writeInt(snapshot.encodedStates.size)
                    output.write(snapshot.encodedStates)
                    output.writeInt(snapshot.clearedPositions.size)
                    snapshot.clearedPositions.sorted().forEach(output::writeInt)
                }
            }.toByteArray()
        val checksum = CRC32().apply { update(payload) }.value.toInt()
        return ByteArrayOutputStream(payload.size + CHECKSUM_BYTES).also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(payload)
                output.writeInt(checksum)
            }
        }.toByteArray()
    }

    fun decode(encoded: ByteArray): TrailChunkSnapshot {
        require(encoded.size in LEGACY_HEADER_BYTES + CHECKSUM_BYTES..MAX_RECORD_BYTES) {
            "Trail recovery record has an invalid size"
        }
        val payloadLength = encoded.size - CHECKSUM_BYTES
        val expected = ByteBuffer.wrap(encoded, payloadLength, CHECKSUM_BYTES).int.toLong() and 0xffff_ffffL
        val actual = CRC32().apply { update(encoded, 0, payloadLength) }.value
        require(expected == actual) { "Trail recovery record checksum mismatch" }
        return DataInputStream(ByteArrayInputStream(encoded, 0, payloadLength)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown trail recovery record magic" }
            val version = input.readUnsignedByte()
            require(version == 1 || version == VERSION) { "Unsupported trail recovery record version" }
            val worldId = UUID(input.readLong(), input.readLong())
            val chunkKey = input.readLong()
            val revision = if (version == 1) 0L else input.readLong()
            val lastClearRevision = if (version == 1) 0L else input.readLong()
            val recordToken = if (version == 1) UUID(0L, 0L) else UUID(input.readLong(), input.readLong())
            val baseKnown = if (version == 1) true else input.readUnsignedByte().let {
                require(it == 0 || it == 1) { "Invalid trail recovery base flag" }
                it == 1
            }
            val length = input.readInt()
            require(length in (if (baseKnown) 1 else 0)..TrailChunkCodec.MAX_ENCODED_BYTES) { "Invalid trail recovery payload size" }
            val states = input.readNBytes(length)
            val clears = linkedSetOf<Int>()
            if (version != 1) {
                val clearCount = input.readInt()
                require(clearCount in 0..TrailChunkCodec.MAX_ENTRIES) { "Invalid trail recovery clear count" }
                repeat(clearCount) { require(clears.add(input.readInt())) { "Duplicate trail recovery clear position" } }
            }
            require(states.size == length && input.available() == 0) { "Truncated trail recovery record" }
            TrailChunkSnapshot(worldId, chunkKey, states, baseKnown, clears, revision, lastClearRevision, recordToken).also(::validate)
        }
    }

    fun validate(snapshot: TrailChunkSnapshot) {
        require(snapshot.revision >= 0 && snapshot.lastClearRevision in 0..snapshot.revision) { "Invalid trail recovery revision" }
        require(snapshot.clearedPositions.size <= TrailChunkCodec.MAX_ENTRIES) { "Trail recovery clear patch is too large" }
        if (snapshot.baseKnown) {
            require(snapshot.encodedStates.isNotEmpty()) { "Trail recovery state payload must not be empty" }
            TrailChunkCodec.decode(snapshot.encodedStates)
        } else {
            require(snapshot.encodedStates.isEmpty() && snapshot.clearedPositions.isNotEmpty()) { "Unknown trail recovery base requires a clear patch" }
        }
    }
}
