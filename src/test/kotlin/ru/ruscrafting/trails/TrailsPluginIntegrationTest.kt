package ru.ruscrafting.trails

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
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
            plugin.inspectTrail(block) shouldBe null
        }

        "status and validate commands expose the active v2 configuration" {
            val admin = server.addPlayer("StatusAdmin")
            admin.isOp = true

            server.dispatchCommand(admin, "trails status") shouldBe true
            server.dispatchCommand(admin, "trails validate") shouldBe true
        }
    })
