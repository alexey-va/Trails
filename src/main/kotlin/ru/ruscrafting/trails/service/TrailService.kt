package ru.ruscrafting.trails.service

import org.bukkit.Material
import org.bukkit.Chunk
import org.bukkit.block.Block
import org.bukkit.entity.Player
import ru.ruscrafting.trails.domain.DecayDecision
import ru.ruscrafting.trails.domain.ProgressDecision
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.domain.TrailProgressEngine
import ru.ruscrafting.trails.storage.TrailBlockState
import ru.ruscrafting.trails.storage.TrailBlockStore
import kotlin.random.Random

class TrailService(
    private val catalog: TrailCatalog,
    private val store: TrailBlockStore,
    private val observer: BlockChangeObserver = BlockChangeObserver.NONE,
    private val randomPercent: () -> Double = { Random.nextDouble(0.0, 100.0) },
) {
    private val progress = TrailProgressEngine(catalog)

    fun walk(
        player: Player,
        block: Block,
        sprintModifier: Double,
        forced: Boolean = false,
    ): Boolean {
        val stored = store.read(block)
        val stage = catalog.resolve(block.type.name, stored?.identity) ?: return false
        return when (
            val decision =
                progress.walk(
                    stage = stage,
                    currentWalks = stored?.walks ?: 0,
                    randomPercent = randomPercent(),
                    sprintModifier = if (player.isSprinting) sprintModifier else 1.0,
                    forced = forced,
                )
        ) {
            ProgressDecision.NoChange -> false
            is ProgressDecision.Counted -> {
                store.write(block, TrailBlockState(decision.stage.identity, decision.walks))
                true
            }
            is ProgressDecision.Advanced -> {
                changeMaterial(player.name, block, Material.valueOf(decision.to.material))
                store.write(block, TrailBlockState(decision.to.identity, 0))
                true
            }
        }
    }

    fun decay(block: Block, fraction: Double): Boolean {
        val stored = store.read(block) ?: return false
        val stage = catalog.resolve(block.type.name, stored.identity) ?: return false
        return when (val decision = progress.decay(stage, stored.walks, fraction)) {
            DecayDecision.NoChange -> false
            DecayDecision.Cleared -> {
                store.clear(block)
                true
            }
            is DecayDecision.CountedDown -> {
                store.write(block, TrailBlockState(decision.stage.identity, decision.walks))
                true
            }
            is DecayDecision.Regressed -> {
                changeMaterial("NaturalTrailDecay", block, Material.valueOf(decision.to.material))
                store.write(
                    block,
                    TrailBlockState(
                        identity = decision.to.identity.takeUnless { decision.to.index == 0 },
                        walks = decision.walks,
                    ),
                )
                true
            }
        }
    }

    fun speedMultiplier(block: Block, onlyTrackedTrails: Boolean): Double {
        val stored = store.read(block)
        if (onlyTrackedTrails && stored == null) return 1.0
        return catalog.resolve(block.type.name, stored?.identity)?.speedMultiplier ?: 1.0
    }

    fun canAffect(block: Block): Boolean {
        val stored = store.read(block)
        return catalog.resolve(block.type.name, stored?.identity) != null
    }

    fun trackedBlocks(chunk: Chunk): Collection<Block> = store.trackedBlocks(chunk)

    fun inspect(block: Block): TrailBlockState? = store.read(block)

    fun clear(block: Block) = store.clear(block)

    private fun changeMaterial(actor: String, block: Block, material: Material) {
        val before = block.state
        block.setType(material, false)
        val after = block.state
        observer.changed(actor, before, after)
    }
}
