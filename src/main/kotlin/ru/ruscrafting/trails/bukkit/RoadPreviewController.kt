package ru.ruscrafting.trails.bukkit

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.block.Block
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.api.ArcNativeCleanupService
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

internal data class RoadPreviewCell(val x: Int, val y: Int, val z: Int, val afterData: String, val clearance: Boolean)

/** Owns exactly the cells actually sent to each viewer, including a superseded partial preview. */
internal class RoadPreviewController(
    private val server: Server,
    private val nativeWork: BudgetedTaskQueue,
    private val cleanup: ArcNativeCleanupService,
    private val selector: RoadPreviewSelector = RoadPreviewSelector(),
) : AutoCloseable {
    private data class CellKey(val x: Int, val y: Int, val z: Int)
    private class View(val worldId: UUID) {
        var shown = linkedMapOf<CellKey, String>()
        var operation: Operation? = null
    }
    private class Operation(
        var cells: Iterator<RoadPreviewCell>,
        var lookup: (Int, Int, Int) -> String?,
        val isCurrent: () -> Boolean,
        val canClearAbove: (Block) -> Boolean,
        var restoring: MutableIterator<MutableMap.MutableEntry<CellKey, String>>,
        var cancelling: Boolean,
    ) {
        val completion = CompletableFuture<Void>()
        var applying = false
        var failure: Throwable? = null
        fun failed(error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
    }
    private var views = mutableMapOf<UUID, View>()
    private var retirement: CompletableFuture<Void>? = null

    fun replace(
        playerId: UUID,
        worldId: UUID,
        cells: Iterator<RoadPreviewCell>,
        lookup: (Int, Int, Int) -> String?,
        isCurrent: () -> Boolean,
        canClearAbove: (Block) -> Boolean,
    ): CompletableFuture<Void> {
        checkMain()
        if (retirement != null) return CompletableFuture.failedFuture(CancellationException("Road previews are closed"))
        val player = server.getPlayer(playerId)
        if (player == null || !player.isOnline || player.world.uid != worldId) return cancel(playerId)
        val previous = views[playerId]
        val view = if (previous?.worldId == worldId) previous else View(worldId).also {
            // A real world change has already discarded the old world's client chunk cache.
            previous?.operation?.completion?.completeExceptionally(CancellationException("Preview world changed"))
            views[playerId] = it
        }
        return submit(playerId, view, cells, lookup, isCurrent, canClearAbove, cancelling = false)
    }

    fun cancel(playerId: UUID): CompletableFuture<Void> {
        checkMain()
        retirement?.let { return it.copy() }
        val view = views[playerId] ?: return CompletableFuture.completedFuture(null)
        return submit(playerId, view, emptyList<RoadPreviewCell>().iterator(), { _, _, _ -> null }, { true }, { false }, true)
    }

    private fun submit(
        playerId: UUID,
        view: View,
        cells: Iterator<RoadPreviewCell>,
        lookup: (Int, Int, Int) -> String?,
        isCurrent: () -> Boolean,
        canClearAbove: (Block) -> Boolean,
        cancelling: Boolean,
    ): CompletableFuture<Void> {
        view.operation?.completion?.completeExceptionally(CancellationException("Road preview superseded"))
        val operation = Operation(cells, lookup, isCurrent, canClearAbove, view.shown.entries.iterator(), cancelling)
        view.operation = operation
        nativeWork.submit({ retirement == null && views[playerId] === view && view.operation === operation }) {
            if (!operation.cancelling && !operation.isCurrent()) {
                operation.cancelling = true
                operation.applying = false
                operation.cells = emptyList<RoadPreviewCell>().iterator()
                operation.lookup = { _, _, _ -> null }
                operation.restoring = view.shown.entries.iterator()
                operation.failed(CancellationException("Road preview is no longer current"))
            }
            val player = server.getPlayer(playerId)
            val world = server.getWorld(view.worldId)
            if (player == null || !player.isOnline || world == null || player.world.uid != view.worldId) {
                view.shown = linkedMapOf()
                views.remove(playerId, view)
                true
            } else if (!operation.applying && operation.restoring.hasNext()) {
                val entry = operation.restoring.next()
                try {
                    val key = entry.key
                    if (operation.lookup(key.x, key.y, key.z) != entry.value) {
                        if (world.isChunkLoaded(key.x shr 4, key.z shr 4)) {
                            val block = world.getBlockAt(key.x, key.y, key.z)
                            player.sendBlockChange(block.location, block.blockData)
                        }
                        operation.restoring.remove()
                    }
                } catch (error: Throwable) { operation.failed(error) }
                false
            } else if (operation.cells.hasNext()) {
                operation.applying = true
                try {
                    val cell = operation.cells.next()
                    val key = CellKey(cell.x, cell.y, cell.z)
                    if (view.shown[key] != cell.afterData && world.isChunkLoaded(cell.x shr 4, cell.z shr 4)) {
                        val block = world.getBlockAt(cell.x, cell.y, cell.z)
                        val target = server.createBlockData(cell.afterData)
                        val selected = if (cell.clearance && operation.canClearAbove(block)) target
                            else selector.select(target, block.location).blockData
                        // Keep a restoration handle even when packet admission throws after a partial send.
                        view.shown[key] = cell.afterData
                        player.sendBlockChange(block.location, selected)
                    }
                } catch (error: Throwable) { operation.failed(error) }
                false
            } else {
                if (view.shown.isEmpty()) views.remove(playerId, view)
                view.operation = null
                operation.failure?.let { throw it }
                true
            }
        }.whenComplete { _, error ->
            if (error == null) operation.completion.complete(null) else operation.completion.completeExceptionally(error)
        }
        return operation.completion.copy()
    }

    fun closeAsync(): CompletableFuture<Void> {
        checkMain()
        retirement?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        retirement = result
        val detached = views
        views = mutableMapOf()
        var failure: Throwable? = null
        val steps = sequence {
            for ((playerId, view) in detached) {
                view.operation?.completion?.completeExceptionally(CancellationException("Road previews closed"))
                for (key in view.shown.keys) {
                    try {
                        val player = server.getPlayer(playerId)
                        val world = server.getWorld(view.worldId)
                        if (player != null && player.isOnline && player.world.uid == view.worldId && world != null &&
                            world.isChunkLoaded(key.x shr 4, key.z shr 4)
                        ) {
                            val block = world.getBlockAt(key.x, key.y, key.z)
                            player.sendBlockChange(block.location, block.blockData)
                        }
                    } catch (error: Throwable) {
                        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                    }
                    yield(Unit)
                }
                yield(Unit)
            }
        }.iterator()
        try {
            cleanup.submit("Trails:road-previews") {
                checkMain()
                if (steps.hasNext()) { steps.next(); false }
                else { failure?.let { throw it }; true }
            }.whenComplete { _, error ->
                if (error == null) result.complete(null) else result.completeExceptionally(error)
            }
        } catch (error: Throwable) { result.completeExceptionally(error) }
        return result.copy()
    }

    override fun close() { closeAsync() }
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "Road previews belong to the native server thread" }
}
