package ru.ruscrafting.trails.integration

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.enums.FlagTarget
import me.angeschossen.lands.api.flags.enums.RoleFlagCategory
import me.angeschossen.lands.api.flags.type.RoleFlag
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.ruscrafting.trails.config.IntegrationSettings
import ru.ruscrafting.trails.config.LocaleService

class LandsProtection(
    plugin: Plugin,
    settings: IntegrationSettings,
    locale: LocaleService,
) : ProtectionPolicy {
    private val integration = LandsIntegration.of(plugin)
    private val roleFlag =
        RoleFlag.of(integration, FlagTarget.PLAYER, RoleFlagCategory.ACTION, "allow_trails")

    init {
        reconfigure(settings, locale)
    }

    fun reconfigure(settings: IntegrationSettings, locale: LocaleService) {
        roleFlag.setApplyInSubareas(settings.landsApplyInSubAreas)
        roleFlag.setAlwaysAllowInWilderness(settings.landsPathsInWilderness)
        roleFlag.setDisplayName(locale.landsDisplayName)
        roleFlag.setIcon(ItemStack(Material.matchMaterial(settings.landsFlagIconMaterial) ?: Material.DIRT_PATH))
        roleFlag.setDescription(locale.landsDescription)
        roleFlag.setDisplay(true)
    }

    override fun canCreate(player: Player, location: Location): Boolean {
        val world = location.world ?: return false
        val landWorld = integration.getWorld(world) ?: return true
        return landWorld.hasRoleFlag(player.uniqueId, location, roleFlag)
    }
}
