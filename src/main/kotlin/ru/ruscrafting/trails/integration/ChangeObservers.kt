package ru.ruscrafting.trails.integration

import net.coreprotect.CoreProtect
import org.bukkit.block.BlockState
import ru.ruscrafting.trails.service.BlockChangeObserver

class CoreProtectObserver(
    coreProtect: CoreProtect,
) : BlockChangeObserver {
    private val api = coreProtect.api.also {
        require(it.isEnabled && it.APIVersion() >= 9) { "Unsupported CoreProtect API" }
    }

    override fun changed(actor: String, before: BlockState, after: BlockState) {
        api.logRemoval(actor, before.location, before.type, before.blockData)
        api.logPlacement(actor, after.location, after.type, after.blockData)
    }
}
