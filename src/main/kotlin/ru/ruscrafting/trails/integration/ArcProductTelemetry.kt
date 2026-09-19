package ru.ruscrafting.trails.integration

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Reports an actual off-to-on preference change; no per-movement telemetry. */
internal object ArcProductTelemetry {
    fun enabled(playerId: UUID) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ARC")) return
        runCatching { AvailableArcTelemetry.enabled(playerId) }
    }

    private object AvailableArcTelemetry {
        fun enabled(playerId: UUID) {
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)
                ?.recordEvent(playerId, "trails", "trail_enabled", UUID.randomUUID().toString())
        }
    }
}
