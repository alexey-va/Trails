package ru.ruscrafting.trails.bukkit

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.block.Block
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import ru.ruscrafting.trails.TrailsPlugin
import ru.ruscrafting.trails.TrailsPlugin.PlayerTarget

class TrailsCommand(
    private val plugin: TrailsPlugin,
) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            null -> toggleSelf(sender)
            "on", "off" -> setTrails(sender, args)
            "boost" -> setBoost(sender, args.drop(1))
            "show" -> show(sender, args.drop(1))
            "reload" -> reload(sender, args.size)
            "status" -> status(sender, args.size)
            "validate" -> validate(sender, args.size)
            "give" -> give(sender, args.drop(1))
            "build" -> buildRoad(sender, args.drop(1))
            "debug" -> debug(sender, args.drop(1))
            else -> if (args.size == 1) toggleOther(sender, args[0]) else plugin.message(sender, "messages.wrongArgs")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val candidates =
            when (args.size) {
                1 ->
                    buildList {
                        if (sender.hasPermission("trails.toggle")) addAll(listOf("on", "off"))
                        if (sender.hasPermission("trails.toggle-boost")) add("boost")
                        if (sender.hasPermission("trails.show")) add("show")
                        if (sender.hasPermission("trails.reload")) add("reload")
                        if (sender.hasPermission("trails.status")) add("status")
                        if (sender.hasPermission("trails.validate")) add("validate")
                        if (sender.hasPermission("trails.tools.give")) add("give")
                        if (canUseRoads(sender)) add("build")
                        if (sender.hasPermission(DEBUG_PERMISSION)) add("debug")
                        if (sender.hasPermission("trails.other")) addAll(otherPlayers(sender))
                    }
                2 ->
                    when (args[0].lowercase()) {
                        "on", "off" -> if (sender.hasPermission("trails.other")) otherPlayers(sender) else emptyList()
                        "boost" -> listOf("on", "off") + if (sender.hasPermission("trails.toggle-boost.other")) otherPlayers(sender) else emptyList()
                        "show" -> listOf("30")
                        "give" -> if (sender.hasPermission("trails.tools.give")) TrailToolKind.entries.map { it.id } else emptyList()
                        "build" -> if (canUseRoads(sender)) listOf("list", "start", "commit", "cancel", "undo", "status") else emptyList()
                        "debug" -> if (sender.hasPermission(DEBUG_PERMISSION)) listOf("inspect", "pulse", "decay") else emptyList()
                        else -> emptyList()
                    }
                3 ->
                    when {
                        args[0].equals("boost", true) && sender.hasPermission("trails.toggle-boost.other") -> otherPlayers(sender)
                        args[0].equals("give", true) && sender.hasPermission("trails.tools.give") -> plugin.server.onlinePlayers.map(Player::getName)
                        args[0].equals("build", true) &&
                            canUseRoads(sender) &&
                            (args[1].equals("start", true) || args[1].equals("list", true)) -> plugin.roadProfiles().toList()
                        args[0].equals("build", true) && sender.hasPermission(ROADS_MANAGE_PERMISSION) ->
                            plugin.server.onlinePlayers.map(Player::getName)
                        args[0].equals("debug", true) &&
                            sender.hasPermission(DEBUG_PERMISSION) &&
                            args[1].equals("pulse", true) -> listOf("1", "5", "10", "25", "100")
                        args[0].equals("debug", true) &&
                            sender.hasPermission(DEBUG_PERMISSION) &&
                            (args[1].equals("inspect", true) || args[1].equals("decay", true)) ->
                            plugin.server.worlds.map { it.name }
                        else -> emptyList()
                    }
                4 ->
                    when {
                        args[0].equals("build", true) &&
                            args[1].equals("start", true) &&
                            sender.hasPermission(ROADS_MANAGE_PERMISSION) -> plugin.server.onlinePlayers.map(Player::getName)
                        args[0].equals("debug", true) &&
                            sender.hasPermission(DEBUG_PERMISSION) &&
                            args[1].equals("pulse", true) ->
                            plugin.server.onlinePlayers.map(Player::getName)
                        else -> emptyList()
                    }
                else -> emptyList()
            }
        val prefix = args.lastOrNull().orEmpty()
        return candidates.distinct().filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    private fun toggleSelf(sender: CommandSender) {
        val player = sender as? Player ?: return plugin.message(sender, "messages.consoleSpecify")
        setTrails(player, arrayOf(if (plugin.trailsEnabled(player.uniqueId)) "off" else "on"))
    }

    private fun setTrails(sender: CommandSender, args: Array<out String>) {
        if (args.size > 2) return plugin.message(sender, "messages.tooManyArgs")
        val enabled = args[0].equals("on", true)
        val target =
            if (args.size == 1) {
                val player = sender as? Player ?: return plugin.message(sender, "messages.consoleSpecify")
                if (!sender.hasPermission("trails.toggle")) return plugin.message(sender, "messages.noPerm")
                PlayerTarget(player.uniqueId, player.name, player)
            } else {
                if (!sender.hasPermission("trails.other")) return plugin.message(sender, "messages.noPermOthers")
                plugin.findPlayer(args[1]) ?: return plugin.message(sender, "messages.notPlayedBefore", mapOf("%name%" to args[1]))
            }
        val current = plugin.trailsEnabled(target.uuid)
        if (current == enabled) {
            plugin.message(sender, if (enabled) "messages.alreadyOn" else "messages.alreadyOff", mapOf("%name%" to target.name))
            return
        }
        plugin.setTrailsEnabled(target.uuid, enabled)
        val other = target.uuid != (sender as? Player)?.uniqueId
        plugin.message(
            sender,
            when {
                enabled && other -> "messages.toggledOnOther"
                !enabled && other -> "messages.toggledOffOther"
                enabled -> "messages.toggledOn"
                else -> "messages.toggledOff"
            },
            mapOf("%name%" to target.name),
        )
        if (other) target.online?.let { plugin.message(it, if (enabled) "messages.toggledOn" else "messages.toggledOff") }
    }

    private fun toggleOther(sender: CommandSender, name: String) {
        if (!sender.hasPermission("trails.other")) return plugin.message(sender, "messages.noPermOthers")
        val target = plugin.findPlayer(name) ?: return plugin.message(sender, "messages.notPlayedBefore", mapOf("%name%" to name))
        setTrails(sender, arrayOf(if (plugin.trailsEnabled(target.uuid)) "off" else "on", target.name))
    }

    private fun setBoost(sender: CommandSender, args: List<String>) {
        if (args.size > 2) return plugin.message(sender, "messages.tooManyArgs")
        var requested: Boolean? = args.firstOrNull()?.takeIf { it.equals("on", true) || it.equals("off", true) }?.equals("on", true)
        val targetName = if (requested == null) args.firstOrNull() else args.getOrNull(1)
        val target =
            if (targetName == null) {
                val player = sender as? Player ?: return plugin.message(sender, "messages.consoleSpecify")
                if (!sender.hasPermission("trails.toggle-boost")) return plugin.message(sender, "messages.noPermBoost")
                PlayerTarget(player.uniqueId, player.name, player)
            } else {
                if (!sender.hasPermission("trails.toggle-boost.other")) return plugin.message(sender, "messages.noPermOthers")
                plugin.findPlayer(targetName) ?: return plugin.message(sender, "messages.notPlayedBefore", mapOf("%name%" to targetName))
            }
        if (requested == null) requested = !plugin.boostEnabled(target.uuid)
        val enabled = requested
        val current = plugin.boostEnabled(target.uuid)
        if (current == enabled) {
            plugin.message(
                sender,
                if (enabled) "messages.alreadyOnBoost${if (targetName == null) "" else "Other"}" else "messages.alreadyOffBoost${if (targetName == null) "" else "Other"}",
                mapOf("%name%" to target.name),
            )
            return
        }
        plugin.setBoostEnabled(target.uuid, enabled)
        val other = target.uuid != (sender as? Player)?.uniqueId
        plugin.message(
            sender,
            when {
                enabled && other -> "messages.toggledOnBoostOther"
                !enabled && other -> "messages.toggledOffBoostOther"
                enabled -> "messages.boostOn"
                else -> "messages.boostOff"
            },
            mapOf("%name%" to target.name),
        )
        if (!enabled) target.online?.let(plugin::restoreSpeed)
        if (other) target.online?.let { plugin.message(it, if (enabled) "messages.boostOn" else "messages.boostOff") }
    }

    private fun show(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("trails.show")) return plugin.message(sender, "messages.noPerm")
        val player = sender as? Player ?: return plugin.message(sender, "messages.only-players")
        if (args.size > 1) return plugin.message(sender, "messages.tooManyArgs")
        val radius = args.firstOrNull()?.toDoubleOrNull() ?: if (args.isEmpty()) 30.0 else return plugin.message(sender, "messages.second-argument-not-double")
        if (radius !in 1.0..128.0) return plugin.message(sender, "messages.radius-out-of-range")
        plugin.showTrails(player, radius)
        plugin.message(
            sender,
            if (args.isEmpty()) "messages.showing-trails" else "messages.showing-trails-radius",
            mapOf("%radius%" to radius.toInt().toString()),
        )
    }

    private fun reload(sender: CommandSender, count: Int) {
        if (count != 1) return plugin.message(sender, "messages.tooManyArgs")
        if (!sender.hasPermission("trails.reload")) return plugin.message(sender, "messages.reloadNoPerm")
        plugin.reloadTrails().onSuccess {
            plugin.message(sender, "messages.reload")
        }.onFailure { error ->
            plugin.message(sender, "messages.reloadFailed", mapOf("%error%" to (error.message ?: error.javaClass.simpleName)))
        }
    }

    private fun status(sender: CommandSender, count: Int) {
        if (count != 1) return plugin.message(sender, "messages.tooManyArgs")
        if (!sender.hasPermission("trails.status")) return plugin.message(sender, "messages.noPerm")
        plugin.showStatus(sender)
    }

    private fun validate(sender: CommandSender, count: Int) {
        if (count != 1) return plugin.message(sender, "messages.tooManyArgs")
        if (!sender.hasPermission("trails.validate")) return plugin.message(sender, "messages.noPerm")
        plugin.validateConfiguration()
            .onSuccess { settings ->
                plugin.message(
                    sender,
                    "messages.validateOk",
                    mapOf("%trail-count%" to settings.definitions.size.toString()),
                )
            }.onFailure { error ->
                plugin.message(
                    sender,
                    "messages.validateFailed",
                    mapOf("%error%" to (error.message ?: error.javaClass.simpleName)),
                )
            }
    }

    private fun give(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("trails.tools.give")) return plugin.message(sender, "messages.noPerm")
        if (args.size !in 1..2) return plugin.message(sender, "messages.wrongArgs")
        val kind = TrailToolKind.fromId(args[0].lowercase()) ?: return plugin.message(sender, "messages.wrongArgs")
        val targetName = args.getOrNull(1)
        val target =
            when {
                targetName != null ->
                    plugin.server.getPlayerExact(targetName)
                        ?: return plugin.message(sender, "messages.playerNotOnline", mapOf("%name%" to targetName))
                sender is Player -> sender
                else -> return plugin.message(sender, "messages.consoleSpecify")
            }
        if (!plugin.giveTool(target, kind)) {
            plugin.message(sender, "messages.inventoryFull", mapOf("%name%" to target.name))
            return
        }
        val replacements = mapOf("%name%" to target.name, "%tool%" to plugin.toolLabel(kind))
        plugin.message(sender, "messages.toolGiven", replacements)
        if (target !== sender) plugin.message(target, "messages.toolReceived", replacements)
    }

    private fun buildRoad(sender: CommandSender, args: List<String>) {
        if (!canUseRoads(sender)) return plugin.message(sender, "messages.noPerm")
        val action = args.firstOrNull()?.lowercase() ?: return plugin.message(sender, "messages.wrongArgs")
        if (action == "list") {
            if (args.size !in 1..2) return plugin.message(sender, "messages.wrongArgs")
            if (!plugin.showRoadProfiles(sender, args.getOrNull(1))) {
                plugin.message(sender, "messages.roadUnknownProfile")
            }
            return
        }
        val target =
            when (action) {
                "start" -> {
                    if (args.size !in 2..3) return plugin.message(sender, "messages.wrongArgs")
                    roadTarget(sender, args.getOrNull(2)) ?: return
                }
                "commit", "cancel", "undo", "status" -> {
                    if (args.size !in 1..2) return plugin.message(sender, "messages.wrongArgs")
                    roadTarget(sender, args.getOrNull(1)) ?: return
                }
                else -> return plugin.message(sender, "messages.wrongArgs")
            }
        val result =
            when (action) {
                "start" -> plugin.roadStart(target, args[1])
                "commit" -> plugin.roadCommit(target)
                "cancel" -> plugin.roadCancel(target)
                "undo" -> plugin.roadUndo(target)
                else -> plugin.roadStatus(target)
            }
        val messages = result.notices + RoadNotice(result.message, result.replacements)
        messages.forEach { notice ->
            val replacements = notice.replacements + ("%name%" to target.name)
            plugin.message(sender, notice.message, replacements)
            if (target !== sender && action != "status") {
                plugin.message(target, notice.message, replacements)
            }
        }
    }

    private fun debug(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(DEBUG_PERMISSION)) return plugin.message(sender, "messages.noPerm")
        when (args.firstOrNull()?.lowercase()) {
            "inspect" -> {
                val block = debugBlock(sender, args.drop(1)) ?: return
                sender.sendMessage(Component.text(plugin.debugInspect(block)))
            }
            "decay" -> {
                val block = debugBlock(sender, args.drop(1)) ?: return
                sender.sendMessage(Component.text(plugin.debugDecay(block)))
            }
            "pulse" -> {
                if (args.size !in 2..3) return plugin.message(sender, "messages.wrongArgs")
                val count = args.getOrNull(1)?.toIntOrNull()
                    ?: return sender.sendMessage(Component.text("TRAILS_DEBUG error=invalid_count expected=1..100"))
                if (count !in 1..100) {
                    sender.sendMessage(Component.text("TRAILS_DEBUG error=count_out_of_range expected=1..100"))
                    return
                }
                val targetName = args.getOrNull(2)
                val player =
                    when {
                        targetName != null ->
                            plugin.server.getPlayerExact(targetName)
                                ?: return sender.sendMessage(Component.text("TRAILS_DEBUG error=player_not_online player=$targetName"))
                        sender is Player -> sender
                        else -> return sender.sendMessage(Component.text("TRAILS_DEBUG error=online_player_required"))
                    }
                sender.sendMessage(Component.text(plugin.debugPulse(player, count)))
            }
            else -> plugin.message(sender, "messages.wrongArgs")
        }
    }

    private fun debugBlock(sender: CommandSender, args: List<String>): Block? {
        if (args.isEmpty()) {
            val player = sender as? Player
                ?: run {
                    sender.sendMessage(Component.text("TRAILS_DEBUG error=coordinates_required syntax=<world> <x> <y> <z>"))
                    return null
                }
            return player.location.clone().subtract(0.0, 0.1, 0.0).block
        }
        if (args.size != 4) {
            plugin.message(sender, "messages.wrongArgs")
            return null
        }
        val world = plugin.server.getWorld(args[0])
        val x = args[1].toIntOrNull()
        val y = args[2].toIntOrNull()
        val z = args[3].toIntOrNull()
        if (world == null || x == null || y == null || z == null || y !in world.minHeight until world.maxHeight) {
            sender.sendMessage(Component.text("TRAILS_DEBUG error=invalid_location"))
            return null
        }
        return world.getBlockAt(x, y, z)
    }

    private fun roadTarget(
        sender: CommandSender,
        name: String?,
    ): Player? =
        when {
            name != null && !sender.hasPermission(ROADS_MANAGE_PERMISSION) -> {
                plugin.message(sender, "messages.noPermOthers")
                null
            }
            name != null ->
                plugin.server.getPlayerExact(name).also {
                    if (it == null) plugin.message(sender, "messages.playerNotOnline", mapOf("%name%" to name))
                }
            sender is Player -> sender
            else -> {
                plugin.message(sender, "messages.consoleSpecify")
                null
            }
        }

    private fun otherPlayers(sender: CommandSender): List<String> =
        plugin.server.onlinePlayers.filter { it !== sender }.map(Player::getName)

    private fun canUseRoads(sender: CommandSender): Boolean =
        sender.hasPermission(ROADS_USE_PERMISSION) || sender.hasPermission(ROADS_MANAGE_PERMISSION)

    private companion object {
        const val ROADS_USE_PERMISSION = "trails.roads.use"
        const val ROADS_MANAGE_PERMISSION = "trails.roads.manage"
        const val DEBUG_PERMISSION = "trails.debug"
    }
}
