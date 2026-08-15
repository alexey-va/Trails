package ru.ruscrafting.trails

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.ruscrafting.trails.bukkit.TrailToolKind
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
import ru.ruscrafting.trails.storage.TrailBlockState
import java.nio.file.Files

class TrailsPluginIntegrationTest :
    FreeSpec({
        lateinit var server: ServerMock
        lateinit var plugin: TrailsPlugin

        beforeTest {
            server = MockBukkit.mock()
            plugin = MockBukkit.load(TrailsPlugin::class.java)
        }

        afterTest {
            MockBukkit.unmock()
        }

        "loads, toggles preferences through the command, and disables cleanly" {
            val player = server.addPlayer("Alexey")
            plugin.trailsEnabled(player.uniqueId) shouldBe true

            server.dispatchCommand(player, "paths off") shouldBe true

            plugin.trailsEnabled(player.uniqueId) shouldBe false
        }

        "commands control personal boost and another player's trails" {
            val admin = server.addPlayer("Admin")
            val target = server.addPlayer("Target")
            admin.isOp = true

            server.dispatchCommand(admin, "trails boost off") shouldBe true
            plugin.boostEnabled(admin.uniqueId) shouldBe false
            server.dispatchCommand(admin, "trails off Target") shouldBe true
            plugin.trailsEnabled(target.uniqueId) shouldBe false
            server.dispatchCommand(server.consoleSender, "trails on Target") shouldBe true
            plugin.trailsEnabled(target.uniqueId) shouldBe true
        }

        "the legacy PlaceholderAPI value reflects the effective trail toggle" {
            val player = server.addPlayer("PlaceholderUser")
            val expansion = TrailsPlaceholderExpansion(plugin)

            expansion.onRequest(player, "toggled_on") shouldBe "true"
            plugin.setTrailsEnabled(player.uniqueId, false)
            expansion.onRequest(player, "TOGGLED_ON") shouldBe "false"
            expansion.onRequest(player, "unknown") shouldBe ""
        }

        "reload registers the configured runtime command alias" {
            val player = server.addPlayer("LegacyCommandUser")
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(
                configPath,
                Files.readString(configPath).replace("  localized-alias: ''", "  localized-alias: footpaths"),
            )

            plugin.reloadTrails().isSuccess shouldBe true
            server.dispatchCommand(player, "footpaths off") shouldBe true
            plugin.trailsEnabled(player.uniqueId) shouldBe false
        }

        "reload is transactional and does not accumulate repeating tasks" {
            val initialTasks = server.scheduler.pendingTasks.count { it.owner == plugin }
            plugin.reloadTrails().isSuccess shouldBe true
            plugin.reloadTrails().isSuccess shouldBe true
            server.scheduler.pendingTasks.count { it.owner == plugin } shouldBe initialTasks

            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            val invalid = Files.readString(configPath).replace("  update-interval-ticks: 1", "  update-interval-ticks: 0")
            Files.writeString(configPath, invalid)

            plugin.reloadTrails().isFailure shouldBe true
            plugin.settings.speedBoostInterval shouldBe 1L
            server.scheduler.pendingTasks.count { it.owner == plugin } shouldBe initialTasks
        }

        "five successful walks advance the default DirtPath and preserve block metadata" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("Walker")
            val block = world.getBlockAt(0, 64, 0)
            block.type = Material.GRASS_BLOCK

            repeat(5) { plugin.handleMovement(player, block) }

            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"
            plugin.inspectTrail(block)?.walks shouldBe 0
        }

        "bukkit-event protection fires only for the material transition and respects cancellation" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("ProtectedWalker")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            var changeCalls = 0
            var placeCalls = 0
            val listener = object : Listener {}
            server.pluginManager.registerEvent(
                EntityChangeBlockEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as EntityChangeBlockEvent
                    changeCalls++
                    event.entity shouldBe player
                    event.block shouldBe block
                    event.to shouldBe Material.DIRT
                },
                plugin,
            )
            server.pluginManager.registerEvent(
                BlockPlaceEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as BlockPlaceEvent
                    placeCalls++
                    event.player shouldBe player
                    event.block shouldBe block
                    event.itemInHand.type shouldBe Material.DIRT
                    event.isCancelled = true
                },
                plugin,
            )

            repeat(4) { plugin.handleMovement(player, block) }
            changeCalls shouldBe 0
            placeCalls shouldBe 0
            plugin.inspectTrail(block)?.walks shouldBe 4

            plugin.handleMovement(player, block)

            changeCalls shouldBe 1
            placeCalls shouldBe 1
            block.type shouldBe Material.GRASS_BLOCK
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:0"
            plugin.inspectTrail(block)?.walks shouldBe 4
        }

        "bukkit-event protection treats canBuild false as a veto" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("BuildDeniedWalker")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            val listener = object : Listener {}
            server.pluginManager.registerEvent(
                BlockPlaceEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw -> (raw as BlockPlaceEvent).setBuild(false) },
                plugin,
            )

            repeat(5) { plugin.handleMovement(player, block) }

            block.type shouldBe Material.GRASS_BLOCK
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:0"
            plugin.inspectTrail(block)?.walks shouldBe 4
        }

        "creation respects the personal toggle, sneak bypass, and enabled worlds" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("CarefulWalker")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }

            plugin.setTrailsEnabled(player.uniqueId, false)
            repeat(5) { plugin.handleMovement(player, block) }
            plugin.inspectTrail(block) shouldBe null

            plugin.setTrailsEnabled(player.uniqueId, true)
            player.isSneaking = true
            repeat(5) { plugin.handleMovement(player, block) }
            plugin.inspectTrail(block) shouldBe null

            player.isSneaking = false
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(
                configPath,
                Files.readString(configPath)
                    .replace("  mode: all", "  mode: allowlist")
                    .replace("  names: []", "  names: [survival]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true
            repeat(5) { plugin.handleMovement(player, block) }
            plugin.inspectTrail(block) shouldBe null
        }

        "permission-gated creation requires trails.create-trails" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("PermittedWalker")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(
                configPath,
                Files.readString(configPath).replace(
                    "  require-permission: false",
                    "  require-permission: true",
                ),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            repeat(5) { plugin.handleMovement(player, block) }
            plugin.inspectTrail(block) shouldBe null

            player.addAttachment(plugin, "trails.create-trails", true)
            repeat(5) { plugin.handleMovement(player, block) }
            block.type shouldBe Material.DIRT
        }

        "the trail tool advances immediately and block breaking clears legacy metadata" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("Builder")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }

            plugin.forceTrail(player, block)
            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"

            server.pluginManager.callEvent(BlockBreakEvent(block, player))
            server.scheduler.performTicks(1)
            plugin.inspectTrail(block) shouldBe null
        }

        "natural material decay fires BlockFadeEvent and respects cancellation" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("DecayGuard")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            plugin.forceTrail(player, block)
            block.type shouldBe Material.DIRT
            val listener = object : Listener {}
            server.pluginManager.registerEvent(
                BlockFadeEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw -> (raw as BlockFadeEvent).isCancelled = true },
                plugin,
            )

            plugin.decayBlock(block) shouldBe false

            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"
        }

        "only tagged command-issued tools trigger trail actions" {
            val world = server.addSimpleWorld("world")
            val admin = server.addPlayer("ToolAdmin")
            admin.isOp = true
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }

            server.pluginManager.callEvent(
                PlayerInteractEvent(
                    admin,
                    Action.RIGHT_CLICK_BLOCK,
                    ItemStack(Material.IRON_SHOVEL),
                    block,
                    BlockFace.UP,
                    EquipmentSlot.HAND,
                ),
            )
            plugin.inspectTrail(block) shouldBe null

            server.dispatchCommand(admin, "trails give advance") shouldBe true
            val tagged = admin.inventory.contents.filterNotNull().single { plugin.toolKind(it) == TrailToolKind.ADVANCE }
            server.pluginManager.callEvent(
                PlayerInteractEvent(
                    admin,
                    Action.RIGHT_CLICK_BLOCK,
                    tagged,
                    block,
                    BlockFace.UP,
                    EquipmentSlot.HAND,
                ),
            )

            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"
        }

        "give command requires permission and can target an online player" {
            val regular = server.addPlayer("Regular")
            val target = server.addPlayer("ToolTarget")

            server.dispatchCommand(regular, "trails give inspect") shouldBe true
            regular.inventory.contents.filterNotNull().size shouldBe 0

            server.dispatchCommand(server.consoleSender, "trails give inspect ToolTarget") shouldBe true
            target.inventory.contents.filterNotNull().single().let(plugin::toolKind) shouldBe TrailToolKind.INSPECT
        }

        "status and validate commands expose the active v3 configuration" {
            val admin = server.addPlayer("StatusAdmin")
            admin.isOp = true

            server.dispatchCommand(admin, "trails status") shouldBe true
            server.dispatchCommand(admin, "trails validate") shouldBe true
        }

        "roads preview is client-only until commit and undo restores exact blocks" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("RoadBuilder")
            admin.isOp = true
            for (x in -2..10) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
            }
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            server.dispatchCommand(admin, "trails road start rustic") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(admin.nextComponentMessage())) shouldContain "DIRT_PATH"
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(admin.nextComponentMessage())) shouldContain "rustic"
            plugin.captureRoadMovement(admin, Location(world, 8.5, 65.0, 0.5)) shouldBe true
            server.scheduler.performTicks(5)
            world.getBlockAt(4, 64, 0).type shouldBe Material.GRASS_BLOCK

            server.dispatchCommand(admin, "trails road commit") shouldBe true
            world.getBlockAt(4, 64, 0).type shouldBe Material.DIRT_PATH
            plugin.inspectTrail(world.getBlockAt(4, 64, 0))?.walks shouldBe 0

            server.dispatchCommand(admin, "trails road undo") shouldBe true
            world.getBlockAt(4, 64, 0).type shouldBe Material.GRASS_BLOCK
            plugin.inspectTrail(world.getBlockAt(4, 64, 0)) shouldBe null

            server.dispatchCommand(admin, "trails road start rustic") shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(admin, "trails road commit") shouldBe true
            world.getBlockAt(1, 64, 0).type = Material.STONE

            server.dispatchCommand(admin, "trails road undo") shouldBe true

            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 1).type shouldBe Material.COARSE_DIRT
        }

        "roads can commit decorative profiles that are not natural trail stages" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("StoneRoadBuilder")
            admin.isOp = true
            for (x in -2..4) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
            }
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            server.dispatchCommand(admin, "trails road start cobblestone") shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 3.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(admin, "trails road commit") shouldBe true

            world.getBlockAt(2, 64, 0).type shouldBe Material.STONE_BRICKS
            world.getBlockAt(2, 64, 1).type shouldBe Material.COBBLESTONE
            plugin.inspectTrail(world.getBlockAt(2, 64, 0)) shouldBe TrailBlockState(null, 0)
        }

        "roads can start on and repaint configured road materials" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("RoadRebuilder")
            admin.isOp = true
            for (x in -2..4) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.STONE_BRICKS
            }
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "boardwalk").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 3.5, 65.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(2, 64, 0).type shouldBe Material.SPRUCE_PLANKS
        }

        "roads resync after movement beyond the configured segment limit" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("JumpingRoadBuilder")
            admin.isOp = true
            for (x in -2..24) world.getBlockAt(x, 64, 0).type = Material.GRASS_BLOCK
            world.loadChunk(0, 0)
            world.loadChunk(1, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            server.dispatchCommand(admin, "trails road start footpath") shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 20.5, 65.0, 0.5)) shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 21.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(admin, "trails road commit") shouldBe true

            world.getBlockAt(10, 64, 0).type shouldBe Material.GRASS_BLOCK
            world.getBlockAt(20, 64, 0).type shouldBe Material.DIRT_PATH
            world.getBlockAt(21, 64, 0).type shouldBe Material.DIRT_PATH
        }

        "roads commit is all-or-nothing when a protection event vetoes one block" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("ProtectedRoadBuilder")
            admin.isOp = true
            for (x in -2..3) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
            }
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true
            val listener = object : Listener {}
            server.pluginManager.registerEvent(
                BlockPlaceEvent::class.java,
                listener,
                EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as BlockPlaceEvent
                    if (event.block.x == 1 && event.block.z == 0) event.isCancelled = true
                },
                plugin,
            )

            server.dispatchCommand(admin, "trails road start rustic") shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(admin, "trails road commit") shouldBe true

            for (x in 0..1) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type shouldBe Material.GRASS_BLOCK
            }
        }
    })
