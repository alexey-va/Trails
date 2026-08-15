package ru.ruscrafting.trails.bukkit

import org.bukkit.entity.Player
import ru.ruscrafting.trails.service.WalkSpeedTarget
import java.util.UUID

class BukkitWalkSpeedTarget(
    val player: Player,
) : WalkSpeedTarget {
    override val id: UUID get() = player.uniqueId

    override var walkSpeed: Float
        get() = player.walkSpeed
        set(value) {
            player.walkSpeed = value
        }
}
