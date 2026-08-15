package ru.ruscrafting.trails.integration

import net.coreprotect.CoreProtect
import org.bukkit.block.BlockState
import org.bukkit.plugin.Plugin
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

class LogBlockObserver(
    logBlockPlugin: Plugin,
) : BlockChangeObserver {
    private val consumer = logBlockPlugin.javaClass.getMethod("getConsumer").invoke(logBlockPlugin)
    private val actorClass = logBlockPlugin.javaClass.classLoader.loadClass("de.diddiz.LogBlock.Actor")
    private val replace =
        consumer.javaClass.methods.first { method ->
            method.name == "queueBlockReplace" && method.parameterCount == 3
        }

    override fun changed(actor: String, before: BlockState, after: BlockState) {
        val logActor = actorClass.getConstructor(String::class.java).newInstance(actor)
        replace.invoke(consumer, logActor, before, after)
    }
}

class DynmapObserver(
    private val dynmapPlugin: Plugin,
) : BlockChangeObserver {
    private val trigger =
        dynmapPlugin.javaClass.methods.first { method ->
            method.name == "triggerRenderOfBlock" && method.parameterCount == 4
        }

    override fun changed(actor: String, before: BlockState, after: BlockState) {
        val location = after.location
        trigger.invoke(dynmapPlugin, location.world.name, location.blockX, location.blockY, location.blockZ)
    }
}
