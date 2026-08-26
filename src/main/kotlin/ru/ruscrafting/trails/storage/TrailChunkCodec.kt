package ru.ruscrafting.trails.storage

import ru.ruscrafting.trails.domain.TrailIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal class TrailChunkFormatException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Packs a chunk-local X/Z and signed absolute block Y into one stable integer. */
internal object TrailBlockPosition {
    private const val MIN_PACKED_Y = -(1 shl 23)
    private const val MAX_PACKED_Y = (1 shl 23) - 1

    fun pack(
        localX: Int,
        y: Int,
        localZ: Int,
    ): Int {
        require(localX in 0..15) { "localX must be between 0 and 15" }
        require(localZ in 0..15) { "localZ must be between 0 and 15" }
        require(y in MIN_PACKED_Y..MAX_PACKED_Y) { "y is outside the supported packed range" }
        return (y shl 8) or (localZ shl 4) or localX
    }

    fun localX(packed: Int): Int = packed and 0x0f

    fun localZ(packed: Int): Int = (packed ushr 4) and 0x0f

    fun y(packed: Int): Int = packed shr 8
}

/**
 * Bounded, deterministic codec for one chunk's trail states.
 *
 * The payload uses a per-chunk trail-name dictionary, fixed packed positions,
 * unsigned varints for counters, and a CRC32 footer. It intentionally has no
 * Java object serialization or legacy format branch.
 */
internal object TrailChunkCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_TRAIL_NAME_BYTES = 128
    const val MAX_ENTRIES = 131_072
    const val MAX_TRAIL_NAMES = 4_096
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    private const val MAGIC = 0x54524c53 // TRLS
    private const val CHECKSUM_BYTES = Int.SIZE_BYTES

    fun encode(states: Map<Int, TrailBlockState>): ByteArray {
        require(states.size <= MAX_ENTRIES) { "trail chunk contains too many entries" }
        states.values.forEach(::validateState)
        val names = states.values.mapNotNull { it.identity?.trailName }.distinct().sorted()
        require(names.size <= MAX_TRAIL_NAMES) { "trail chunk contains too many trail identities" }
        val encodedNames = names.map { name ->
            name.toByteArray(StandardCharsets.UTF_8).also { bytes ->
                require(bytes.isNotEmpty()) { "trail identity name must not be empty" }
                require(bytes.size <= MAX_TRAIL_NAME_BYTES) { "trail identity name is too long" }
            }
        }
        val nameIndexes = names.withIndex().associate { (index, name) -> name to index + 1 }

        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(SCHEMA_VERSION)
            output.writeUnsignedVarInt(names.size)
            encodedNames.forEach { name ->
                output.writeUnsignedVarInt(name.size)
                output.write(name)
            }
            output.writeUnsignedVarInt(states.size)
            states.toSortedMap().forEach { (position, state) ->
                output.writeInt(position)
                val identity = state.identity
                output.writeUnsignedVarInt(identity?.let { nameIndexes.getValue(it.trailName) } ?: 0)
                if (identity != null) output.writeUnsignedVarInt(identity.stageIndex)
                output.writeUnsignedVarInt(state.walks)
            }
        }
        val raw = payload.toByteArray()
        require(raw.size + CHECKSUM_BYTES <= MAX_ENCODED_BYTES) { "trail chunk payload is too large" }
        val checksum = CRC32().apply { update(raw) }.value.toInt()
        return ByteArrayOutputStream(raw.size + CHECKSUM_BYTES).also { complete ->
            DataOutputStream(complete).use { output ->
                output.write(raw)
                output.writeInt(checksum)
            }
        }.toByteArray()
    }

    fun decode(encoded: ByteArray): LinkedHashMap<Int, TrailBlockState> {
        if (encoded.size < Int.SIZE_BYTES + 1 + CHECKSUM_BYTES) throw format("payload is truncated")
        if (encoded.size > MAX_ENCODED_BYTES) throw format("payload exceeds the size limit")
        val payloadLength = encoded.size - CHECKSUM_BYTES
        val expected = ByteBuffer.wrap(encoded, payloadLength, CHECKSUM_BYTES).int.toLong() and 0xffff_ffffL
        val actual = CRC32().apply { update(encoded, 0, payloadLength) }.value
        if (actual != expected) throw format("checksum mismatch")

        try {
            DataInputStream(ByteArrayInputStream(encoded, 0, payloadLength)).use { input ->
                if (input.readInt() != MAGIC) throw format("unknown payload magic")
                val version = input.readUnsignedByte()
                if (version != SCHEMA_VERSION) throw format("unsupported schema version $version")
                val nameCount = input.readUnsignedVarInt("trail-name count")
                if (nameCount > MAX_TRAIL_NAMES) throw format("too many trail identities")
                val names = ArrayList<String>(nameCount)
                val uniqueNames = HashSet<String>(nameCount)
                repeat(nameCount) {
                    val length = input.readUnsignedVarInt("trail-name length")
                    if (length !in 1..MAX_TRAIL_NAME_BYTES) throw format("invalid trail-name length")
                    val bytes = input.readNBytes(length)
                    if (bytes.size != length) throw format("truncated trail identity")
                    val name = decodeUtf8(bytes)
                    if (!uniqueNames.add(name)) throw format("duplicate trail identity")
                    names += name
                }

                val entryCount = input.readUnsignedVarInt("entry count")
                if (entryCount > MAX_ENTRIES) throw format("too many trail entries")
                val states = LinkedHashMap<Int, TrailBlockState>(entryCount)
                repeat(entryCount) {
                    val position = input.readInt()
                    val identityIndex = input.readUnsignedVarInt("trail identity index")
                    if (identityIndex > names.size) throw format("unknown trail identity index")
                    val identity =
                        if (identityIndex == 0) {
                            null
                        } else {
                            val stage = input.readUnsignedVarInt("stage index")
                            try {
                                TrailIdentity(names[identityIndex - 1], stage)
                            } catch (error: IllegalArgumentException) {
                                throw format("invalid trail identity", error)
                            }
                        }
                    val walks = input.readUnsignedVarInt("walk count")
                    val previous = states.put(position, TrailBlockState(identity, walks))
                    if (previous != null) throw format("duplicate block position")
                }
                if (input.available() != 0) throw format("trailing payload data")
                return states
            }
        } catch (error: TrailChunkFormatException) {
            throw error
        } catch (error: EOFException) {
            throw format("payload is truncated", error)
        }
    }

    fun validateState(state: TrailBlockState) {
        require(state.walks >= 0) { "walk count must not be negative" }
        state.identity?.let { identity ->
            require(identity.stageIndex >= 0) { "stage index must not be negative" }
            val bytes = identity.trailName.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_TRAIL_NAME_BYTES) { "trail identity name is too long" }
        }
    }

    private fun DataOutputStream.writeUnsignedVarInt(value: Int) {
        require(value >= 0) { "varint value must not be negative" }
        var remaining = value
        while (remaining and 0x7f.inv() != 0) {
            writeByte((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining)
    }

    private fun DataInputStream.readUnsignedVarInt(field: String): Int {
        var result = 0
        for (index in 0 until 5) {
            val current = readUnsignedByte()
            if (index == 4 && current and 0xf8 != 0) throw format("$field exceeds the integer range")
            result = result or ((current and 0x7f) shl (index * 7))
            if (current and 0x80 == 0) return result
        }
        throw format("$field varint is too long")
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw format("trail identity is not valid UTF-8", error)
        }

    private fun format(
        message: String,
        cause: Throwable? = null,
    ) = TrailChunkFormatException(message, cause)
}
