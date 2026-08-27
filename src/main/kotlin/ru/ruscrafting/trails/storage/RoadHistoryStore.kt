package ru.ruscrafting.trails.storage

import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.persistence.AtomicFileStore
import ru.ruscrafting.trails.domain.TrailIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

data class RoadBlockRecord(
    val x: Int,
    val y: Int,
    val z: Int,
    val beforeData: String,
    val afterData: String,
    val previousState: TrailBlockState?,
    val afterState: TrailBlockState?,
)

data class RoadCommitRecord(
    val worldId: UUID,
    val committedAt: Long,
    val blocks: List<RoadBlockRecord>,
)

class RoadHistoryStore(
    dataFolder: Path,
) {
    private val store =
        AtomicFileStore(
            root = dataFolder,
            relativePath = Path.of("road-history.yml"),
            maxBytes = MAX_FILE_BYTES,
            encode = { value: String -> value.toByteArray(StandardCharsets.UTF_8) },
            decode = { bytes -> bytes.toString(StandardCharsets.UTF_8) },
            validate = { encoded -> decode(encoded) },
        )

    fun load(): LinkedHashMap<UUID, RoadCommitRecord> {
        val encoded = store.loadOrNull() ?: return linkedMapOf()
        return decode(encoded)
    }

    private fun decode(encoded: String): LinkedHashMap<UUID, RoadCommitRecord> {
        val yaml = YamlConfiguration().apply { loadFromString(encoded) }
        val configVersion = yaml.getInt("config-version")
        require(configVersion in 1..CONFIG_VERSION) { "Unsupported road-history.yml config-version" }
        val historyKeys = yaml.getConfigurationSection("history")?.getKeys(false).orEmpty()
        require(historyKeys.size <= MAX_STORED_PLAYERS * 2) { "road-history.yml contains too many player entries" }
        val loaded =
            historyKeys.mapNotNull { rawUuid ->
                val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return@mapNotNull null
                val base = "history.$rawUuid"
                val world = runCatching { UUID.fromString(yaml.getString("$base.world")) }.getOrNull() ?: return@mapNotNull null
                val committedAt = yaml.getLong("$base.committed-at")
                val rawBlocks = yaml.getMapList("$base.blocks")
                if (rawBlocks.size !in 1..MAX_BLOCKS_PER_COMMIT) return@mapNotNull null
                val blocks =
                    rawBlocks.mapNotNull { raw ->
                        val before = raw["before"]?.toString() ?: return@mapNotNull null
                        val after = raw["after"]?.toString() ?: return@mapNotNull null
                        if (before.length > MAX_BLOCK_DATA_LENGTH || after.length > MAX_BLOCK_DATA_LENGTH) return@mapNotNull null
                        val x = (raw["x"] as? Number)?.toInt() ?: return@mapNotNull null
                        val y = (raw["y"] as? Number)?.toInt() ?: return@mapNotNull null
                        val z = (raw["z"] as? Number)?.toInt() ?: return@mapNotNull null
                        val previous =
                            if (raw["previous-present"] == true) {
                                val rawIdentity = raw["previous-identity"]?.toString()
                                if (rawIdentity != null && rawIdentity.length > MAX_IDENTITY_LENGTH) return@mapNotNull null
                                val identity = TrailIdentity.parse(rawIdentity)
                                if (rawIdentity != null && identity == null) return@mapNotNull null
                                TrailBlockState(
                                    identity,
                                    ((raw["previous-walks"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                                )
                            } else {
                                null
                            }
                        val rawAfterIdentity = raw["after-identity"]?.toString()
                        if (rawAfterIdentity != null && rawAfterIdentity.length > MAX_IDENTITY_LENGTH) return@mapNotNull null
                        val afterIdentity = TrailIdentity.parse(rawAfterIdentity)
                        if (rawAfterIdentity != null && afterIdentity == null) return@mapNotNull null
                        val afterPresent = configVersion == 1 || raw["after-present"] == true
                        val afterState =
                            if (afterPresent) {
                                TrailBlockState(
                                    afterIdentity,
                                    ((raw["after-walks"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                                )
                            } else {
                                null
                            }
                        RoadBlockRecord(x, y, z, before, after, previous, afterState)
                    }
                if (blocks.isEmpty() || blocks.size != rawBlocks.size) null else uuid to RoadCommitRecord(world, committedAt, blocks)
            }.sortedBy { it.second.committedAt }.takeLast(MAX_STORED_PLAYERS)
        return linkedMapOf(*loaded.toTypedArray())
    }

    fun save(history: Map<UUID, RoadCommitRecord>) {
        validate(history)
        val yaml = encode(history)
        store.write(yaml)
    }

    private fun validate(history: Map<UUID, RoadCommitRecord>) {
        require(history.size <= MAX_STORED_PLAYERS) { "Road undo history exceeds $MAX_STORED_PLAYERS players" }
        history.values.forEach { commit ->
            require(commit.blocks.size in 1..MAX_BLOCKS_PER_COMMIT) { "Road undo commit has an invalid block count" }
            commit.blocks.forEach { block ->
                require(block.beforeData.length <= MAX_BLOCK_DATA_LENGTH && block.afterData.length <= MAX_BLOCK_DATA_LENGTH) {
                    "Road undo block data is too long"
                }
            }
        }
    }

    private fun encode(history: Map<UUID, RoadCommitRecord>): String {
        val yaml = YamlConfiguration()
        yaml.set("config-version", CONFIG_VERSION)
        history.forEach { (uuid, commit) ->
            val base = "history.$uuid"
            yaml.set("$base.world", commit.worldId.toString())
            yaml.set("$base.committed-at", commit.committedAt)
            yaml.set(
                "$base.blocks",
                commit.blocks.map { block ->
                    linkedMapOf<String, Any>(
                        "x" to block.x,
                        "y" to block.y,
                        "z" to block.z,
                        "before" to block.beforeData,
                        "after" to block.afterData,
                        "previous-present" to (block.previousState != null),
                        "previous-walks" to (block.previousState?.walks ?: 0),
                        "after-present" to (block.afterState != null),
                        "after-walks" to (block.afterState?.walks ?: 0),
                    ).apply {
                        block.previousState?.identity?.let { put("previous-identity", it.serialize()) }
                        block.afterState?.identity?.let { put("after-identity", it.serialize()) }
                    }
                },
            )
        }
        return yaml.saveToString()
    }

    private companion object {
        const val CONFIG_VERSION = 2
        const val MAX_STORED_PLAYERS = 10
        const val MAX_BLOCKS_PER_COMMIT = 4096
        const val MAX_BLOCK_DATA_LENGTH = 1024
        const val MAX_IDENTITY_LENGTH = 256
        const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    }
}
