package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.service.TrailService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DecaySchedulerTest :
    FreeSpec({
        "fractional sampling can select zero instead of decaying every small chunk" {
            DecayScheduler.sampleSize(size = 1, fraction = 0.03, random = 0.5) shouldBe 0
            DecayScheduler.sampleSize(size = 1, fraction = 0.03, random = 0.01) shouldBe 1
        }

        "sampling never exceeds the chunk's tracked blocks" {
            DecayScheduler.sampleSize(size = 3, fraction = 1.0, random = 0.0) shouldBe 3
            DecayScheduler.sampleSize(size = 0, fraction = 1.0, random = 0.0) shouldBe 0
        }

        "idle decay waits for the full configured quiet period" {
            DecayScheduler.isIdle(1_000L, 60_999L, 1L) shouldBe false
            DecayScheduler.isIdle(1_000L, 61_000L, 1L) shouldBe true
            DecayScheduler.isIdle(61_000L, 1_000L, 1L) shouldBe false
        }

        "edge-first falls back to all idle blocks for a closed route" {
            DecayScheduler.preferEdges(listOf("a", "b")) { false } shouldBe listOf("a", "b")
            DecayScheduler.preferEdges(listOf("a", "b")) { it == "b" } shouldBe listOf("b")
        }

        "a cold 300-block cycle waits outside the shared queue and captures or mutates at most seven blocks per tick" {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("TrailDecayBudget")
                val world = paper.addSimpleWorld("decay")
                world.loadChunk(0, 0)
                val blocks = (0 until 300).map { index ->
                    world.getBlockAt(index % 16, 64 + index / 256, index / 16 % 16).also { it.type = Material.DIRT_PATH }
                }
                val settings = mockk<TrailsSettings>()
                every { settings.decayTimer } returns 10_000L
                every { settings.worldEnabled(any()) } returns true
                every { settings.chunkChance } returns 1.0
                every { settings.decayEdgeFirst } returns false
                every { settings.decayFraction } returns 1.0
                every { settings.decayMinimumIdleMinutes } returns 1L
                every { settings.decayDistance } returns 0.0
                every { settings.stepDecayFraction } returns 1.0
                val ready = CompletableFuture<Unit>()
                val service = mockk<TrailService>()
                var nativeCalls = 0
                var changed = 0
                var preparations = 0
                every { service.isChunkReady(any()) } answers { Bukkit.isPrimaryThread() shouldBe true; ready.isDone }
                every { service.prepareChunk(any()) } answers { Bukkit.isPrimaryThread() shouldBe true; preparations++; ready }
                every { service.nextTrackedBlock(any(), any()) } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    val position = (secondArg<Int?>() ?: -1) + 1
                    blocks.getOrNull(position)?.let { nativeCalls++; position to it }
                }
                every { service.lastActivityMillis(any()) } returns 0L
                every { service.decay(any(), any(), any()) } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    nativeCalls++; changed++; true
                }
                val scheduler = TestTaskScheduler()
                val scope = LifecycleTaskScope(scheduler)
                val native = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
                val storage = BoundedAsyncExecutor("decay-budget-test")
                val decay = DecayScheduler(plugin, settings, service, native, storage,
                    random = Random(7), currentTimeMillis = { 60_000L })
                try {
                    val cycle = decay.runCycle()
                    repeat(100) { if (preparations == 0) scheduler.tick() }
                    preparations shouldBe 1
                    cycle.isDone shouldBe false
                    nativeCalls shouldBe 0
                    var unrelated = false
                    val sentinel = native.call({ true }) { unrelated = true }
                    repeat(10) { if (!sentinel.isDone) scheduler.tick() }
                    unrelated shouldBe true
                    Thread { ready.complete(Unit) }.apply { start(); join() }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                    while (!cycle.isDone && System.nanoTime() < deadline) {
                        val before = nativeCalls
                        scheduler.tick()
                        (nativeCalls - before <= 7) shouldBe true
                        Thread.sleep(1)
                    }
                    cycle.isDone shouldBe true
                    cycle.join()
                    changed shouldBe 300
                    decay.snapshot().trackedBlocks shouldBe 300
                } finally {
                    ready.complete(Unit)
                    decay.close(); native.close(); scope.close()
                    storage.closeAsync().get(5, TimeUnit.SECONDS)
                }
            }
        }
    })
