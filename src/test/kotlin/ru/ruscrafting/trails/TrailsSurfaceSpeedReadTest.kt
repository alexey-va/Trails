package ru.ruscrafting.trails

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.api.TrailsFootTrafficActor
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import ru.ruscrafting.trails.storage.ChunkPersistentTrailStore
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TrailsSurfaceSpeedReadTest : FunSpec({
    test("accepted path keeps its speed while a queued own footstep waits for its predecessor") {
        speedScenario { h ->
            h.path()
            val release = CompletableFuture<Unit>()
            val queued = h.store.whenReady(h.ground.chunk, predecessor = release) {}
            val footstep = h.step()
            try {
                queued.isDone shouldBe false
                footstep.isDone shouldBe false
                h.service.speedMultiplier(h.ground) shouldBe 1.3
            } finally {
                release.complete(Unit)
                CompletableFuture.allOf(queued, footstep).awaitTrails(h.runtime.server)
            }
        }
    }

    test("an unrelated foreign block clear does not slow an already accepted path") {
        speedScenario { h ->
            h.path()
            val unrelated = h.world.getBlockAt(12, 64, 12).also { it.type = Material.STONE }
            h.plugin.clearTrailData(unrelated) shouldBe true
            val footstep = h.step()
            try {
                footstep.isDone shouldBe false
                h.service.speedMultiplier(h.ground) shouldBe 1.3
            } finally { footstep.awaitTrails(h.runtime.server) }
        }
    }

    for (size in listOf(2, 16, 128)) test("$size unrelated foreign block clears keep an already accepted path speed") {
        speedScenario { h ->
            h.path()
            val unrelated = (1..size).map { index ->
                h.world.getBlockAt(index % 16, 65 + index / 256, (index / 16) % 16).also { it.type = Material.STONE }
            }
            h.plugin.clearTrailData(unrelated) { it } shouldBe true
            val footstep = h.step()
            try {
                footstep.isDone shouldBe false
                h.service.speedMultiplier(h.ground) shouldBe 1.3
            } finally { footstep.awaitTrails(h.runtime.server) }
        }
    }

    test("ordinary ready footsteps and asynchronous flushes do not change an accepted speed") {
        speedScenario { h ->
            h.path()
            repeat(3) {
                val footstep = h.step()
                footstep.isDone shouldBe true
                h.service.speedMultiplier(h.ground) shouldBe 1.3
                h.store.flushDirty()
                h.service.speedMultiplier(h.ground) shouldBe 1.3
            }
        }
    }

    test("clearing the road itself removes boost before the asynchronous clear completes") {
        speedScenario { h ->
            h.path()
            h.plugin.clearTrailData(h.ground) shouldBe true
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            val queued = h.store.whenReady(h.ground.chunk) {}
            queued.awaitTrails(h.runtime.server)
            h.service.speedMultiplier(h.ground) shouldBe 1.0
        }
    }

    test("ordinary ground and an unregistered path in a different world never inherit a boost") {
        speedScenario { h ->
            h.path()
            val plain = h.world.getBlockAt(2, 64, 0).also { it.type = Material.STONE }
            h.service.speedMultiplier(plain) shouldBe 1.0
            val other = h.runtime.server.addSimpleWorld("unregistered_speed").also { it.loadChunk(0, 0) }
                .getBlockAt(0, 64, 0).also { it.type = Material.DIRT_PATH }
            h.plugin.awaitTrailData(other.chunk).awaitTrails(h.runtime.server)
            h.service.speedMultiplier(other) shouldBe 1.0
            h.ground.type = Material.STONE
            h.service.speedMultiplier(h.ground) shouldBe 1.0
        }
    }

    test("an exceptional pending read cannot reuse an earlier accepted boost") {
        speedScenario { h ->
            h.path()
            val failed = h.store.whenReady(h.ground.chunk) { error("injected metadata operation failure") }
            failed.handle { _, _ -> Unit }.awaitTrails(h.runtime.server)
            h.service.speedMultiplier(h.ground) shouldBe 1.0
        }
    }

    test("a failed foreign metadata barrier cannot authorize an earlier accepted boost") {
        speedScenario { h ->
            h.path()
            val field = ChunkPersistentTrailStore::class.java.getDeclaredField("foreignBarrier").also { it.isAccessible = true }
            field.set(h.store, CompletableFuture.failedFuture<Unit>(IllegalStateException("injected foreign read failure")))
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            field.set(h.store, CompletableFuture.completedFuture(Unit))
        }
    }

    test("pending native replacement and accepted metadata removal invalidate the previous speed") {
        speedScenario { h ->
            h.path()
            val release = CompletableFuture<Unit>()
            val queued = h.store.whenReady(h.ground.chunk, predecessor = release) {}
            try {
                h.ground.type = Material.STONE
                h.service.speedMultiplier(h.ground) shouldBe 1.0
                h.ground.type = Material.DIRT_PATH
                h.service.speedMultiplier(h.ground) shouldBe 1.0
            } finally { release.complete(Unit); queued.awaitTrails(h.runtime.server) }
            h.service.speedMultiplier(h.ground) shouldBe 1.3
            val nextRelease = CompletableFuture<Unit>()
            val next = h.store.whenReady(h.ground.chunk, predecessor = nextRelease) {}
            try {
                h.store.clear(h.ground)
                h.service.speedMultiplier(h.ground) shouldBe 1.0
            } finally { nextRelease.complete(Unit); next.awaitTrails(h.runtime.server) }
        }
    }

    test("affected bulk invalidation and disabled world reload cannot reuse a previous speed") {
        speedScenario { h ->
            h.path()
            h.plugin.clearTrailData(listOf(h.ground, h.world.getBlockAt(12, 64, 12))) { it } shouldBe true
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            h.store.whenReady(h.ground.chunk) {}.awaitTrails(h.runtime.server)
            h.path()
            h.plugin.config.set("worlds.mode", "blocklist")
            h.plugin.config.set("worlds.names", listOf(h.world.name))
            h.plugin.saveConfig()
            h.plugin.reloadTrails().awaitTrails(h.runtime.server).isSuccess shouldBe true
            h.service.speedMultiplier(h.ground) shouldBe 1.0
        }
    }

    test("oversized foreign clear does no synchronous pre-scan and conservatively drops the cached speed") {
        speedScenario { h ->
            h.path()
            val unrelated = (1..129).map { index -> h.world.getBlockAt(index % 16, 65, index / 16) }
            var captured = 0
            h.plugin.clearTrailData(unrelated) { captured++; it } shouldBe true
            captured shouldBe 0
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            h.store.whenReady(h.ground.chunk) {}.awaitTrails(h.runtime.server)
            captured shouldBe 129
            h.service.speedMultiplier(h.ground) shouldBe 1.3
        }
    }
})

private fun speedScenario(block: (SurfaceSpeedScenario) -> Unit) {
    MockBukkitTestRuntime.open().use { runtime -> SurfaceSpeedScenario(runtime).use(block) }
}

private class SurfaceSpeedScenario(val runtime: MockBukkitTestRuntime) : AutoCloseable {
    private val cleanup = PaperNativeCleanupServiceProvider(runtime.createSimplePlugin("ArcCore"))
    val plugin = runtime.loadPlugin(TrailsPlugin::class.java).also { awaitTrailsReady(it, runtime) }
    val service = requireNotNull(runtime.server.servicesManager.load(TrailsFootTrafficService::class.java))
    val store = TrailsPlugin::class.java.getDeclaredField("blockStore").let { field ->
        field.isAccessible = true
        field.get(plugin) as ChunkPersistentTrailStore
    }
    val world = runtime.server.addSimpleWorld("speed_reads").also { it.loadChunk(0, 0) }
    val ground = world.getBlockAt(0, 64, 0)
    private val authority = runtime.server.addPlayer("Manager").also { it.teleport(Location(world, .5, 65.0, .5)) }
    private val actor = object : TrailsFootTrafficActor {
        private val id = UUID.randomUUID()
        override fun uniqueId() = id
        override fun location() = Location(world, 1.1, 64.9375, .5)
        override fun isValid() = true
    }
    fun path() {
        ground.type = Material.DIRT_PATH
        service.registerSurface(ground).toCompletableFuture().awaitTrails(runtime.server) shouldBe true
        service.speedMultiplier(ground) shouldBe 1.3
    }
    fun step() = service.recordStep(actor, authority,
        Location(world, .9, 64.9375, .5), actor.location()).toCompletableFuture()
    override fun close() {
        runtime.server.pluginManager.disablePlugin(plugin)
        plugin.drained().awaitTrails(runtime.server)
        cleanup.close()
    }
}
