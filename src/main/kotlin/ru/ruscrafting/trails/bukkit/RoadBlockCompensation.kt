package ru.ruscrafting.trails.bukkit

import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal data class RoadBlockDelivery(
    val returnedItems: Int,
    val droppedItems: Int,
)

internal object RoadBlockCompensation {
    fun deliver(
        player: Player,
        removedMaterials: Collection<Material>,
        dropItem: (Player, ItemStack) -> Item = { owner, stack ->
            owner.world.dropItemNaturally(owner.location, stack)
        },
    ): RoadBlockDelivery {
        val stacks =
            removedMaterials
                .filter(Material::isItem)
                .groupingBy { it }
                .eachCount()
                .flatMap { (material, count) -> stacks(material, count) }
        var dropped = 0
        stacks.forEach { stack ->
            player.inventory.addItem(stack).values.forEach { leftover ->
                dropped += leftover.amount
                dropItem(player, leftover).owner = player.uniqueId
            }
        }
        return RoadBlockDelivery(stacks.sumOf(ItemStack::getAmount), dropped)
    }

    private fun stacks(
        material: Material,
        amount: Int,
    ): List<ItemStack> =
        buildList {
            var remaining = amount
            while (remaining > 0) {
                val stackSize = minOf(remaining, material.maxStackSize)
                add(ItemStack(material, stackSize))
                remaining -= stackSize
            }
        }
}
