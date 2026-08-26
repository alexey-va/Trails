package ru.ruscrafting.trails.service

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.domain.TrailDefinition
import ru.ruscrafting.trails.domain.TrailIdentity
import ru.ruscrafting.trails.domain.TrailStage
import ru.ruscrafting.trails.storage.TrailBlockState
import ru.ruscrafting.trails.storage.TrailBlockStore

class TrailServiceTest :
    FreeSpec({
        "counts walks and advances a block while preserving its trail identity" {
            val block = mockk<Block>(relaxed = true)
            val before = mockk<BlockState>()
            val after = mockk<BlockState>()
            every { block.type } returns Material.GRASS_BLOCK
            every { block.state } returnsMany listOf(before, after)
            val player = mockk<Player>()
            every { player.name } returns "Alexey"
            every { player.isSprinting } returns false
            val store = InMemoryTrailBlockStore()
            val changes = mutableListOf<Triple<String, BlockState, BlockState>>()
            val service = service(store, BlockChangeObserver { actor, old, new -> changes += Triple(actor, old, new) })

            service.walk(player, block, sprintModifier = 1.5) shouldBe true
            store.read(block) shouldBe TrailBlockState(TrailIdentity("DirtPath", 0), 1)
            service.walk(player, block, sprintModifier = 1.5) shouldBe true

            verify(exactly = 1) { block.setType(Material.DIRT, false) }
            store.read(block) shouldBe TrailBlockState(TrailIdentity("DirtPath", 1), 0)
            changes shouldBe listOf(Triple("Alexey", before, after))
        }

        "decay regresses to the previous material and first-stage representation" {
            val block = mockk<Block>(relaxed = true)
            every { block.type } returns Material.DIRT
            every { block.state } returns mockk()
            val store = InMemoryTrailBlockStore()
            store.write(block, TrailBlockState(TrailIdentity("DirtPath", 1), 0))
            val service = service(store)

            service.decay(block, fraction = 0.1) shouldBe true

            verify(exactly = 1) { block.setType(Material.GRASS_BLOCK, false) }
            store.read(block) shouldBe TrailBlockState(identity = null, walks = 1)
        }

        "a denied transition leaves the block and stored stage unchanged" {
            val block = mockk<Block>(relaxed = true)
            every { block.type } returns Material.GRASS_BLOCK
            val player = mockk<Player>()
            every { player.name } returns "ProtectedWalker"
            every { player.isSprinting } returns false
            val store = InMemoryTrailBlockStore()
            val service = service(store)

            service.walk(player, block, sprintModifier = 1.5) shouldBe true
            service.walk(player, block, sprintModifier = 1.5) { target ->
                target shouldBe Material.DIRT
                false
            } shouldBe false

            verify(exactly = 0) { block.setType(any<Material>(), any<Boolean>()) }
            store.read(block) shouldBe TrailBlockState(TrailIdentity("DirtPath", 0), 1)
        }

        "only-trails boost ignores matching natural materials" {
            val block = mockk<Block>()
            every { block.type } returns Material.GRASS_BLOCK
            val store = InMemoryTrailBlockStore()
            val service = service(store)

            service.speedMultiplier(block, onlyTrackedTrails = true) shouldBe 1.0
            service.speedMultiplier(block, onlyTrackedTrails = false) shouldBe 1.1
            store.write(block, TrailBlockState(TrailIdentity("DirtPath", 0), 1))
            service.speedMultiplier(block, onlyTrackedTrails = true) shouldBe 1.1
        }

        "one movement context reuses a single persistent read" {
            val block = mockk<Block>(relaxed = true)
            every { block.type } returns Material.GRASS_BLOCK
            every { block.state } returns mockk()
            val player = mockk<Player>()
            every { player.name } returns "CachedWalker"
            every { player.isSprinting } returns false
            val store = InMemoryTrailBlockStore()
            val service = service(store)

            val context = service.movementContext(block)
            service.speedMultiplier(context, onlyTrackedTrails = false) shouldBe 1.1
            service.canAffect(context) shouldBe true
            service.walk(player, context, sprintModifier = 1.5) shouldBe true

            store.readCount shouldBe 1
        }

        "piston movement preserves adjacent trail states without overwriting them" {
            val first = mockk<Block>()
            val second = mockk<Block>()
            val third = mockk<Block>()
            every { first.getRelative(BlockFace.EAST) } returns second
            every { second.getRelative(BlockFace.EAST) } returns third
            val store = InMemoryTrailBlockStore()
            val firstState = TrailBlockState(TrailIdentity("DirtPath", 0), 1)
            val secondState = TrailBlockState(TrailIdentity("DirtPath", 1), 2)
            store.write(first, firstState)
            store.write(second, secondState)
            val service = service(store)

            service.move(listOf(first, second), BlockFace.EAST)

            store.read(first) shouldBe null
            store.read(second) shouldBe firstState
            store.read(third) shouldBe secondState
        }

        "piston movement clears stale state at a replaced destination" {
            val source = mockk<Block>()
            val destination = mockk<Block>()
            every { source.getRelative(BlockFace.EAST) } returns destination
            val store = InMemoryTrailBlockStore()
            store.write(destination, TrailBlockState(TrailIdentity("DirtPath", 1), 2))
            val service = service(store)

            service.move(listOf(source), BlockFace.EAST)

            store.read(destination) shouldBe null
        }
    }) {
    companion object {
        private fun service(
            store: TrailBlockStore,
            observer: BlockChangeObserver = BlockChangeObserver.NONE,
        ): TrailService {
            val definition =
                TrailDefinition(
                    "DirtPath",
                    listOf(
                        TrailStage("DirtPath", 0, "GRASS_BLOCK", 2, 100.0, 1.1),
                        TrailStage("DirtPath", 1, "DIRT", 3, 100.0, 1.2),
                    ),
                )
            return TrailService(TrailCatalog(listOf(definition), strictLinks = false), store, observer) { 0.0 }
        }
    }
}

private class InMemoryTrailBlockStore : TrailBlockStore {
    private val values = mutableMapOf<Block, TrailBlockState>()
    var readCount = 0
        private set

    override fun read(block: Block): TrailBlockState? {
        readCount++
        return values[block]
    }

    override fun write(block: Block, state: TrailBlockState) {
        values[block] = state
    }

    override fun clear(block: Block) {
        values.remove(block)
    }

    override fun trackedBlocks(chunk: Chunk): Collection<Block> = emptyList()
}
