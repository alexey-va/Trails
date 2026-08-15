package ru.ruscrafting.trails.integration

import org.bukkit.Location
import org.bukkit.entity.Player

fun interface ProtectionPolicy {
    fun canCreate(player: Player, location: Location): Boolean

    companion object {
        val ALLOW_ALL = ProtectionPolicy { _, _ -> true }

        fun composite(policies: Collection<ProtectionPolicy>): ProtectionPolicy =
            ProtectionPolicy { player, location -> policies.all { it.canCreate(player, location) } }
    }
}

fun interface DecayPolicy {
    fun canDecay(location: Location): Boolean

    companion object {
        val ALLOW_ALL = DecayPolicy { true }
    }
}
