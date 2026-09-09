package ru.ruscrafting.trails.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Reports an actual off-to-on preference change; no per-movement telemetry. */
internal object ArcProductTelemetry {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun enabled(playerId: UUID) {
        runCatching { telemetry?.recordEvent(playerId, "trails", "trail_enabled", UUID.randomUUID().toString()) }
    }
}
