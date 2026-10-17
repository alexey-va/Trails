package ru.ruscrafting.trails

import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal fun awaitTrailsReady(plugin: TrailsPlugin, runtime: MockBukkitTestRuntime) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!plugin.isReady() && plugin.isEnabled && System.nanoTime() < deadline) {
        runtime.performTicks(1)
        Thread.sleep(1)
    }
    check(plugin.isReady()) { "Trails did not reach native readiness" }
}

internal fun <T> CompletableFuture<T>.awaitTrails(server: ServerMock): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!isDone && System.nanoTime() < deadline) {
        server.scheduler.performTicks(1)
        Thread.sleep(1)
    }
    return get(1, TimeUnit.SECONDS)
}
