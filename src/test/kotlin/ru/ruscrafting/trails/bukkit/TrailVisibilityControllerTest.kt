package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.entity.Player
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class TrailVisibilityControllerTest : FunSpec({
    test("cold chunk preparation releases the native queue and 100 particles advance in shared slices") {
        withTrailVisibilityFixture { f ->
            val shown = f.controller.show(f.playerId, 30.0, Particle.CRIT) { true }
            f.awaitColdChunk()
            f.preparations shouldBe 1
            f.cursorCalls shouldBe 0
            shown.isDone shouldBe false
            var unrelated = false
            val unrelatedDone = f.queue.call({ true }) { unrelated = true }
            repeat(10) { if (!unrelatedDone.isDone) f.scheduler.tick() }
            unrelatedDone.isDone shouldBe true
            unrelated shouldBe true
            shown.isDone shouldBe false
            Thread { f.ready.complete(Unit) }.apply { start(); join() }
            f.finish(shown)
            f.particles.size shouldBe 100
            f.particles.forEach { at ->
                at.y shouldBe 65.1
                (at.x % 1.0) shouldBe 0.5
                (at.z % 1.0) shouldBe 0.5
            }
            f.world.isChunkLoaded(-2, -2) shouldBe false
        }
    }

    test("superseded preparation cannot emit late particles after a newer request or close") {
        withTrailVisibilityFixture { f ->
            val old = f.controller.show(f.playerId, 30.0, Particle.CRIT) { true }
            f.awaitColdChunk()
            val current = f.controller.show(f.playerId, 30.0, Particle.CRIT) { true }
            f.finish(current)
            f.particles.size shouldBe 100
            f.controller.close()
            Thread { f.ready.complete(Unit) }.apply { start(); join() }
            repeat(20) { f.scheduler.tick() }
            old.isCompletedExceptionally shouldBe true
            f.particles.size shouldBe 100
        }
    }
})

private inline fun withTrailVisibilityFixture(block: (TrailVisibilityFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val fixture = TrailVisibilityFixture(paper)
        try { block(fixture) } finally {
            fixture.controller.close()
            fixture.ready.complete(Unit)
            fixture.queue.close()
            fixture.scope.close()
        }
    }
}

private class TrailVisibilityFixture(paper: MockBukkitTestRuntime) {
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    val queue = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
    val world = paper.addSimpleWorld("visible-trails")
    private val server = mockk<Server>()
    private val player = mockk<Player>()
    val playerId: UUID = UUID.randomUUID()
    val ready = CompletableFuture<Unit>()
    var preparations = 0
    var cursorCalls = 0
    val particles = mutableListOf<Location>()
    val controller: TrailVisibilityController

    init {
        world.loadChunk(0, 0)
        val blocks = (0 until 100).map { world.getBlockAt(it % 10, 64, it / 10) }
        every { server.getPlayer(playerId) } answers { Bukkit.isPrimaryThread() shouldBe true; player }
        every { server.getWorld(world.uid) } answers { Bukkit.isPrimaryThread() shouldBe true; world }
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.location } returns Location(world, 8.0, 64.0, 8.0)
        every { player.spawnParticle(Particle.CRIT, any<Location>(), 2, 0.2, 0.1, 0.2, 0.0) } answers {
            Bukkit.isPrimaryThread() shouldBe true
            particles.add(secondArg<Location>().clone())
        }
        controller = TrailVisibilityController(server, queue, { _, after ->
            Bukkit.isPrimaryThread() shouldBe true
            cursorCalls++
            val next = (after ?: -1) + 1
            blocks.getOrNull(next)?.let { next to it }
        }, { _ ->
            Bukkit.isPrimaryThread() shouldBe true
            preparations++
            if (preparations == 1) ready.copy() else CompletableFuture.completedFuture(Unit)
        })
    }

    fun awaitColdChunk() {
        repeat(100) { if (preparations == 0) scheduler.tick() }
        preparations shouldBe 1
    }

    fun finish(future: CompletableFuture<Void>) {
        repeat(200) {
            if (!future.isDone) {
                val before = cursorCalls
                scheduler.tick()
                (cursorCalls - before <= 7) shouldBe true
            }
        }
        future.isDone shouldBe true
        future.join()
    }
}
