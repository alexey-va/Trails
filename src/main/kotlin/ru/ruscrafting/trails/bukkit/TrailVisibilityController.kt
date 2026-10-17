package ru.ruscrafting.trails.bukkit

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.block.Block
import ru.arc.core.BudgetedTaskQueue
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

/** One loaded chunk or one tracked block per shared native step; cold chunk data waits outside the queue. */
internal class TrailVisibilityController(
    private val server: Server,
    private val nativeWork: BudgetedTaskQueue,
    private val nextTrackedBlock: (Chunk, Int?) -> Pair<Int, Block>?,
    private val prepareChunk: (Chunk) -> CompletableFuture<Unit>,
) : AutoCloseable {
    private class Request(val center: Location, val radius: Double, val particle: Particle, val current: () -> Boolean) {
        val worldId: UUID = center.world!!.uid
        val chunkRadius = ceil(radius / 16.0).toInt()
        val minX = (center.blockX shr 4) - chunkRadius
        val maxX = (center.blockX shr 4) + chunkRadius
        val minZ = (center.blockZ shr 4) - chunkRadius
        val maxZ = (center.blockZ shr 4) + chunkRadius
        var x = minX
        var z = minZ
    }
    private data class ChunkReady(val x: Int, val z: Int, val ready: CompletableFuture<Unit>?)
    private var requests = mutableMapOf<UUID, Request>()
    private var closed = false

    fun show(playerId: UUID, radius: Double, particle: Particle, isCurrent: () -> Boolean): CompletableFuture<Void> {
        check(Bukkit.isPrimaryThread()) { "Trail visibility belongs to the native server thread" }
        require(radius in 1.0..128.0) { "Trail visibility radius must be between 1 and 128 blocks" }
        if (closed) return CompletableFuture.failedFuture(CancellationException("Trail visibility is closed"))
        val player = server.getPlayer(playerId)?.takeIf { it.isOnline }
            ?: return CompletableFuture.failedFuture(CancellationException("Trail viewer is offline"))
        val request = Request(player.location.clone(), radius, particle, isCurrent)
        requests[playerId] = request
        return advance(playerId, request).thenApply<Void> { null }.copy()
    }

    private fun current(playerId: UUID, request: Request): Boolean =
        !closed && requests[playerId] === request && request.current()

    private fun advance(playerId: UUID, request: Request): CompletableFuture<Unit> = nativeWork.call({ current(playerId, request) }) {
        val player = server.getPlayer(playerId)
        val world = server.getWorld(request.worldId)
        if (player == null || !player.isOnline || player.world.uid != request.worldId || world == null || request.x > request.maxX) {
            requests.remove(playerId, request)
            null
        } else {
            val x = request.x
            val z = request.z
            if (++request.z > request.maxZ) { request.z = request.minZ; request.x++ }
            if (world.isChunkLoaded(x, z)) {
                val chunk = world.getChunkAt(x, z, false)
                ChunkReady(x, z, prepareChunk(chunk))
            } else ChunkReady(x, z, null)
        }
    }.thenCompose { captured ->
        when {
            captured == null -> CompletableFuture.completedFuture(Unit)
            captured.ready != null -> captured.ready.thenCompose { drawChunk(playerId, request, captured.x, captured.z) }
                .thenCompose { advance(playerId, request) }
            else -> advance(playerId, request)
        }
    }

    private fun drawChunk(playerId: UUID, request: Request, x: Int, z: Int): CompletableFuture<Unit> {
        var after: Int? = null
        var waiting: CompletableFuture<Unit>? = null
        // Readiness may complete on a storage worker; admission itself belongs to the native owner.
        return nativeWork.call({ current(playerId, request) }) { nativeWork.submit({ current(playerId, request) }) {
            val player = server.getPlayer(playerId)
            val world = server.getWorld(request.worldId)
            if (player == null || !player.isOnline || player.world.uid != request.worldId || world == null ||
                !world.isChunkLoaded(x, z)
            ) return@submit true
            // A chunk may unload and reload while its previous preparation was in flight.
            // Resolve the current loaded instance and return its cold readiness outside the queue.
            val chunk = world.getChunkAt(x, z, false)
            val prepared = prepareChunk(chunk)
            if (!prepared.isDone || prepared.isCompletedExceptionally) {
                waiting = prepared
                return@submit true
            }
            val next = nextTrackedBlock(chunk, after) ?: return@submit true
            after = next.first
            val location = next.second.location.add(0.5, 1.1, 0.5)
            if (location.distanceSquared(request.center) <= request.radius * request.radius) {
                player.spawnParticle(request.particle, location, 2, 0.2, 0.1, 0.2, 0.0)
            }
            false
        } }.thenCompose { it }
            .thenCompose { waiting?.thenCompose { drawChunk(playerId, request, x, z) } ?: CompletableFuture.completedFuture(Unit) }
    }

    override fun close() {
        check(Bukkit.isPrimaryThread()) { "Trail visibility belongs to the native server thread" }
        closed = true
        requests = mutableMapOf()
    }
}
