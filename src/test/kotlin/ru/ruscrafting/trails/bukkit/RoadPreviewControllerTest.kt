package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

class RoadPreviewControllerTest : FunSpec({
    test("a superseding plan keeps partially sent cells until their bounded restoration") {
        withRoadPreviewFixture { f ->
            val first = (0 until 30).map { f.cell(it, Material.COBBLESTONE) }
            val old = f.replace(first)
            f.scheduler.tick()
            f.sent.size shouldBe 7
            old.isDone shouldBe false
            val second = (25 until 45).map { f.cell(it, Material.SANDSTONE) }
            val next = f.replace(second)
            old.isCompletedExceptionally shouldBe true
            f.finish(next)
            (0 until 7).forEach { f.client[it] shouldBe Material.STONE }
            (7 until 25).forEach { f.client[it] shouldBe null }
            (25 until 45).forEach { f.client[it] shouldBe Material.SANDSTONE }
            val cancelled = f.controller.cancel(f.playerId)
            f.finish(cancelled)
            (25 until 45).forEach { f.client[it] shouldBe Material.STONE }
        }
    }

    test("closed consumer admission preserves old handles for actual host cleanup") {
        withRoadPreviewFixture { f ->
            val active = f.replace((0 until 30).map { f.cell(it, Material.COBBLESTONE) })
            f.scheduler.tick()
            f.sent.size shouldBe 7
            f.native.close()
            active.isCompletedExceptionally shouldBe true
            f.replace(listOf(f.cell(40, Material.SANDSTONE))).isCompletedExceptionally shouldBe true
            val observer = f.controller.closeAsync()
            observer.isDone shouldBe false
            observer.cancel(false)
            val actual = f.controller.closeAsync()
            f.finish(actual)
            (0 until 7).forEach { f.client[it] shouldBe Material.STONE }
            f.client[40] shouldBe null
        }
    }

    test("clearance and fallback materials are unchanged and host stop reports incomplete restoration") {
        withRoadPreviewFixture { f ->
            val cells = listOf(f.cell(0, Material.AIR, true), f.cell(1, Material.DIRT_PATH), f.cell(2, Material.COBBLESTONE))
            val shown = f.replace(cells)
            f.finish(shown)
            f.client[0] shouldBe Material.AIR
            f.client[1] shouldBe Material.YELLOW_CONCRETE
            f.client[2] shouldBe Material.COBBLESTONE
            val retired = f.controller.closeAsync()
            f.host.close()
            retired.isCompletedExceptionally shouldBe true
        }
    }
})

private inline fun withRoadPreviewFixture(block: (RoadPreviewFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val fixture = RoadPreviewFixture(paper)
        try { block(fixture) } finally {
            val closed = fixture.controller.closeAsync()
            repeat(100) { if (!closed.isDone) fixture.scheduler.tick() }
            fixture.native.close()
            fixture.host.close()
            fixture.scope.close()
        }
    }
}

private class RoadPreviewFixture(paper: MockBukkitTestRuntime) {
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    val native = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
    val host = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
    private val world = paper.addSimpleWorld("roads")
    private val server = mockk<Server>()
    private val player = mockk<Player>()
    val playerId: UUID = UUID.randomUUID()
    val sent = mutableListOf<Pair<Int, Material>>()
    val client = mutableMapOf<Int, Material>()
    val controller: RoadPreviewController

    init {
        for (chunk in 0..2) world.loadChunk(chunk, 0)
        for (x in 0 until 48) world.getBlockAt(x, 64, 0).type = Material.STONE
        every { server.getPlayer(playerId) } answers { Bukkit.isPrimaryThread() shouldBe true; player }
        every { server.getWorld(world.uid) } answers { Bukkit.isPrimaryThread() shouldBe true; world }
        every { server.createBlockData(any<String>()) } answers { paper.server.createBlockData(firstArg<String>()) }
        every { player.isOnline } returns true
        every { player.world } returns world
        every { player.sendBlockChange(any(), any<BlockData>()) } answers {
            Bukkit.isPrimaryThread() shouldBe true
            val x = firstArg<org.bukkit.Location>().blockX
            val material = secondArg<BlockData>().material
            sent.add(x to material)
            client[x] = material
        }
        val cleanup = object : ArcNativeCleanupService {
            override fun submit(owner: String, step: BooleanSupplier) =
                host.submit({ true }, step::getAsBoolean).thenApply<Void> { null }.minimalCompletionStage()
        }
        controller = RoadPreviewController(server, native, cleanup,
            RoadPreviewSelector { data, _ -> data.material != Material.DIRT_PATH })
    }

    fun cell(x: Int, material: Material, clearance: Boolean = false) =
        RoadPreviewCell(x, 64, 0, material.createBlockData().asString, clearance)

    fun replace(cells: List<RoadPreviewCell>): CompletableFuture<Void> {
        val index = cells.associateBy { it.x }
        return controller.replace(playerId, world.uid, cells.iterator(), { x, _, _ -> index[x]?.afterData }, { true }, { true })
    }

    fun finish(future: CompletableFuture<Void>) {
        repeat(100) {
            if (!future.isDone) {
                val before = sent.size
                scheduler.tick()
                (sent.size - before <= 7) shouldBe true
            }
        }
        future.join()
    }
}
