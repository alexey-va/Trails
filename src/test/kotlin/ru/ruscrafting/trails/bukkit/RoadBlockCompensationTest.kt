package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime

class RoadBlockCompensationTest :
    FreeSpec({
        lateinit var runtime: MockBukkitTestRuntime

        beforeTest { runtime = MockBukkitTestRuntime.open() }
        afterTest { runtime.close() }

        "inventory overflow is dropped at the builder with an ownership lock" {
            val server = runtime.server
            val world = server.addSimpleWorld("world")
            val player = server.addPlayer("FullInventoryBuilder")
            player.teleport(Location(world, 0.5, 65.0, 0.5))
            player.inventory.contents.indices.forEach { slot ->
                player.inventory.setItem(slot, ItemStack(Material.COBBLESTONE, 64))
            }

            val result = RoadBlockCompensation.deliver(player, listOf(Material.STONE))

            result.returnedItems shouldBe 1
            result.droppedItems shouldBe 1
            val dropped = world.entities.filterIsInstance<Item>().single()
            dropped.itemStack.type shouldBe Material.STONE
            dropped.owner shouldBe player.uniqueId
        }
    })
