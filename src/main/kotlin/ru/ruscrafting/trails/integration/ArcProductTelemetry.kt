package ru.ruscrafting.trails.integration

import java.util.UUID

/** Reports an actual off-to-on preference change; no per-movement telemetry. */
internal object ArcProductTelemetry {
    private val method by lazy {
        runCatching {
            // Build the external name at runtime: Shadow relocates our bundled ru.arc classes and literals.
            Class.forName(listOf("ru", "arc", "metrics", "ExternalProductTelemetryBridge").joinToString(".")).getMethod(
                "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
            )
        }.getOrNull()
    }

    fun enabled(playerId: UUID) {
        runCatching { method?.invoke(null, playerId, "trails", "trail_enabled", UUID.randomUUID().toString()) }
    }
}
