package ru.ruscrafting.trails

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Biome
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.player.PlayerMoveEvent
import ru.arc.paper.runtime.PaperNativeCleanupServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import ru.ruscrafting.trails.api.TrailsFootTrafficActor
import ru.ruscrafting.trails.api.TrailsFootTrafficService.StepResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class TrailsFootTrafficIntegrationTest : FunSpec({
    test("registered road soil receives its configured speed immediately without changing blocks or wear counters") {
        trafficScenario { h ->
            h.ground.type = Material.COARSE_DIRT
            val original = h.ground.blockData.asString
            val speed = h.authority.walkSpeed
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe true
            h.ground.blockData.asString shouldBe original
            h.service.speedMultiplier(h.ground) shouldBe 1.2
            h.plugin.inspectTrail(h.ground)?.walks shouldBe 0
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe true
            h.plugin.inspectTrail(h.ground)?.walks shouldBe 0
            h.authority.walkSpeed shouldBe speed
        }
    }

    test("surface registration preserves a worn road counter and ignores unsupported blocks or disabled worlds") {
        trafficScenario { h ->
            h.virtualStep().awaitTrails(h.server) shouldBe StepResult.COUNTED
            val before = h.plugin.inspectTrail(h.ground)
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe true
            h.plugin.inspectTrail(h.ground) shouldBe before
            h.ground.type = Material.CHEST
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe false
            h.ground.type shouldBe Material.CHEST
            h.plugin.config.set("worlds.mode", "blocklist")
            h.plugin.config.set("worlds.names", listOf(h.world.name))
            h.plugin.saveConfig(); h.plugin.reloadTrails().awaitTrails(h.server)
            h.ground.type = Material.COARSE_DIRT
            h.service.registerSurface(h.ground).toCompletableFuture().join() shouldBe false
        }
    }

    test("registered dry bottom road stairs inherit base material speed without changing their native state") {
        trafficScenario { h ->
            h.ground.type = Material.COBBLESTONE
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe true
            val baseSpeed = h.service.speedMultiplier(h.ground)
            (baseSpeed > 1.0) shouldBe true
            h.ground.type = Material.COBBLESTONE_STAIRS
            val stairs = h.ground.blockData as org.bukkit.block.data.type.Stairs
            stairs.half = Bisected.Half.BOTTOM
            stairs.isWaterlogged = false
            h.ground.blockData = stairs
            val original = stairs.asString
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe true
            h.service.speedMultiplier(h.ground) shouldBe baseSpeed
            h.ground.blockData.asString shouldBe original
            stairs.isWaterlogged = true
            h.ground.blockData = stairs
            h.service.registerSurface(h.ground).toCompletableFuture().awaitTrails(h.server) shouldBe false
            h.service.speedMultiplier(h.ground) shouldBe 1.0
        }
    }

    for (route in listOf("player", "npc")) for (plant in listOf(Material.SHORT_GRASS, Material.FERN, Material.TALL_GRASS, Material.LARGE_FERN)) {
        test("$route wear clears $plant completely only after the ground transition and gives no drops") {
            trafficScenario { h ->
                val lower = h.ground.getRelative(BlockFace.UP)
                val upper = lower.getRelative(BlockFace.UP)
                lower.type = plant
                val tall = plant == Material.TALL_GRASS || plant == Material.LARGE_FERN
                if (tall) {
                    setTallPlantHalf(lower, plant, Bisected.Half.BOTTOM)
                    setTallPlantHalf(upper, plant, Bisected.Half.TOP)
                }
                val entities = h.world.entities.map { it.uniqueId }.toSet()
                val checked = mutableListOf<Int>()
                h.server.pluginManager.registerEvent(EntityChangeBlockEvent::class.java, object : Listener {}, EventPriority.NORMAL,
                    { _, raw ->
                        val event = raw as EntityChangeBlockEvent
                        if (event.to == Material.AIR) {
                            event.entity shouldBe h.authority
                            h.ground.type shouldBe Material.GRASS_BLOCK
                            checked += event.block.y
                        }
                    }, h.plugin)
                h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
                repeat(4) {
                    h.wear(route)
                    h.ground.type shouldBe Material.GRASS_BLOCK
                    lower.type shouldBe plant
                    if (tall) upper.type shouldBe plant
                }
                checked shouldBe emptyList()
                h.wear(route)
                h.ground.type shouldBe Material.DIRT
                lower.type shouldBe Material.AIR
                upper.type shouldBe Material.AIR
                checked.toSet() shouldBe if (tall) setOf(65, 66) else setOf(65)
                h.world.entities.map { it.uniqueId }.toSet() shouldBe entities
                h.plugin.inspectTrail(h.ground)?.identity?.serialize() shouldBe "DirtPath:1"
            }
        }
    }

    for (route in listOf("player", "npc")) for (veto in listOf("entity", "canBuild")) {
        test("$route wear preserves the ground and both tall grass halves when the upper $veto probe is denied") {
            trafficScenario { h ->
                val lower = h.ground.getRelative(BlockFace.UP)
                val upper = lower.getRelative(BlockFace.UP)
                setTallPlantHalf(lower, Material.TALL_GRASS, Bisected.Half.BOTTOM)
                setTallPlantHalf(upper, Material.TALL_GRASS, Bisected.Half.TOP)
                h.server.pluginManager.registerEvent(EntityChangeBlockEvent::class.java, object : Listener {}, EventPriority.NORMAL,
                    { _, raw ->
                        val event = raw as EntityChangeBlockEvent
                        if (veto == "entity" && event.block == upper && event.to == Material.AIR) event.isCancelled = true
                    }, h.plugin)
                h.server.pluginManager.registerEvent(BlockPlaceEvent::class.java, object : Listener {}, EventPriority.NORMAL,
                    { _, raw ->
                        val event = raw as BlockPlaceEvent
                        if (veto == "canBuild" && event.block == upper) event.setBuild(false)
                    }, h.plugin)
                h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
                repeat(5) { h.wear(route) }
                h.ground.type shouldBe Material.GRASS_BLOCK
                lower.type shouldBe Material.TALL_GRASS
                upper.type shouldBe Material.TALL_GRASS
                (lower.blockData as Bisected).half shouldBe Bisected.Half.BOTTOM
                (upper.blockData as Bisected).half shouldBe Bisected.Half.TOP
                h.plugin.inspectTrail(h.ground)?.walks shouldBe 4
            }
        }
    }

    for (plant in listOf(Material.WHEAT, Material.POPPY)) test("natural trail wear preserves $plant rather than applying the broader road clearance list") {
        trafficScenario { h ->
            val above = h.ground.getRelative(BlockFace.UP).also { it.type = plant }
            h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
            repeat(5) { h.wear("player") }
            h.ground.type shouldBe Material.DIRT
            above.type shouldBe plant
        }
    }

    test("the advance tool shares vegetation cleanup and keeps an unmatched tall plant half") {
        trafficScenario { h ->
            val above = h.ground.getRelative(BlockFace.UP).also { it.type = Material.SHORT_GRASS }
            h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
            h.plugin.forceTrail(h.authority, h.ground)
            h.ground.type shouldBe Material.DIRT
            above.type shouldBe Material.AIR
            setTallPlantHalf(above, Material.TALL_GRASS, Bisected.Half.BOTTOM)
            val flower = above.getRelative(BlockFace.UP).also { it.type = Material.POPPY }
            h.plugin.forceTrail(h.authority, h.ground)
            h.ground.type shouldBe Material.COARSE_DIRT
            above.type shouldBe Material.TALL_GRASS
            flower.type shouldBe Material.POPPY
            h.plugin.forceTrail(h.authority, h.ground)
            h.ground.type shouldBe Material.DIRT_PATH
            above.type shouldBe Material.TALL_GRASS
            flower.type shouldBe Material.POPPY
        }
    }

    test("packet-only actors use ordinary progression without spawning a native entity") {
        trafficScenario { h ->
            val entities = h.world.entities.map { it.uniqueId }.toSet()
            val results = (1..5).flatMap { listOf(h.virtualStep(), h.virtualStep(reverse = true)) }
            CompletableFuture.allOf(*results.toTypedArray()).awaitTrails(h.server)
            h.ground.type shouldBe Material.DIRT
            results.count { it.join() == StepResult.CHANGED } shouldBe 2
            h.world.entities.map { it.uniqueId }.toSet() shouldBe entities
        }
    }

    for (exit in listOf("retired", "world", "authority", "foreign-edit")) {
        test("packet-only cold steps are fenced after $exit") {
            trafficScenario { h ->
                val pending = h.virtualStep()
                pending.isDone shouldBe false
                when (exit) {
                    "retired" -> h.valid = false
                    "world" -> h.position.world = h.server.addSimpleWorld("elsewhere")
                    "authority" -> h.authority.disconnect()
                    "foreign-edit" -> h.ground.type = Material.STONE
                }
                pending.awaitTrails(h.server) shouldBe StepResult.IGNORED
                h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
                h.plugin.inspectTrail(h.ground) shouldBe null
            }
        }
    }

    test("packet-only movement rejects teleport samples and honors the real manager protection veto") {
        trafficScenario { h ->
            h.position = h.to().add(10.0, 0.0, 0.0)
            h.service.recordStep(h.virtualActor, h.authority, h.from(), h.position)
                .toCompletableFuture().join() shouldBe StepResult.IGNORED
            h.server.pluginManager.registerEvent(EntityChangeBlockEvent::class.java, object : Listener {},
                EventPriority.NORMAL, { _, raw ->
                    val event = raw as EntityChangeBlockEvent
                    event.entity shouldBe h.authority
                    event.isCancelled = true
                }, h.plugin)
            repeat(4) { h.virtualStep().awaitTrails(h.server) shouldBe StepResult.COUNTED }
            h.virtualStep().awaitTrails(h.server) shouldBe StepResult.NO_CHANGE
            h.ground.type shouldBe Material.GRASS_BLOCK
        }
    }

    test("the typed service records every cold NPC step with normal weight and no personal settings or boost") {
        trafficScenario { h ->
            h.plugin.config.set("trail-creation.sprint-progress-multiplier", 0.0)
            h.plugin.saveConfig(); h.plugin.reloadTrails().awaitTrails(h.server).isSuccess shouldBe true
            h.authority.isSprinting = true
            h.server.dispatchCommand(h.authority, "trails off")
            h.server.dispatchCommand(h.authority, "trails boost off")
            val speed = h.authority.walkSpeed
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            val results = (1..5).flatMap { listOf(h.step(), h.step(reverse = true)) }
            CompletableFuture.allOf(*results.toTypedArray()).awaitTrails(h.server)
            h.ground.type shouldBe Material.DIRT
            (h.service.speedMultiplier(h.ground) > 1.0) shouldBe true
            h.plugin.inspectTrail(h.ground)?.identity?.serialize() shouldBe "DirtPath:1"
            results.count { it.join() == StepResult.CHANGED } shouldBe 2
            h.authority.walkSpeed shouldBe speed
            h.plugin.trailsEnabled(h.authority.uniqueId) shouldBe false
            h.plugin.boostEnabled(h.authority.uniqueId) shouldBe false
            h.npc.isOnline shouldBe false // Citizens' fake Player is not a connected client.
        }
    }

    test("non NPC actors stationary samples teleports mismatched positions and unloaded chunks do not count") {
        trafficScenario { h ->
            h.tagged = false
            h.step().join() shouldBe StepResult.IGNORED
            h.tagged = true
            val from = h.from()
            for (to in listOf(from.clone(), from.clone().add(0.0, 1.0, 0.0), from.clone().add(3.0, 0.0, 0.0),
                from.clone().apply { x = Double.NaN })) {
                h.position = to
                h.service.recordStep(h.npc, h.authority, from, to).toCompletableFuture().join() shouldBe StepResult.IGNORED
            }
            h.position = h.to().add(0.3, 0.0, 0.0)
            h.service.recordStep(h.npc, h.authority, from, h.to()).toCompletableFuture().join() shouldBe StepResult.IGNORED
            val distantFrom = from.clone().add(1_600.0, 0.0, 0.0)
            val distantTo = h.to().add(1_600.0, 0.0, 0.0)
            h.position = distantTo
            h.world.isChunkLoaded(100, 0) shouldBe false
            h.service.recordStep(h.npc, h.authority, distantFrom, distantTo).toCompletableFuture().join() shouldBe StepResult.IGNORED
            h.world.isChunkLoaded(100, 0) shouldBe false
            h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
            h.plugin.inspectTrail(h.ground) shouldBe null
        }
    }

    test("NPC traffic enforces the global world and authority permission gates and the owning thread") {
        trafficScenario { h ->
            h.plugin.config.set("trail-creation.require-permission", true)
            h.plugin.saveConfig(); h.plugin.reloadTrails().awaitTrails(h.server)
            h.step().awaitTrails(h.server) shouldBe StepResult.IGNORED
            h.authority.addAttachment(h.plugin, "trails.create-trails", true)
            h.step().awaitTrails(h.server) shouldBe StepResult.COUNTED
            h.plugin.config.set("worlds.mode", "blocklist")
            h.plugin.config.set("worlds.names", listOf(h.world.name))
            h.plugin.saveConfig(); h.plugin.reloadTrails().awaitTrails(h.server)
            h.step().awaitTrails(h.server) shouldBe StepResult.IGNORED
            h.service.speedMultiplier(h.ground) shouldBe 1.0
            val invalidThread = CompletableFuture.supplyAsync { h.service.recordStep(h.npc, h.authority, h.from(), h.to()) }
            shouldThrow<CompletionException> { invalidThread.join() }.cause!!::class shouldBe IllegalStateException::class
            h.plugin.inspectTrail(h.ground)?.walks shouldBe 1
        }
    }

    for (veto in listOf("entity", "canBuild")) test("the ordinary $veto protection probe uses the online manager and retains the last counter") {
        trafficScenario { h ->
            h.plugin.config.set("messages.protection-denied.enabled", true)
            h.plugin.saveConfig(); h.plugin.reloadTrails().awaitTrails(h.server)
            var transitions = 0
            h.server.pluginManager.registerEvent(EntityChangeBlockEvent::class.java, object : Listener {}, EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as EntityChangeBlockEvent
                    event.entity shouldBe h.authority
                    transitions++
                    if (veto == "entity") event.isCancelled = true
                }, h.plugin)
            h.server.pluginManager.registerEvent(BlockPlaceEvent::class.java, object : Listener {}, EventPriority.NORMAL,
                { _, raw ->
                    val event = raw as BlockPlaceEvent
                    event.player shouldBe h.authority
                    if (veto == "canBuild") event.setBuild(false)
                }, h.plugin)
            repeat(4) { h.step().awaitTrails(h.server) shouldBe StepResult.COUNTED }
            transitions shouldBe 0
            h.step().awaitTrails(h.server) shouldBe StepResult.NO_CHANGE
            transitions shouldBe 1
            h.ground.type shouldBe Material.GRASS_BLOCK
            h.plugin.inspectTrail(h.ground)?.walks shouldBe 4
            h.authority.nextMessage() shouldBe null
        }
    }

    for (exit in listOf("actor", "authority", "foreign-edit"))
        test("cold steps are fenced after $exit before any native change") {
            trafficScenario { h ->
                val pending = h.step()
                pending.isDone shouldBe false
                when (exit) {
                    "actor" -> h.valid = false
                    "authority" -> h.authority.teleport(Location(h.server.addSimpleWorld("elsewhere"), 0.5, 65.0, 0.5))
                    "foreign-edit" -> h.ground.type = Material.STONE
                }
                pending.awaitTrails(h.server) shouldBe StepResult.IGNORED
                h.plugin.awaitTrailData(h.ground.chunk).awaitTrails(h.server)
                h.plugin.inspectTrail(h.ground) shouldBe null
            }
        }

    test("disabling the provider retires a pending cold step without changing the world") {
        trafficScenario { h ->
            val pending = h.step()
            pending.isDone shouldBe false
            h.server.pluginManager.disablePlugin(h.plugin)
            pending.handle { result, failure -> result == StepResult.IGNORED || failure != null }.awaitTrails(h.server) shouldBe true
            h.ground.type shouldBe Material.GRASS_BLOCK
            h.server.servicesManager.load(TrailsFootTrafficService::class.java) shouldBe null
        }
    }

    test("API coordinates are copied and native PlayerMoveEvent does not submit a second NPC step") {
        trafficScenario { h ->
            val from = h.from()
            val to = h.to()
            h.position = to.clone()
            val pending = h.service.recordStep(h.npc, h.authority, from, to).toCompletableFuture()
            from.x = 1_600.0; to.x = 1_601.0
            pending.awaitTrails(h.server) shouldBe StepResult.COUNTED
            h.server.pluginManager.callEvent(PlayerMoveEvent(h.npc, h.from(), h.to()))
            h.plugin.inspectTrail(h.ground)?.walks shouldBe 1
            h.server.pluginManager.disablePlugin(h.plugin)
            h.server.servicesManager.load(TrailsFootTrafficService::class.java) shouldBe null
            h.step().join() shouldBe StepResult.IGNORED
        }
    }
})

private fun setTallPlantHalf(block: Block, material: Material, half: Bisected.Half) {
    // MockBukkit's direct setBlockData leaves its stored BlockState stale; update publishes both native views.
    val state = block.state
    state.type = material
    state.blockData = (material.createBlockData() as Bisected).also { it.half = half }
    state.update(true, false) shouldBe true
    block.type shouldBe material
    (block.blockData as Bisected).half shouldBe half
    (block.state.blockData as Bisected).half shouldBe half
}

private fun trafficScenario(block: (FootTrafficScenario) -> Unit) {
    MockBukkitTestRuntime.open().use { runtime ->
        val h = FootTrafficScenario(runtime)
        try { block(h) } finally { h.close() }
    }
}

private class FootTrafficScenario(runtime: MockBukkitTestRuntime) : AutoCloseable {
    val server = runtime.server
    private val cleanup = PaperNativeCleanupServiceProvider(runtime.createSimplePlugin("ArcCore"))
    val plugin = runtime.loadPlugin(TrailsPlugin::class.java).also { awaitTrailsReady(it, runtime) }
    val service = requireNotNull(server.servicesManager.load(TrailsFootTrafficService::class.java))
    val world = server.addSimpleWorld("foot_traffic")
    val authority = server.addPlayer("Manager")
    val npc = mockk<Player>(relaxed = true)
    var position = to()
    var valid = true
    var tagged = true
    val ground = world.getBlockAt(0, 64, 0)
    val virtualActor = object : TrailsFootTrafficActor {
        private val id = UUID.randomUUID()
        override fun uniqueId(): UUID = id
        override fun location(): Location = position.clone()
        override fun isValid(): Boolean = valid
    }

    init {
        world.loadChunk(0, 0)
        for (x in 0..1) world.getBlockAt(x, 64, 0).also { it.type = Material.GRASS_BLOCK; it.biome = Biome.THE_VOID }
        authority.teleport(Location(world, 0.5, 65.0, 0.5))
        every { npc.uniqueId } returns UUID.randomUUID()
        every { npc.world } returns world
        every { npc.location } answers { position.clone() }
        every { npc.isValid } answers { valid }
        every { npc.isDead } returns false
        every { npc.isOnline } returns false
        every { npc.hasMetadata("NPC") } answers { tagged }
    }

    fun from() = Location(world, 0.9, 65.0, 0.5)
    fun to() = Location(world, 1.1, 65.0, 0.5)
    fun step(reverse: Boolean = false): CompletableFuture<StepResult> {
        val from = if (reverse) to() else from()
        val to = if (reverse) from() else to()
        position = to.clone()
        return service.recordStep(npc, authority, from, to).toCompletableFuture()
    }

    fun wear(route: String) {
        if (route == "npc") step().awaitTrails(server) else plugin.handleMovement(authority, ground)
    }

    fun virtualStep(reverse: Boolean = false): CompletableFuture<StepResult> {
        val from = if (reverse) to() else from()
        val to = if (reverse) from() else to()
        position = to.clone()
        return service.recordStep(virtualActor, authority, from, to).toCompletableFuture()
    }

    override fun close() {
        server.pluginManager.disablePlugin(plugin)
        plugin.drained().awaitTrails(server)
        cleanup.close()
    }
}
