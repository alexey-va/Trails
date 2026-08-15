package ru.ruscrafting.trails.integration

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flag
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException
import org.bukkit.Location
import org.bukkit.entity.Player

class WorldGuardProtection(
    registerDecayFlag: Boolean,
    private var checkBypass: Boolean,
) : ProtectionPolicy, DecayPolicy {
    private val trailsFlag = registerStateFlag("trails-flag")
    private val decayFlag = if (registerDecayFlag) registerStateFlag("trails-decay-flag") else null
    private var decayEnabled = registerDecayFlag

    fun reconfigure(checkBypass: Boolean, decayEnabled: Boolean) {
        require(!decayEnabled || decayFlag != null) {
            "WorldGuard trails-decay-flag was not registered during onLoad; restart is required"
        }
        this.checkBypass = checkBypass
        this.decayEnabled = decayEnabled
    }

    override fun canCreate(player: Player, location: Location): Boolean {
        val world = location.world ?: return false
        val localPlayer = WorldGuardPlugin.inst().wrapPlayer(player)
        if (checkBypass && WorldGuard.getInstance().platform.sessionManager.hasBypass(localPlayer, BukkitAdapter.adapt(world))) {
            return true
        }
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        return query.testState(BukkitAdapter.adapt(location), localPlayer, trailsFlag)
    }

    override fun canDecay(location: Location): Boolean {
        if (!decayEnabled) return true
        val flag = decayFlag ?: return true
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        return query.getApplicableRegions(BukkitAdapter.adapt(location)).testState(null, flag)
    }

    private fun registerStateFlag(name: String): StateFlag {
        val registry = WorldGuard.getInstance().flagRegistry
        return try {
            StateFlag(name, true).also(registry::register)
        } catch (_: FlagConflictException) {
            existingStateFlag(registry.get(name), name)
        } catch (_: IllegalStateException) {
            existingStateFlag(registry.get(name), name)
        }
    }

    private fun existingStateFlag(flag: Flag<*>?, name: String): StateFlag =
        flag as? StateFlag ?: error("WorldGuard flag '$name' is already registered with an incompatible type")
}
