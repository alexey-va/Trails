package ru.ruscrafting.trails

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.bukkit.RoadNotice
import ru.ruscrafting.trails.bukkit.TrailToolKind
import ru.ruscrafting.trails.domain.TrailIdentity
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
import ru.ruscrafting.trails.storage.TrailBlockState
import java.nio.file.Files

class TrailsPluginIntegrationTest :
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

        "tab completion exposes only routes allowed for the sender" {
            val regular = server.addPlayer("RegularCompleter")
            val admin = server.addPlayer("AdminCompleter").also { it.isOp = true }

            val regularRoutes = server.getCommandTabComplete(regular, "trails ")
            regularRoutes shouldContain "on"
            regularRoutes shouldContain "boost"
            regularRoutes shouldNotContain "reload"
            regularRoutes shouldNotContain "road"

            val adminRoutes = server.getCommandTabComplete(admin, "trails ")
            adminRoutes shouldContain "reload"
            adminRoutes shouldContain "road"
            server.getCommandTabComplete(admin, "trails road start ") shouldContain "rustic"
            server.getCommandTabComplete(admin, "trails road list ") shouldContain "forest_walk"
        }

        "road protection bypass is granted to operators but not regular builders by default" {
            server.pluginManager.getPermission("trails.roads.bypass-protection")?.default shouldBe PermissionDefault.OP
            server.addPlayer("RegularRoadBuilder").hasPermission("trails.roads.bypass-protection") shouldBe false
            server.addPlayer("OperatorRoadBuilder").also { it.isOp = true }
                .hasPermission("trails.roads.bypass-protection") shouldBe true
        }

        "road use permission exposes only the player's own session" {
            server.pluginManager.getPermission("trails.roads.use")?.default shouldBe PermissionDefault.FALSE
            server.pluginManager.getPermission("trails.roads.manage")?.default shouldBe PermissionDefault.OP
            val roadUser = server.addPlayer("RoadUser")
            val target = server.addPlayer("OtherRoadUser")
            roadUser.addAttachment(plugin, "trails.roads.use", true)

            server.getCommandTabComplete(roadUser, "trails ") shouldContain "road"
            server.getCommandTabComplete(roadUser, "trails road status ") shouldNotContain target.name
            server.dispatchCommand(roadUser, "trails road list footpath") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(roadUser.nextComponentMessage())) shouldContain "footpath"

            server.dispatchCommand(roadUser, "trails road status ${target.name}") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(checkNotNull(roadUser.nextComponentMessage())) shouldContain
                "permission to change another player"

            val manager = server.addPlayer("RoadManager").also { it.isOp = true }
            server.getCommandTabComplete(manager, "trails road status ") shouldContain target.name
        }

        "road list command renders the localized description for a selected profile" {
            val admin = server.addPlayer("RoadCatalogAdmin")
            admin.isOp = true

            server.dispatchCommand(admin, "trails road list lantern_lane") shouldBe true

            val description = PlainTextComponentSerializer.plainText().serialize(checkNotNull(admin.nextComponentMessage()))
            description shouldContain "lantern_lane"
            description shouldContain "12"
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

        "reload keeps the active task set when a replacement interval overflows" {
            val initialTasks = server.scheduler.pendingTasks.count { it.owner == plugin }
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(
                configPath,
                Files.readString(configPath).replace(
                    "  player-preferences-save-interval-minutes: 5",
                    "  player-preferences-save-interval-minutes: ${Long.MAX_VALUE}",
                ),
            )

            plugin.reloadTrails().isFailure shouldBe true

            plugin.settings.saveIntervalMinutes shouldBe 5L
            server.scheduler.pendingTasks.count { it.owner == plugin } shouldBe initialTasks
        }

        "five successful walks advance the default DirtPath and preserve block state" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("Walker")
            val block = world.getBlockAt(0, 64, 0)
            block.type = Material.GRASS_BLOCK

            repeat(5) { plugin.handleMovement(player, block) }

            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"
            plugin.inspectTrail(block)?.walks shouldBe 0
            block.chunk.persistentDataContainer.has(
                NamespacedKey(plugin, "block_states_v1"),
                PersistentDataType.BYTE_ARRAY,
            ) shouldBe false

            server.scheduler.performTicks(20)

            block.chunk.persistentDataContainer.has(
                NamespacedKey(plugin, "block_states_v1"),
                PersistentDataType.BYTE_ARRAY,
            ) shouldBe true
        }

        "the registered movement listener adapts block changes into trail progress" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("EventWalker")
            val from = Location(world, 0.5, 65.0, 0.5)
            val to = Location(world, 1.5, 65.0, 0.5)
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            player.teleport(from)

            repeat(5) {
                server.pluginManager.callEvent(PlayerMoveEvent(player, from, to))
            }

            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block) shouldBe TrailBlockState(TrailIdentity("DirtPath", 1), 0)
        }

        "the registered movement listener ignores cancelled movement" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("CancelledWalker")
            val from = Location(world, 0.5, 65.0, 0.5)
            val to = Location(world, 1.5, 65.0, 0.5)
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            player.teleport(from)

            repeat(5) {
                server.pluginManager.callEvent(PlayerMoveEvent(player, from, to).also { event -> event.isCancelled = true })
            }

            block.type shouldBe Material.GRASS_BLOCK
            plugin.inspectTrail(block) shouldBe null
        }

        "teleport and plugin shutdown restore managed speed and cancel runtime tasks" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("BoostedWalker")
            val location = Location(world, 0.5, 65.0, 0.5)
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            player.teleport(location)
            player.walkSpeed = 0.2F
            plugin.forceTrail(player, block)

            plugin.handleMovement(player, block, createTrail = false)
            server.scheduler.performTicks(1)
            player.walkSpeed shouldNotBe 0.2F

            server.pluginManager.callEvent(PlayerTeleportEvent(player, location, location.clone().add(4.0, 0.0, 0.0)))
            player.walkSpeed shouldBeExactly 0.2F

            plugin.handleMovement(player, block, createTrail = false)
            server.scheduler.performTicks(1)
            player.walkSpeed shouldNotBe 0.2F

            server.pluginManager.disablePlugin(plugin)

            player.walkSpeed shouldBeExactly 0.2F
            server.scheduler.pendingTasks.none { it.owner == plugin } shouldBe true
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

        "permission-gated boost requires trails.boost" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("BoostPermissionWalker")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(
                configPath,
                Files.readString(configPath).replace(
                    "speed-boost:\n  require-permission: false",
                    "speed-boost:\n  require-permission: true",
                ),
            )
            plugin.reloadTrails().isSuccess shouldBe true
            player.walkSpeed = 0.2F
            plugin.forceTrail(player, block)

            plugin.handleMovement(player, block, createTrail = false)
            server.scheduler.performTicks(1)
            player.walkSpeed shouldBeExactly 0.2F

            player.addAttachment(plugin, "trails.boost", true)
            plugin.handleMovement(player, block, createTrail = false)
            server.scheduler.performTicks(1)
            player.walkSpeed shouldNotBe 0.2F
        }

        "the trail tool advances immediately and block breaking clears persisted state" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("Builder")
            val block = world.getBlockAt(0, 64, 0).also { it.type = Material.GRASS_BLOCK }

            plugin.forceTrail(player, block)
            block.type shouldBe Material.DIRT
            plugin.inspectTrail(block)?.identity?.serialize() shouldBe "DirtPath:1"

            server.pluginManager.callEvent(BlockBreakEvent(block, player))
            plugin.inspectTrail(block) shouldBe null
        }

        "a retracting sticky piston moves persisted trail state toward the piston" {
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("PistonWalker")
            val piston = world.getBlockAt(0, 64, 0).also { it.type = Material.STICKY_PISTON }
            val destination = world.getBlockAt(1, 64, 0)
            val source = world.getBlockAt(2, 64, 0).also { it.type = Material.GRASS_BLOCK }
            plugin.forceTrail(player, source)
            val state = plugin.inspectTrail(source)

            server.pluginManager.callEvent(
                BlockPistonRetractEvent(piston, listOf(source), BlockFace.EAST),
            )

            plugin.inspectTrail(source) shouldBe null
            plugin.inspectTrail(destination) shouldBe state
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
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            val configPath = plugin.dataFolder.toPath().resolve("config.yml")
            Files.writeString(configPath, Files.readString(configPath).replace("locale: en-US", "locale: ru-RU"))
            plugin.reloadTrails().isSuccess shouldBe true

            server.dispatchCommand(admin, "trails road start rustic") shouldBe true
            val startMessage = PlainTextComponentSerializer.plainText().serialize(checkNotNull(admin.nextComponentMessage()))
            startMessage shouldContain "Предпросмотр дороги rustic включён"
            startMessage shouldContain "/trails road commit, чтобы принять изменения"
            admin.nextComponentMessage() shouldBe null
            plugin.captureRoadMovement(admin, Location(world, 8.5, 65.0, 0.5)) shouldBe true
            server.scheduler.performTicks(5)
            world.getBlockAt(4, 64, 0).type shouldBe Material.GRASS_BLOCK

            server.dispatchCommand(admin, "trails road commit") shouldBe true
            setOf(Material.DIRT_PATH, Material.COARSE_DIRT) shouldContain world.getBlockAt(4, 64, 0).type
            plugin.inspectTrail(world.getBlockAt(4, 64, 0))?.walks shouldBe 0

            server.dispatchCommand(admin, "trails road undo") shouldBe true
            world.getBlockAt(4, 64, 0).type shouldBe Material.GRASS_BLOCK
            plugin.inspectTrail(world.getBlockAt(4, 64, 0)) shouldBe null

            server.dispatchCommand(admin, "trails road start rustic") shouldBe true
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(admin, "trails road commit") shouldBe true
            val committedSideMaterial = world.getBlockAt(1, 64, 1).type
            world.getBlockAt(1, 64, 0).type = Material.STONE

            server.dispatchCommand(admin, "trails road undo") shouldBe true

            world.getBlockAt(1, 64, 0).type shouldBe Material.STONE
            world.getBlockAt(1, 64, 1).type shouldBe committedSideMaterial
        }

        "road commit replans an intervening ordinary block change and undo restores the current snapshot" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val builder = server.addPlayer("SnapshotRoadBuilder")
            builder.addAttachment(plugin, "trails.roads.manage", true)
            for (x in 0..3) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.loadChunk(0, 0)
            builder.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(builder, "footpath")
            plugin.captureRoadMovement(builder, Location(world, 2.5, 65.0, 0.5))
            plugin.roadStatus(builder)
            world.getBlockAt(1, 64, 0).type = Material.DIRT
            plugin.captureRoadMovement(builder, Location(world, 3.5, 65.0, 0.5))

            plugin.roadCommit(builder).message shouldBe "messages.roadCommitted"
            world.getBlockAt(1, 64, 0).type shouldBe Material.DIRT_PATH
            plugin.roadUndo(builder).message shouldBe "messages.roadUndone"
            world.getBlockAt(1, 64, 0).type shouldBe Material.DIRT
        }

        "road commit reloads a previewed chunk before rebuilding the current plan" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val builder = server.addPlayer("UnloadedRoadBuilder")
            builder.addAttachment(plugin, "trails.roads.manage", true)
            for (x in 14..18) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.loadChunk(0, 0)
            world.loadChunk(1, 0)
            builder.teleport(Location(world, 14.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(builder, "footpath")
            plugin.captureRoadMovement(builder, Location(world, 18.5, 65.0, 0.5))
            plugin.roadStatus(builder)
            world.unloadChunk(1, 0) shouldBe true

            plugin.roadCommit(builder).message shouldBe "messages.roadCommitted"
            world.getBlockAt(17, 64, 0).type shouldBe Material.DIRT_PATH
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

            setOf(Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS) shouldContain world.getBlockAt(2, 64, 0).type
            setOf(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE) shouldContain world.getBlockAt(2, 64, 1).type
            plugin.inspectTrail(world.getBlockAt(2, 64, 0)) shouldBe TrailBlockState(null, 0)
        }

        "safe-solid roads repaint ordinary stone but preserve special and valuable blocks" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("SafeRoadBuilder")
            admin.isOp = true
            for (x in -1..5) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.getBlockAt(1, 64, 0).type = Material.BEDROCK
            world.getBlockAt(2, 64, 0).type = Material.DIAMOND_ORE
            world.getBlockAt(3, 64, 0).type = Material.DIAMOND_BLOCK
            world.getBlockAt(4, 64, 0).type = Material.CHEST
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

            plugin.roadStart(admin, "footpath").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 5.5, 65.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(0, 64, 0).type shouldBe Material.DIRT_PATH
            world.getBlockAt(1, 64, 0).type shouldBe Material.BEDROCK
            world.getBlockAt(2, 64, 0).type shouldBe Material.DIAMOND_ORE
            world.getBlockAt(3, 64, 0).type shouldBe Material.DIAMOND_BLOCK
            world.getBlockAt(4, 64, 0).type shouldBe Material.CHEST
            world.getBlockAt(5, 64, 0).type shouldBe Material.DIRT_PATH
        }

        "height transitions keep the stair back against a full road block on the higher side" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("StairRoadBuilder")
            admin.isOp = true
            for (z in -1..1) {
                world.getBlockAt(0, 64, z).type = Material.STONE
                for (x in 1..3) {
                    world.getBlockAt(x, 64, z).type = Material.STONE
                    world.getBlockAt(x, 65, z).type = Material.STONE
                }
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
            plugin.captureRoadMovement(admin, Location(world, 3.5, 66.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            val ascending = world.getBlockAt(1, 65, 0).blockData as Stairs
            setOf(Material.OAK_STAIRS, Material.SPRUCE_STAIRS) shouldContain ascending.material
            ascending.facing shouldBe BlockFace.WEST
            ascending.facing.oppositeFace shouldBe BlockFace.EAST
            (world.getBlockAt(2, 65, 0).blockData is Stairs) shouldBe false
            setOf(Material.OAK_PLANKS, Material.SPRUCE_PLANKS) shouldContain world.getBlockAt(2, 65, 0).type

            val wrongDirection = Material.OAK_STAIRS.createBlockData() as Stairs
            wrongDirection.facing = BlockFace.NORTH
            world.getBlockAt(1, 65, 0).blockData = wrongDirection
            admin.teleport(Location(world, 3.5, 66.0, 0.5))
            plugin.roadStart(admin, "boardwalk").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 0.5, 65.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            val descending = world.getBlockAt(1, 65, 0).blockData as Stairs
            descending.facing shouldBe BlockFace.WEST
            descending.facing.oppositeFace shouldBe BlockFace.EAST
            setOf(Material.OAK_PLANKS, Material.SPRUCE_PLANKS) shouldContain world.getBlockAt(2, 65, 0).type
        }

        "height transitions support profile-specific bottom slabs" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("SlabRoadBuilder")
            admin.isOp = true
            world.getBlockAt(0, 64, 0).type = Material.STONE
            world.getBlockAt(1, 65, 0).type = Material.STONE
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

            plugin.roadStart(admin, "footpath").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 1.5, 66.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            val slab = world.getBlockAt(1, 65, 0).blockData as Slab
            setOf(Material.OAK_SLAB, Material.SPRUCE_SLAB) shouldContain slab.material
            slab.type shouldBe Slab.Type.BOTTOM
        }

        "road smoothing removes obsolete edges while keeping captured points inside the road" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("SmoothRoadBuilder")
            admin.isOp = true
            for (x in -2..6) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 1.5))
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            plugin.captureRoadMovement(admin, Location(world, 3.5, 65.0, 1.5))
            plugin.captureRoadMovement(admin, Location(world, 4.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            setOf(Material.DIRT_PATH, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL) shouldContain
                world.getBlockAt(1, 64, 1).type
            world.getBlockAt(-1, 64, 0).type shouldBe Material.STONE
        }

        "turning the route does not retain an obsolete cross-section" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("TurningRoadBuilder")
            admin.isOp = true
            for (x in -2..5) {
                for (z in -2..4) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("    tolerance-blocks: 1.0", "    tolerance-blocks: 0.0"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 2.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(3, 64, 0).type shouldBe Material.STONE
            setOf(Material.DIRT_PATH, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL) shouldContain
                world.getBlockAt(2, 64, 1).type
        }

        "side-slope changes do not create stairs while the road center stays level" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("LevelRoadBuilder")
            admin.isOp = true
            for (x in 0..1) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.getBlockAt(1, 65, 1).type = Material.STONE
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            (world.getBlockAt(1, 65, 1).blockData is Stairs) shouldBe false
        }

        "sharp cross-slopes do not create detached outer road strips" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("CrossSlopeRoadBuilder")
            admin.isOp = true
            for (x in 0..1) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            for (y in 65..67) world.getBlockAt(1, y, 1).type = Material.STONE
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "rustic")
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(1, 67, 1).type shouldBe Material.STONE
        }

        "periodic road forms rotate with the route and alternate lantern sides" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("LanternRoadBuilder")
            admin.isOp = true
            for (x in -1..24) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(1, 0)
            world.loadChunk(0, -1)
            world.loadChunk(1, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "lantern_lane").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 24.5, 65.0, 0.5)) shouldBe true
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            setOf(Material.COBBLESTONE_WALL, Material.MOSSY_COBBLESTONE_WALL) shouldContain world.getBlockAt(12, 65, 2).type
            world.getBlockAt(12, 66, 2).type shouldBe Material.OAK_FENCE
            world.getBlockAt(12, 67, 2).type shouldBe Material.LANTERN
            setOf(Material.COBBLESTONE_WALL, Material.MOSSY_COBBLESTONE_WALL) shouldContain world.getBlockAt(24, 65, -2).type
            world.getBlockAt(24, 66, -2).type shouldBe Material.OAK_FENCE
            world.getBlockAt(24, 67, -2).type shouldBe Material.LANTERN
        }

        "periodic road forms rotate lateral offsets on a southbound route" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("RotatedLanternRoadBuilder")
            admin.isOp = true
            for (x in -1..1) {
                for (z in 0..12) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(-1, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "lantern_lane")
            plugin.captureRoadMovement(admin, Location(world, 0.5, 65.0, 12.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            setOf(Material.COBBLESTONE_WALL, Material.MOSSY_COBBLESTONE_WALL) shouldContain world.getBlockAt(-2, 65, 12).type
            world.getBlockAt(-2, 66, 12).type shouldBe Material.OAK_FENCE
            world.getBlockAt(-2, 67, 12).type shouldBe Material.LANTERN
        }

        "periodic road forms skip the whole structure when any target is occupied" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("BlockedLanternRoadBuilder")
            admin.isOp = true
            for (x in -1..12) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            world.getBlockAt(12, 65, 2).type = Material.CHEST
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "lantern_lane")
            plugin.captureRoadMovement(admin, Location(world, 12.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(12, 65, 2).type shouldBe Material.CHEST
            world.getBlockAt(12, 66, 2).type shouldBe Material.AIR
            world.getBlockAt(12, 67, 2).type shouldBe Material.AIR
        }

        "periodic road forms skip a structure intersecting a later road section" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("LoopingLanternRoadBuilder")
            admin.isOp = true
            for (x in -2..14) {
                for (z in -2..4) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "lantern_lane")
            plugin.captureRoadMovement(admin, Location(world, 12.5, 65.0, 0.5))
            plugin.captureRoadMovement(admin, Location(world, 12.5, 65.0, 2.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            world.getBlockAt(12, 65, 2).type shouldBe Material.AIR
            world.getBlockAt(12, 66, 2).type shouldBe Material.AIR
            world.getBlockAt(12, 67, 2).type shouldBe Material.AIR
        }

        "periodic road forms are not partially planned at the preview limit" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("CappedLanternRoadBuilder")
            admin.isOp = true
            for (x in -1..12) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type = Material.STONE
            }
            world.loadChunk(0, 0)
            world.loadChunk(0, -1)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  max-planned-blocks: 2048", "  max-planned-blocks: 41"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "lantern_lane")
            plugin.captureRoadMovement(admin, Location(world, 12.5, 65.0, 0.5))
            plugin.roadStatus(admin).replacements["%count%"] shouldBe "39"
            plugin.roadCommit(admin).message shouldBe "messages.roadCommittedCapped"

            world.getBlockAt(12, 65, 2).type shouldBe Material.AIR
            world.getBlockAt(12, 66, 2).type shouldBe Material.AIR
            world.getBlockAt(12, 67, 2).type shouldBe Material.AIR
        }

        "survival road commits return ordinary removed blocks and disable duplicating undo" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("CompensatedRoadBuilder")
            admin.isOp = true
            admin.gameMode = GameMode.SURVIVAL
            for (x in 0..2) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  return-to-survival-inventory: false", "  return-to-survival-inventory: true"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "footpath")
            plugin.captureRoadMovement(admin, Location(world, 2.5, 65.0, 0.5))
            val result = plugin.roadCommit(admin)

            result.message shouldBe "messages.roadCommitted"
            result.notices.map(RoadNotice::message) shouldContain "messages.roadBlocksReturned"
            admin.inventory.contents.filterNotNull().filter { it.type == Material.STONE }.sumOf(ItemStack::getAmount) shouldBe 3
            plugin.roadUndo(admin).message shouldBe "messages.roadNothingToUndo"
        }

        "survival road commits require the exact collection permission before returning blocks" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val builder = server.addPlayer("UnprivilegedRoadBuilder")
            builder.gameMode = GameMode.SURVIVAL
            for (x in 0..1) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.loadChunk(0, 0)
            builder.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  return-to-survival-inventory: false", "  return-to-survival-inventory: true"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(builder, "footpath")
            plugin.captureRoadMovement(builder, Location(world, 1.5, 65.0, 0.5))
            plugin.roadCommit(builder).message shouldBe "messages.roadCommitted"

            builder.inventory.contents.filterNotNull().filter { it.type == Material.STONE }.sumOf(ItemStack::getAmount) shouldBe 0
            plugin.roadUndo(builder).message shouldBe "messages.roadUndone"
        }

        "creative road commits keep undo and do not return removed blocks" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("CreativeRoadBuilder")
            admin.isOp = true
            admin.gameMode = GameMode.CREATIVE
            for (x in 0..1) world.getBlockAt(x, 64, 0).type = Material.STONE
            world.loadChunk(0, 0)
            admin.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  return-to-survival-inventory: false", "  return-to-survival-inventory: true"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "footpath")
            plugin.captureRoadMovement(admin, Location(world, 1.5, 65.0, 0.5))
            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"

            admin.inventory.contents.filterNotNull().filter { it.type == Material.STONE }.sumOf(ItemStack::getAmount) shouldBe 0
            plugin.roadUndo(admin).message shouldBe "messages.roadUndone"
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

            setOf(Material.OAK_PLANKS, Material.SPRUCE_PLANKS) shouldContain world.getBlockAt(2, 64, 0).type
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
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  max-segment-distance-blocks: 48", "  max-segment-distance-blocks: 16"),
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

        "roads include the landing point after movement beyond the segment limit" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("LandingRoadBuilder")
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
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  max-segment-distance-blocks: 48", "  max-segment-distance-blocks: 16"),
            )
            plugin.reloadTrails().isSuccess shouldBe true

            plugin.roadStart(admin, "footpath").message shouldBe "messages.roadStarted"
            plugin.captureRoadMovement(admin, Location(world, 20.5, 65.0, 0.5)) shouldBe true

            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"
            world.getBlockAt(10, 64, 0).type shouldBe Material.GRASS_BLOCK
            world.getBlockAt(20, 64, 0).type shouldBe Material.DIRT_PATH
        }

        "roads capture movement while the builder is flying" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val admin = server.addPlayer("FlyingRoadBuilder")
            admin.isOp = true
            for (x in -2..4) world.getBlockAt(x, 64, 0).type = Material.GRASS_BLOCK
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
            plugin.roadStart(admin, "footpath").message shouldBe "messages.roadStarted"
            admin.allowFlight = true
            admin.isFlying = true

            server.pluginManager.callEvent(
                PlayerMoveEvent(
                    admin,
                    Location(world, 0.5, 65.0, 0.5),
                    Location(world, 3.5, 65.0, 0.5),
                ),
            )

            plugin.roadCommit(admin).message shouldBe "messages.roadCommitted"
            world.getBlockAt(2, 64, 0).type shouldBe Material.DIRT_PATH
        }

        "roads commit is all-or-nothing for a regular builder when a protection event vetoes one block" {
            val world = server.addSimpleWorld("arc_qa_flat")
            val builder = server.addPlayer("ProtectedRoadBuilder")
            builder.addAttachment(plugin, "trails.roads.manage", true)
            for (x in -2..3) {
                for (z in -2..2) world.getBlockAt(x, 64, z).type = Material.GRASS_BLOCK
            }
            world.loadChunk(0, 0)
            builder.teleport(Location(world, 0.5, 65.0, 0.5))
            val roadsPath = plugin.dataFolder.toPath().resolve("roads.yml")
            Files.writeString(
                roadsPath,
                Files.readString(roadsPath)
                    .replace("enabled: false", "enabled: true")
                    .replace("worlds: []", "worlds: [arc_qa_flat]")
                    .replace("  return-to-survival-inventory: false", "  return-to-survival-inventory: true"),
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

            server.dispatchCommand(builder, "trails road start rustic") shouldBe true
            plugin.captureRoadMovement(builder, Location(world, 1.5, 65.0, 0.5)) shouldBe true
            server.dispatchCommand(builder, "trails road commit") shouldBe true

            for (x in 0..1) {
                for (z in -1..1) world.getBlockAt(x, 64, z).type shouldBe Material.GRASS_BLOCK
            }
            builder.inventory.contents.filterNotNull().size shouldBe 0
        }
    })
