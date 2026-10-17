package ru.ruscrafting.trails

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.concurrent.TimeUnit

/** Wait for the real command callback, including native publication after worker completion. */
internal fun PlayerMock.awaitTrailsMessage(server: ServerMock, expected: String): String {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    val received = mutableListOf<String>()
    while (System.nanoTime() < deadline) {
        var message = nextComponentMessage()
        while (message != null) {
            val text = PlainTextComponentSerializer.plainText().serialize(message)
            received += text
            if (expected in text) return text
            message = nextComponentMessage()
        }
        server.scheduler.performTicks(1)
        Thread.sleep(1)
    }
    error("Command did not report '$expected'; received: $received")
}
