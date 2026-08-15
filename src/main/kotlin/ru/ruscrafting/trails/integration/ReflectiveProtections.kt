package ru.ruscrafting.trails.integration

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.trails.config.IntegrationSettings
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility adapters for optional plugins that do not provide a stable public artifact for this build.
 * Classes, singleton APIs, enum values, fields, and reflective method lookups are resolved once at startup.
 */
object ReflectiveProtections {
    private val methodCache = ConcurrentHashMap<MethodKey, Method>()

    fun towny(plugin: Plugin, settings: IntegrationSettings): ProtectionPolicy =
        guarded(plugin, "Towny") {
            val loader = plugin.javaClass.classLoader
            val api = loader.loadClass("com.palmergames.bukkit.towny.TownyAPI").static("getInstance")
            val universe = loader.loadClass("com.palmergames.bukkit.towny.TownyUniverse").static("getInstance")
            val worldCoordClass = loader.loadClass("com.palmergames.bukkit.towny.object.WorldCoord")
            val check: (Player, Location) -> Boolean = { player, location ->
                val wilderness = api.call("isWilderness", location.block) as Boolean
                if (wilderness) {
                    settings.townyPathsInWilderness
                } else {
                    val resident = universe.call("getResident", player.uniqueId)
                    if (resident == null) {
                        false
                    } else {
                        val worldCoord = worldCoordClass.static("parseWorldCoord", player)
                        val townBlock = worldCoord.requiredCall("getTownBlock")
                        if (townBlock.call("hasTown") != true) {
                            settings.townyPathsInWilderness
                        } else {
                            val blockTown = townBlock.requiredCall("getTown")
                            val residentTown = resident.requiredCall("getTown")
                            val sameTown = blockTown == residentTown
                            val sameNation =
                                blockTown.call("hasNation") == true &&
                                    residentTown.call("hasNation") == true &&
                                    blockTown.call("getNation") == residentTown.call("getNation")
                            if (settings.townyPermissionMode) {
                                when {
                                    sameTown -> player.hasPermission("trails.towny.town")
                                    sameNation -> player.hasPermission("trails.towny.nation")
                                    else -> false
                                }
                            } else {
                                sameTown || sameNation
                            }
                        }
                    }
                }
            }
            check
        }

    fun griefPrevention(plugin: Plugin, pathsInWilderness: Boolean): ProtectionPolicy =
        guarded(plugin, "GriefPrevention") {
            val loader = plugin.javaClass.classLoader
            val instance = loader.loadClass("me.ryanhamshire.GriefPrevention.GriefPrevention").staticField("instance")
            val dataStore = instance.field("dataStore")
            val permissionClass = loader.loadClass("me.ryanhamshire.GriefPrevention.ClaimPermission")
            @Suppress("UNCHECKED_CAST")
            val build = java.lang.Enum.valueOf(permissionClass as Class<out Enum<*>>, "Build")
            val check: (Player, Location) -> Boolean = { player, location ->
                val claim = dataStore.call("getClaimAt", location, false, null)
                claim?.call("checkPermission", player, build, null) == null && (claim != null || pathsInWilderness)
            }
            check
        }

    fun playerPlot(plugin: Plugin): ProtectionPolicy =
        guarded(plugin, "PlayerPlot") {
            val api = plugin.javaClass.classLoader.loadClass("de.whitescan.playerplot.PlayerPlotAPI").static("getInstance")
            val check: (Player, Location) -> Boolean = { player, location -> api.call("hasAccessAt", player, location) as Boolean }
            check
        }

    fun redProtect(plugin: Plugin): ProtectionPolicy =
        guarded(plugin, "RedProtect") {
            val redProtect = plugin.javaClass.classLoader.loadClass("br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect").static("get")
            val api = redProtect.requiredCall("getAPI")
            val check: (Player, Location) -> Boolean = { player, location ->
                val region = api.call("getRegion", location)
                if (region == null) true else region.call("canBuild", player) as Boolean
            }
            check
        }

    fun residence(plugin: Plugin): ProtectionPolicy =
        guarded(plugin, "Residence") {
            val loader = plugin.javaClass.classLoader
            val manager = loader.loadClass("com.bekvon.bukkit.residence.api.ResidenceApi").static("getResidenceManager")
            val build = loader.loadClass("com.bekvon.bukkit.residence.containers.Flags").staticField("build")
            val check: (Player, Location) -> Boolean = { player, location ->
                val residence = manager.call("getByLoc", location)
                if (residence == null) {
                    true
                } else {
                    residence.requiredCall("getPermissions").call("playerHas", player, build, true) as Boolean
                }
            }
            check
        }

    private fun guarded(
        plugin: Plugin,
        integrationName: String,
        initialize: () -> (Player, Location) -> Boolean,
    ): ProtectionPolicy {
        val check =
            try {
                initialize()
            } catch (exception: Exception) {
                plugin.logger.severe("$integrationName integration could not initialize; denying trail creation: ${exception.message}")
                return ProtectionPolicy { _, _ -> false }
            } catch (exception: LinkageError) {
                plugin.logger.severe("$integrationName integration is incompatible; denying trail creation: ${exception.message}")
                return ProtectionPolicy { _, _ -> false }
            }
        val warned = AtomicBoolean()
        return ProtectionPolicy { player, location ->
            try {
                check(player, location)
            } catch (exception: Exception) {
                if (warned.compareAndSet(false, true)) {
                    plugin.logger.severe("$integrationName integration failed; denying trail creation: ${exception.message}")
                }
                false
            } catch (exception: LinkageError) {
                if (warned.compareAndSet(false, true)) {
                    plugin.logger.severe("$integrationName integration became incompatible; denying trail creation: ${exception.message}")
                }
                false
            }
        }
    }

    private fun Class<*>.static(name: String, vararg args: Any?): Any = invoke(null, name, args)!!

    private fun Class<*>.staticField(name: String): Any = getField(name).get(null)

    private fun Any.field(name: String): Any = javaClass.getField(name).get(this)

    private fun Any.call(name: String, vararg args: Any?): Any? = javaClass.invoke(this, name, args)

    private fun Any.requiredCall(name: String, vararg args: Any?): Any =
        call(name, *args) ?: throw ReflectiveOperationException("${javaClass.name}.$name returned null")

    private fun Class<*>.invoke(target: Any?, name: String, args: Array<out Any?>): Any? {
        val key = MethodKey(this, name, args.map { it?.javaClass })
        val method =
            methodCache.computeIfAbsent(key) {
                methods.firstOrNull { candidate -> candidate.name == name && candidate.accepts(args) }
                    ?: throw NoSuchMethodException("${this.name}.$name(${args.size} arguments)")
            }
        return method.invoke(target, *args)
    }

    private fun Method.accepts(args: Array<out Any?>): Boolean =
        parameterCount == args.size &&
            parameterTypes.zip(args).all { (type, value) -> value == null || type.boxed().isInstance(value) }

    private fun Class<*>.boxed(): Class<*> =
        when (this) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            else -> this
        }

    private data class MethodKey(
        val owner: Class<*>,
        val name: String,
        val arguments: List<Class<*>?>,
    )
}
