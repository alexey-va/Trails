package ru.ruscrafting.trails.storage

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.trails.domain.TrailIdentity
import com.jeff_media.customblockdata.CustomBlockData

data class TrailBlockState(
    val identity: TrailIdentity?,
    val walks: Int,
)

interface TrailBlockStore {
    fun read(block: Block): TrailBlockState?

    fun write(block: Block, state: TrailBlockState)

    fun clear(block: Block)

    fun trackedBlocks(chunk: Chunk): Collection<Block>
}

class CustomBlockTrailStore(
    private val plugin: Plugin,
) : TrailBlockStore {
    private val walksKey = NamespacedKey(plugin, "w")
    private val identityKey = NamespacedKey(plugin, "n")

    override fun read(block: Block): TrailBlockState? {
        val data = CustomBlockData(block, plugin)
        val rawWalks = data.get(walksKey, PersistentDataType.INTEGER)
        val rawIdentity = data.get(identityKey, PersistentDataType.STRING)
        if (rawWalks == null && rawIdentity == null) return null
        return TrailBlockState(
            identity = TrailIdentity.parse(rawIdentity),
            walks = rawWalks?.coerceAtLeast(0) ?: 0,
        )
    }

    override fun write(block: Block, state: TrailBlockState) {
        val data = CustomBlockData(block, plugin)
        data.set(walksKey, PersistentDataType.INTEGER, state.walks.coerceAtLeast(0))
        state.identity?.let { data.set(identityKey, PersistentDataType.STRING, it.serialize()) }
            ?: data.remove(identityKey)
    }

    override fun clear(block: Block) {
        val data = CustomBlockData(block, plugin)
        data.remove(walksKey)
        data.remove(identityKey)
    }

    override fun trackedBlocks(chunk: Chunk): Collection<Block> =
        CustomBlockData.getBlocksWithCustomData(plugin, chunk).filter { read(it) != null }
}
