package ru.ruscrafting.trails.storage

import org.bukkit.configuration.file.YamlConfiguration
import ru.ruscrafting.trails.domain.TrailIdentity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class RoadBlockRecord(
    val x: Int,
    val y: Int,
    val z: Int,
    val beforeData: String,
    val afterData: String,
    val previousState: TrailBlockState?,
    val afterState: TrailBlockState,
)

data class RoadCommitRecord(
    val worldId: UUID,
    val committedAt: Long,
    val blocks: List<RoadBlockRecord>,
)

class RoadHistoryStore(
    private val dataFolder: Path,
) {
    private val path = dataFolder.resolve("road-history.yml")

    fun load(): LinkedHashMap<UUID, RoadCommitRecord> {
        if (!Files.exists(path)) return linkedMapOf()
        val yaml = YamlConfiguration.loadConfiguration(path.toFile())
        require(yaml.getInt("config-version") == CONFIG_VERSION) { "Unsupported road-history.yml config-version" }
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
                        if (rawAfterIdentity == null || rawAfterIdentity.length > MAX_IDENTITY_LENGTH) return@mapNotNull null
                        val afterIdentity = TrailIdentity.parse(rawAfterIdentity) ?: return@mapNotNull null
                        val afterState =
                            TrailBlockState(
                                afterIdentity,
                                ((raw["after-walks"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                            )
                        RoadBlockRecord(x, y, z, before, after, previous, afterState)
                    }
                if (blocks.isEmpty() || blocks.size != rawBlocks.size) null else uuid to RoadCommitRecord(world, committedAt, blocks)
            }.sortedBy { it.second.committedAt }.takeLast(MAX_STORED_PLAYERS)
        return linkedMapOf(*loaded.toTypedArray())
    }

    fun save(history: Map<UUID, RoadCommitRecord>) {
        require(history.size <= MAX_STORED_PLAYERS) { "Road undo history exceeds $MAX_STORED_PLAYERS players" }
        history.values.forEach { commit ->
            require(commit.blocks.size in 1..MAX_BLOCKS_PER_COMMIT) { "Road undo commit has an invalid block count" }
            commit.blocks.forEach { block ->
                require(block.beforeData.length <= MAX_BLOCK_DATA_LENGTH && block.afterData.length <= MAX_BLOCK_DATA_LENGTH) {
                    "Road undo block data is too long"
                }
            }
        }
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
                        "after-walks" to block.afterState.walks,
                    ).apply {
                        block.previousState?.identity?.let { put("previous-identity", it.serialize()) }
                        block.afterState.identity?.let { put("after-identity", it.serialize()) }
                    }
                },
            )
        }
        Files.createDirectories(dataFolder)
        val temporary = Files.createTempFile(dataFolder, ".road-history-", ".tmp")
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val CONFIG_VERSION = 1
        const val MAX_STORED_PLAYERS = 10
        const val MAX_BLOCKS_PER_COMMIT = 1024
        const val MAX_BLOCK_DATA_LENGTH = 1024
        const val MAX_IDENTITY_LENGTH = 256
    }
}
