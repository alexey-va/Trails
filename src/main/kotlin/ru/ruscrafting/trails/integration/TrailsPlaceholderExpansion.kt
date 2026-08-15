package ru.ruscrafting.trails.integration

import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.ruscrafting.trails.TrailsPlugin

class TrailsPlaceholderExpansion(
    private val plugin: TrailsPlugin,
) : PlaceholderExpansion() {
    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString()

    override fun getIdentifier(): String = "trails"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun onRequest(player: OfflinePlayer?, params: String): String =
        if (params.equals("toggled_on", ignoreCase = true) && player != null) {
            plugin.trailsEnabled(player.uniqueId).toString()
        } else {
            ""
        }

    fun parse(player: Player?, message: String): String = PlaceholderAPI.setPlaceholders(player, message)
}
