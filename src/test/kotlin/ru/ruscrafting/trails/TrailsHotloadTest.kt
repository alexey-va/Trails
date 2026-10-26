package ru.ruscrafting.trails

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.event.HandlerList
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.runtime.PluginHotloadState
import ru.ruscrafting.trails.bukkit.RoadManager
import java.util.concurrent.CompletableFuture

class TrailsHotloadTest : FreeSpec({
    "prepare preserves pending native cleanup and a fresh generation restores gameplay registrations" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcCore")
                PaperNativeCleanupServiceProvider(host).use {
                    val plugin = paper.loadPlugin(TrailsPlugin::class.java)
                    awaitTrailsReady(plugin, paper)
                    val listenerCount = HandlerList.getRegisteredListeners(plugin).size
                    val hotload = TrailsPlugin::class.java.getDeclaredField("hotload").apply { isAccessible = true }
                        .get(plugin) as PaperPluginHotload
                    val native = CompletableFuture<Void>()
                    val roads = mockk<RoadManager> { every { closeAsync() } returns native }
                    TrailsPlugin::class.java.getDeclaredField("roadManager").apply { isAccessible = true }.set(plugin, roads)
                    try {
                        repeat(2) {
                            paper.server.dispatchCommand(paper.server.consoleSender, "trails:hotload prepare") shouldBe true
                        }
                        plugin.isEnabled shouldBe true
                        plugin.isReady() shouldBe false
                        plugin.drained().isDone shouldBe false
                        hotload.status().phases.getValue("storage").state shouldBe PluginHotloadState.READY
                        hotload.status().phases.getValue("native").state shouldBe PluginHotloadState.PREPARING
                        HandlerList.getRegisteredListeners(plugin).isEmpty() shouldBe true
                        paper.server.dispatchCommand(paper.server.consoleSender, "trails:hotload status") shouldBe true
                        native.complete(null)
                        plugin.drained().awaitTrails(paper.server)
                        hotload.status().state shouldBe PluginHotloadState.READY
                        paper.server.pluginManager.disablePlugin(plugin)
                        paper.server.commandMap.getCommand("trails:hotload") shouldBe null

                        paper.server.pluginManager.enablePlugin(plugin)
                        awaitTrailsReady(plugin, paper)
                        hotload.status().state shouldBe PluginHotloadState.ACTIVE
                        HandlerList.getRegisteredListeners(plugin).size shouldBe listenerCount
                        paper.server.pluginManager.disablePlugin(plugin)
                        plugin.drained().awaitTrails(paper.server)
                    } finally {
                        native.complete(null)
                        paper.server.pluginManager.disablePlugin(plugin)
                        plugin.drained().awaitTrails(paper.server)
                    }
                }
            }
        }
    }
})
