package ru.ruscrafting.trails.integration

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URLClassLoader
import java.util.UUID

class ArcProductTelemetryOptionalTest : FreeSpec({
    "enabling trails without ARC never loads its optional API" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            val targetName = "ru.ruscrafting.trails.integration.ArcProductTelemetry"
            val source = ArcProductTelemetry::class.java.protectionDomain.codeSource.location
            var apiLoads = 0
            object : URLClassLoader(arrayOf(source), ArcProductTelemetry::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.arc.paper.api.")) {
                        apiLoads++
                        throw ClassNotFoundException(name)
                    }
                    if (name.startsWith(targetName)) {
                        return (findLoadedClass(name) ?: findClass(name)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { isolated ->
                val type = isolated.loadClass(targetName)
                val instance = type.getField("INSTANCE").get(null)
                type.getMethod("enabled", UUID::class.java).invoke(instance, UUID.randomUUID())
                apiLoads shouldBe 0
            }
        }
    }
})
