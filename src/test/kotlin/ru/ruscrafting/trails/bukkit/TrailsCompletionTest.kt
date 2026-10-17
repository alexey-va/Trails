package ru.ruscrafting.trails.bukkit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.entity.Player
import ru.ruscrafting.trails.TrailsPlugin

class TrailsCompletionTest : StringSpec({
    val plugin = mockk<TrailsPlugin>()
    val sender = mockk<Player>()
    val target = mockk<Player>()
    val command = mockk<Command>()
    val handler = TrailsCommand(plugin)
    every { target.name } returns "RemoteAlice"
    every { plugin.server.onlinePlayers } returns listOf(target)
    every { plugin.roadProfiles() } returns setOf("rustic")
    fun complete(vararg args: String) = handler.onTabComplete(sender, command, "trails", args)

    "player suggestions stop after unknown actions and completed player arguments" {
        every { sender.hasPermission(any<String>()) } returns true
        complete("build", "bogus", "") shouldBe emptyList()
        complete("boost", "RemoteAlice", "") shouldBe emptyList()
        complete("give", "bogus", "") shouldBe emptyList()
        complete("build", "start", "bogus", "") shouldBe emptyList()
        complete("debug", "pulse", "bad", "") shouldBe emptyList()
        complete("debug", "pulse", "101", "") shouldBe emptyList()
        complete("boost", "off", "Rem") shouldBe listOf("RemoteAlice")
        complete("build", "status", "rem") shouldBe listOf("RemoteAlice")
        complete("build", "start", "rustic", "") shouldBe listOf("RemoteAlice")
        complete("debug", "pulse", "5", "") shouldBe listOf("RemoteAlice")
    }

    "nested suggestions enforce the same route permissions as execution" {
        every { sender.hasPermission(any<String>()) } returns false
        complete("boost", "") shouldBe emptyList()
        complete("show", "") shouldBe emptyList()
        every { sender.hasPermission("trails.toggle-boost.other") } returns true
        complete("b") shouldBe listOf("boost")
        complete("boost", "") shouldBe listOf("RemoteAlice", "off", "on")
    }
})
