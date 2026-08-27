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

internal data class TrailChunkSnapshot(
    val worldId: UUID,
    val chunkKey: Long,
    val encodedStates: ByteArray,
)

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
 * mutation until a later chunk load proves that Paper persisted the same state to disk.
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
    private val records = linkedMapOf<ChunkId, TrailChunkSnapshot>()

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

    fun commit(snapshot: TrailChunkSnapshot): TrailChunkSnapshot {
        val committed = journal.commit(recordId(snapshot.worldId, snapshot.chunkKey), snapshot)
        records[ChunkId(committed.worldId, committed.chunkKey)] = committed
        return committed
    }

    fun acknowledge(snapshot: TrailChunkSnapshot): DurableAcknowledgementOutcome {
        val outcome =
            journal.acknowledgeExactly(
                recordId(snapshot.worldId, snapshot.chunkKey),
                snapshot,
                ::sameSnapshot,
            )
        if (outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
            records.remove(ChunkId(snapshot.worldId, snapshot.chunkKey))
        }
        return outcome
    }

    fun size(): Int = records.size

    private fun sameSnapshot(expected: TrailChunkSnapshot, current: TrailChunkSnapshot): Boolean =
        expected.worldId == current.worldId &&
            expected.chunkKey == current.chunkKey &&
            expected.encodedStates.contentEquals(current.encodedStates)

    private fun recordId(worldId: UUID, chunkKey: Long): String =
        "${worldId.toString().replace("-", "")}-${chunkKey.toULong().toString(16)}"

    private data class ChunkId(val worldId: UUID, val chunkKey: Long)

    private companion object {
        const val MAX_RECORD_BYTES = TrailChunkCodec.MAX_ENCODED_BYTES.toLong() + 64L
    }
}

private object TrailChunkSnapshotCodec {
    private const val MAGIC = 0x5452434a // TRCJ
    private const val VERSION = 1
    private const val HEADER_BYTES = Int.SIZE_BYTES + 1 + Long.SIZE_BYTES * 3 + Int.SIZE_BYTES
    private const val CHECKSUM_BYTES = Int.SIZE_BYTES

    fun encode(snapshot: TrailChunkSnapshot): ByteArray {
        validate(snapshot)
        val payload =
            ByteArrayOutputStream(HEADER_BYTES + snapshot.encodedStates.size).also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeByte(VERSION)
                    output.writeLong(snapshot.worldId.mostSignificantBits)
                    output.writeLong(snapshot.worldId.leastSignificantBits)
                    output.writeLong(snapshot.chunkKey)
                    output.writeInt(snapshot.encodedStates.size)
                    output.write(snapshot.encodedStates)
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
        require(encoded.size in HEADER_BYTES + CHECKSUM_BYTES..TrailChunkCodec.MAX_ENCODED_BYTES + 64) {
            "Trail recovery record has an invalid size"
        }
        val payloadLength = encoded.size - CHECKSUM_BYTES
        val expected = ByteBuffer.wrap(encoded, payloadLength, CHECKSUM_BYTES).int.toLong() and 0xffff_ffffL
        val actual = CRC32().apply { update(encoded, 0, payloadLength) }.value
        require(expected == actual) { "Trail recovery record checksum mismatch" }
        return DataInputStream(ByteArrayInputStream(encoded, 0, payloadLength)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown trail recovery record magic" }
            require(input.readUnsignedByte() == VERSION) { "Unsupported trail recovery record version" }
            val worldId = UUID(input.readLong(), input.readLong())
            val chunkKey = input.readLong()
            val length = input.readInt()
            require(length in 1..TrailChunkCodec.MAX_ENCODED_BYTES) { "Invalid trail recovery payload size" }
            val states = input.readNBytes(length)
            require(states.size == length && input.available() == 0) { "Truncated trail recovery record" }
            TrailChunkSnapshot(worldId, chunkKey, states).also(::validate)
        }
    }

    fun validate(snapshot: TrailChunkSnapshot) {
        require(snapshot.encodedStates.isNotEmpty()) { "Trail recovery state payload must not be empty" }
        TrailChunkCodec.decode(snapshot.encodedStates)
    }
}
