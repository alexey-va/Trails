package ru.ruscrafting.trails.service

import org.bukkit.block.BlockState

fun interface BlockChangeObserver {
    fun changed(actor: String, before: BlockState, after: BlockState)

    companion object {
        val NONE = BlockChangeObserver { _, _, _ -> }

        fun composite(observers: Collection<BlockChangeObserver>): BlockChangeObserver =
            BlockChangeObserver { actor, before, after ->
                observers.forEach { observer -> observer.changed(actor, before, after) }
            }
    }
}
