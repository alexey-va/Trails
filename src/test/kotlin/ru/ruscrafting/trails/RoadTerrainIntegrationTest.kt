package ru.ruscrafting.trails

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.type.Stairs
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class RoadTerrainIntegrationTest :
    FreeSpec({
        lateinit var server: ServerMock
        lateinit var plugin: TrailsPlugin
        lateinit var runtime: MockBukkitTestRuntime

        beforeTest {
            runtime = MockBukkitTestRuntime.open()
            server = runtime.server
            plugin = runtime.loadPlugin(TrailsPlugin::class.java)
        }

        afterTest {
            runtime.close()
        }

        "a wide road fills one-block cross-slope depressions at the row grade" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("DepressionRoadBuilder").also { it.isOp = true }
            for (x in -1..3) {
                for (z in -1..0) world.getBlockAt(x, 64, z).type = Material.STONE
                world.getBlockAt(x, 63, 1).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            enableRoads(plugin)

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            setOf(Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL) shouldContain
                world.getBlockAt(1, 64, 1).type
            world.getBlockAt(1, 63, 1).type shouldBe Material.STONE
        }

        "a longitudinal rise creates one complete stair row instead of an isolated stair" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("CompleteStairRoadBuilder").also { it.isOp = true }
            for (z in -1..1) {
                world.getBlockAt(0, 64, z).type = Material.STONE
                world.getBlockAt(1, 64, z).type = Material.STONE
            }
            world.getBlockAt(1, 65, 0).type = Material.STONE
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            enableRoads(plugin)

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 1.5, 66.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            (-1..1).forEach { z ->
                (world.getBlockAt(1, 65, z).blockData is Stairs) shouldBe true
            }
        }

        "an isolated one-row bump is graded away instead of becoming a stair" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("BumpRoadBuilder").also { it.isOp = true }
            for (x in -1..3) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.getBlockAt(1, 65, 0).type = Material.STONE
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            enableRoads(plugin)

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(1, 65, 0).type shouldBe Material.AIR
            (-1..1).forEach { z ->
                (world.getBlockAt(1, 64, z).blockData is Stairs) shouldBe false
                setOf(Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.DIRT_PATH) shouldContain
                    world.getBlockAt(1, 64, z).type
            }
        }

        "road commit clears configured surface plants and undo restores them" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("ClearRoadBuilder").also { it.isOp = true }
            for (x in -1..3) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.getBlockAt(1, 65, 0).type = Material.SHORT_GRASS
            world.getBlockAt(1, 65, 1).type = Material.POPPY
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            enableRoads(plugin)

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(1, 65, 0).type shouldBe Material.AIR
            world.getBlockAt(1, 65, 1).type shouldBe Material.AIR
            plugin.inspectTrail(world.getBlockAt(1, 65, 0)) shouldBe null
            plugin.inspectTrail(world.getBlockAt(1, 65, 1)) shouldBe null

            plugin.roadUndo(admin).message shouldBe "messages.roadUndone"
            world.getBlockAt(1, 65, 0).type shouldBe Material.SHORT_GRASS
            world.getBlockAt(1, 65, 1).type shouldBe Material.POPPY
        }

        "a protection veto on plant clearance rejects the whole road commit" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("ProtectedClearanceRoadBuilder").also { it.isOp = true }
            for (x in -1..3) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.getBlockAt(1, 65, 0).type = Material.SHORT_GRASS
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            enableRoads(plugin)
            val listener = object : Listener {}
            server.pluginManager.registerEvent(
                EntityChangeBlockEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as EntityChangeBlockEvent
                    if (event.block.y == 65 && event.to == Material.AIR) event.isCancelled = true
                },
                plugin,
            )

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))

            plugin.roadCommit(admin).message shouldBe "messages.roadProtected"
            world.getBlockAt(1, 65, 0).type shouldBe Material.SHORT_GRASS
            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
        }
    })

private fun enableRoads(plugin: TrailsPlugin) {
    val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
    Files.writeString(
        roadsPath,
        Files.readString(roadsPath)
            .replace("enabled: false", "enabled: true")
            .replace("worlds: []", "worlds: [arc_qa_flat]"),
    )
    plugin.reloadTrails().getOrThrow()
}
