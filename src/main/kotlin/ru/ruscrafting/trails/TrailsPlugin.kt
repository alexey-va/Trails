package ru.ruscrafting.trails

import net.coreprotect.CoreProtect
import net.kyori.adventure.text.Component
import org.bstats.bukkit.Metrics
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.trails.bukkit.BukkitWalkSpeedTarget
import ru.ruscrafting.trails.bukkit.DecayScheduler
import ru.ruscrafting.trails.bukkit.TrailsCommand
import ru.ruscrafting.trails.bukkit.TrailsListener
import ru.ruscrafting.trails.bukkit.TrailToolKind
import ru.ruscrafting.trails.config.LocaleService
import ru.ruscrafting.trails.config.LegacyConfigMigrator
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.config.TrailsSettingsLoader
import ru.ruscrafting.trails.config.YamlConfig
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.integration.CoreProtectObserver
import ru.ruscrafting.trails.integration.DecayPolicy
import ru.ruscrafting.trails.integration.DynmapObserver
import ru.ruscrafting.trails.integration.LandsProtection
import ru.ruscrafting.trails.integration.LogBlockObserver
import ru.ruscrafting.trails.integration.ProtectionPolicy
import ru.ruscrafting.trails.integration.ReflectiveProtections
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
import ru.ruscrafting.trails.integration.WorldGuardProtection
import ru.ruscrafting.trails.service.BlockChangeObserver
import ru.ruscrafting.trails.service.SpeedController
import ru.ruscrafting.trails.service.TrailService
import ru.ruscrafting.trails.storage.CustomBlockTrailStore
import ru.ruscrafting.trails.storage.PlayerPreferencesStore
import ru.ruscrafting.trails.storage.TrailBlockState
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

open class TrailsPlugin : JavaPlugin() {
    lateinit var settings: TrailsSettings
        private set
    private lateinit var locale: LocaleService
    private lateinit var configFile: YamlConfig
    private lateinit var trailsFile: YamlConfig
    private lateinit var preferences: PlayerPreferencesStore
    private lateinit var blockStore: CustomBlockTrailStore
    private lateinit var trailService: TrailService
    private var protection: ProtectionPolicy = ProtectionPolicy.ALLOW_ALL
    private var decayPolicy: DecayPolicy = DecayPolicy.ALLOW_ALL
    private var worldGuard: WorldGuardProtection? = null
    private var lands: LandsProtection? = null
    private var placeholderExpansion: TrailsPlaceholderExpansion? = null
    private val speedController = SpeedController()
    private val denyMessageCooldown = ConcurrentHashMap.newKeySet<UUID>()
    private var speedTask: BukkitTask? = null
    private var preferenceSaveTask: BukkitTask? = null
    private var decayScheduler: DecayScheduler? = null
    private lateinit var commandHandler: TrailsCommand
    private var localeCommand: Command? = null
    private val toolKindKey by lazy { NamespacedKey(this, "trail_tool_kind") }

    override fun onLoad() {
        saveDefaultConfig()
        ensureBundledResources()
        configFile = YamlConfig(dataFolder.toPath(), "config.yml")
        val migration =
            LegacyConfigMigrator(
                dataFolder = dataFolder.toPath(),
                materialExists = { Material.getMaterial(it) != null },
                particleExists = { runCatching { Particle.valueOf(it) }.isSuccess },
            ).migrateIfNeeded(configFile)
        if (migration.migrated) {
            logger.info("Migrated legacy config.yml to schema v2; backup: ${migration.backup?.fileName}")
            configFile.reload()
        }
        ensureBundledResource("trails.yml")
        trailsFile = YamlConfig(dataFolder.toPath(), "trails.yml")
        val loadSettings = loadSettings(reload = false)
        val loadLocale = loadLocale(loadSettings)
        if (
            server.pluginManager.getPlugin("WorldGuard") != null &&
            loadSettings.integrations.worldGuardEnabled
        ) {
            worldGuard =
                WorldGuardProtection(
                    registerDecayFlag = loadSettings.integrations.worldGuardDecayFlag,
                    checkBypass = loadSettings.integrations.worldGuardCheckBypass,
                )
        }
        if (loadSettings.integrations.landsEnabled && server.pluginManager.getPlugin("Lands") != null) {
            lands = LandsProtection(this, loadSettings.integrations, loadLocale)
        }
    }

    override fun onEnable() {
        preferences =
            PlayerPreferencesStore(dataFolder.toPath()) { error ->
                logger.severe("Could not save players.yml asynchronously: ${error.message}")
            }
        blockStore = CustomBlockTrailStore(this)
        val loadedSettings = loadSettings(reload = true)
        val loadedLocale = loadLocale(loadedSettings)
        applyRuntime(loadedSettings, loadedLocale)
        runCatching { Metrics(this, BSTATS_PLUGIN_ID) }
            .onFailure { logger.warning("bStats could not initialize and will be skipped: ${it.message}") }

        server.pluginManager.registerEvents(TrailsListener(this), this)
        val command = checkNotNull(getCommand("trails")) { "Command 'trails' is missing from plugin.yml" }
        commandHandler = TrailsCommand(this)
        command.setExecutor(commandHandler)
        command.tabCompleter = commandHandler
        syncLocaleCommand()

        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = TrailsPlaceholderExpansion(this).also { check(it.register()) { "Could not register PlaceholderAPI expansion" } }
        }
        logger.info("Trails ${pluginMeta.version} enabled with ${settings.definitions.size} trail definitions (roads excluded)")
    }

    override fun onDisable() {
        restoreAllSpeeds()
        cancelRuntimeTasks()
        if (::preferences.isInitialized) {
            runCatching { preferences.close() }.onFailure { logger.severe("Could not save players.yml: ${it.message}") }
        }
        placeholderExpansion?.unregister()
        placeholderExpansion = null
        localeCommand?.unregister(server.commandMap)
        localeCommand = null
    }

    fun reloadTrails(): Result<Unit> =
        runCatching {
            val loadedSettings = loadSettings(reload = true)
            val loadedLocale = loadLocale(loadedSettings)
            applyRuntime(loadedSettings, loadedLocale)
            if (::commandHandler.isInitialized) syncLocaleCommand()
            logger.info("Trails configuration reloaded")
        }.onFailure { error ->
            logger.severe("Trails reload rejected; previous runtime remains active: ${error.message}")
        }

    fun validateConfiguration(): Result<TrailsSettings> =
        runCatching {
            val loadedSettings = loadSettings(reload = true)
            loadLocale(loadedSettings)
            loadedSettings
        }

    fun showStatus(sender: CommandSender) {
        val replacements =
            mapOf(
                "%version%" to pluginMeta.version,
                "%config-version%" to settings.configVersion.toString(),
                "%trails-version%" to settings.trailsConfigVersion.toString(),
                "%locale-format%" to locale.formatName,
                "%worlds%" to settings.worldSummary(),
                "%trail-count%" to settings.definitions.size.toString(),
                "%stage-count%" to settings.definitions.sumOf { it.stages.size }.toString(),
                "%integrations%" to integrationSummary(),
            )
        listOf(
            "messages.statusHeader",
            "messages.statusConfig",
            "messages.statusWorlds",
            "messages.statusTrails",
            "messages.statusIntegrations",
        ).forEach { message(sender, it, replacements) }
    }

    fun handleMovement(player: Player, block: Block) {
        if (!settings.worldEnabled(player.world.name)) {
            restoreSpeed(player)
            return
        }
        val canBoost =
            if (settings.usePermissionForBoost) player.hasPermission("trails.boost") else boostEnabled(player.uniqueId)
        if (canBoost) {
            val multiplier = trailService.speedMultiplier(block, settings.speedBoostOnlyTrails)
            speedController.target(
                BukkitWalkSpeedTarget(player),
                multiplier,
                immediate = multiplier == 1.0 && settings.immediatelyRemoveBoost,
            )
        } else {
            restoreSpeed(player)
        }

        if (settings.sneakBypass && player.isSneaking) return
        if (!trailService.canAffect(block)) return
        val canCreate =
            if (settings.usePermissionForTrails) player.hasPermission("trails.create-trails") else trailsEnabled(player.uniqueId)
        if (!canCreate || !checkProtection(player, block)) return
        trailService.walk(player, block, settings.runModifier)
    }

    fun forceTrail(player: Player, block: Block) {
        if (!settings.worldEnabled(block.world.name) || !trailService.canAffect(block)) return
        if (!player.hasPermission("trails.trail-tool.bypass-protection") && !checkProtection(player, block)) return
        trailService.walk(player, block, settings.runModifier, forced = true)
    }

    fun decayBlock(block: Block): Boolean =
        settings.worldEnabled(block.world.name) && decayPolicy.canDecay(block.location) && trailService.decay(block, settings.stepDecayFraction)

    fun clearTrailData(block: Block) = trailService.clear(block)

    fun inspectTrail(block: Block): TrailBlockState? = trailService.inspect(block)

    fun showTrailInfo(player: Player, block: Block) {
        val state = inspectTrail(block)
        message(
            player,
            "messages.trail-info",
            mapOf(
                "%walks%" to (state?.walks ?: 0).toString(),
                "%trail%" to (state?.identity?.serialize() ?: "—"),
            ),
        )
    }

    fun createTool(kind: TrailToolKind): ItemStack {
        val material =
            when (kind) {
                TrailToolKind.ADVANCE -> settings.trailToolMaterial
                TrailToolKind.INSPECT -> settings.infoToolMaterial
            }
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.itemName(locale.render("tools.${kind.id}.name"))
        meta.lore(listOf(locale.render("tools.${kind.id}.lore")))
        meta.persistentDataContainer.set(toolKindKey, PersistentDataType.STRING, kind.id)
        meta.setEnchantmentGlintOverride(true)
        item.itemMeta = meta
        return item
    }

    fun toolKind(item: ItemStack): TrailToolKind? =
        item.itemMeta
            ?.persistentDataContainer
            ?.get(toolKindKey, PersistentDataType.STRING)
            ?.let(TrailToolKind::fromId)

    fun giveTool(
        player: Player,
        kind: TrailToolKind,
    ): Boolean = player.inventory.addItem(createTool(kind)).isEmpty()

    fun toolLabel(kind: TrailToolKind): String = locale.plain("tools.${kind.id}.label")

    fun showTrails(player: Player, radius: Double) {
        val center = player.location
        val chunkRadius = ceil(radius / 16.0).toInt()
        val squared = radius * radius
        for (x in center.chunk.x - chunkRadius..center.chunk.x + chunkRadius) {
            for (z in center.chunk.z - chunkRadius..center.chunk.z + chunkRadius) {
                if (!center.world.isChunkLoaded(x, z)) continue
                trailService.trackedBlocks(center.world.getChunkAt(x, z)).forEach { block ->
                    val location = block.location.add(0.5, 1.1, 0.5)
                    if (location.distanceSquared(center) <= squared) {
                        player.spawnParticle(settings.particle, location, 2, 0.2, 0.1, 0.2, 0.0)
                    }
                }
            }
        }
    }

    fun trailsEnabled(uuid: UUID): Boolean = preferences.get(uuid).trailsEnabled(settings.enabledByDefault)

    fun boostEnabled(uuid: UUID): Boolean = preferences.get(uuid).boostEnabled(settings.boostEnabledByDefault)

    fun setTrailsEnabled(uuid: UUID, enabled: Boolean) = preferences.setEnabled(uuid, enabled)

    fun setBoostEnabled(uuid: UUID, enabled: Boolean) = preferences.setBoost(uuid, enabled)

    fun restoreSpeed(player: Player) = speedController.restore(BukkitWalkSpeedTarget(player))

    fun message(
        sender: CommandSender,
        path: String,
        replacements: Map<String, String> = emptyMap(),
    ) {
        val player = sender as? Player
        val rendered =
            runCatching {
                locale.render(
                    path,
                    replacements + ("%name%" to (replacements["%name%"] ?: sender.name)),
                ) { text ->
                    runCatching { placeholderExpansion?.parse(player, text) ?: text }
                        .getOrElse { error ->
                            logger.warning("PlaceholderAPI could not render '$path': ${error.message}")
                            text
                        }
                }
            }.getOrElse { error ->
                logger.warning("Locale message '$path' could not be rendered: ${error.message}")
                Component.text("Trails: $path")
            }
        sender.sendMessage(rendered)
    }

    fun findPlayer(name: String): PlayerTarget? {
        server.getPlayerExact(name)?.let { return PlayerTarget(it.uniqueId, it.name, it) }
        val offline = server.offlinePlayers.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return PlayerTarget(offline.uniqueId, offline.name ?: name, offline.player)
    }

    data class PlayerTarget(
        val uuid: UUID,
        val name: String,
        val online: Player?,
    )

    private fun loadSettings(reload: Boolean): TrailsSettings {
        if (reload) {
            configFile.reload()
            trailsFile.reload()
        }
        return TrailsSettingsLoader.load(
            config = configFile,
            trails = trailsFile,
            materialExists = { Material.getMaterial(it) != null },
            particleExists = { runCatching { Particle.valueOf(it) }.isSuccess },
        )
    }

    private fun loadLocale(settings: TrailsSettings): LocaleService =
        LocaleService.load(
            dataFolder = dataFolder.toPath(),
            language = settings.language,
            commandName = settings.commandAlias ?: "trails",
        )

    private fun applyRuntime(newSettings: TrailsSettings, newLocale: LocaleService) {
        val newWorldGuard = worldGuard
        val newProtection = createProtectionPolicy(newSettings, newWorldGuard)
        val newObserver = createObserver(newSettings)
        val newTrailService =
            TrailService(
                catalog = TrailCatalog(newSettings.definitions, newSettings.strictLinks),
                store = blockStore,
                observer = newObserver,
            )

        newWorldGuard?.reconfigure(
            newSettings.integrations.worldGuardCheckBypass,
            newSettings.integrations.worldGuardDecayFlag,
        )
        lands?.reconfigure(newSettings.integrations, newLocale)
        if (::settings.isInitialized) restoreAllSpeeds()
        cancelRuntimeTasks()
        settings = newSettings
        locale = newLocale
        protection = newProtection
        decayPolicy = if (newSettings.integrations.worldGuardEnabled) newWorldGuard ?: DecayPolicy.ALLOW_ALL else DecayPolicy.ALLOW_ALL
        trailService = newTrailService
        scheduleRuntimeTasks()
    }

    private fun createProtectionPolicy(
        settings: TrailsSettings,
        worldGuard: WorldGuardProtection?,
    ): ProtectionPolicy {
        val manager = server.pluginManager
        val policies = mutableListOf<ProtectionPolicy>()
        if (settings.integrations.townyEnabled) {
            manager.enabledPlugin("Towny")?.let { policies += ReflectiveProtections.towny(it, settings.integrations) }
        }
        if (settings.integrations.landsEnabled && manager.isPluginEnabled("Lands")) {
            policies += checkNotNull(lands) { "Lands integration was enabled after onLoad; restart is required" }
        }
        if (settings.integrations.griefPreventionEnabled) {
            manager.enabledPlugin("GriefPrevention")?.let {
                policies += ReflectiveProtections.griefPrevention(it, settings.integrations.griefPreventionPathsInWilderness)
            }
        }
        if (settings.integrations.worldGuardEnabled && manager.isPluginEnabled("WorldGuard")) {
            policies += checkNotNull(worldGuard) { "WorldGuard integration was enabled after onLoad; restart is required" }
        }
        if (settings.integrations.playerPlotEnabled) manager.enabledPlugin("PlayerPlot")?.let { policies += ReflectiveProtections.playerPlot(it) }
        if (settings.integrations.redProtectEnabled) manager.enabledPlugin("RedProtect")?.let { policies += ReflectiveProtections.redProtect(it) }
        if (settings.integrations.residenceEnabled) manager.enabledPlugin("Residence")?.let { policies += ReflectiveProtections.residence(it) }
        return ProtectionPolicy.composite(policies)
    }

    private fun createObserver(settings: TrailsSettings): BlockChangeObserver {
        val observers = mutableListOf<BlockChangeObserver>()
        if (settings.integrations.coreProtectChanges) {
            (server.pluginManager.enabledPlugin("CoreProtect") as? CoreProtect)?.let { coreProtect ->
                addObserver(observers, "CoreProtect") { CoreProtectObserver(coreProtect) }
            }
        }
        if (settings.integrations.logBlockChanges) {
            server.pluginManager.enabledPlugin("LogBlock")?.let { addObserver(observers, "LogBlock") { LogBlockObserver(it) } }
        }
        if (settings.integrations.dynmapRender) {
            server.pluginManager.enabledPlugin("dynmap", "Dynmap")?.let { addObserver(observers, "Dynmap") { DynmapObserver(it) } }
        }
        return BlockChangeObserver.composite(observers)
    }

    private fun addObserver(
        observers: MutableCollection<BlockChangeObserver>,
        name: String,
        factory: () -> BlockChangeObserver,
    ) {
        runCatching(factory)
            .onSuccess { observers += safeObserver(name, it) }
            .onFailure { logger.warning("$name integration could not initialize and will be skipped: ${it.message}") }
    }

    private fun safeObserver(name: String, observer: BlockChangeObserver): BlockChangeObserver =
        BlockChangeObserver { actor, before, after ->
            runCatching { observer.changed(actor, before, after) }
                .onFailure { logger.warning("$name could not log a Trails block change: ${it.message}") }
        }

    private fun scheduleRuntimeTasks() {
        speedTask =
            server.scheduler.runTaskTimer(
                this,
                Runnable {
                    val online = server.onlinePlayers.associate { it.uniqueId to BukkitWalkSpeedTarget(it) }
                    speedController.tick(online, settings.speedBoostStep.toFloat())
                },
                0L,
                settings.speedBoostInterval,
            )
        preferenceSaveTask =
            server.scheduler.runTaskTimer(
                this,
                Runnable { preferences.saveAsync() },
                settings.saveIntervalMinutes * 60L * 20L,
                settings.saveIntervalMinutes * 60L * 20L,
            )
        if (settings.trailDecay) decayScheduler = DecayScheduler(this, settings, trailService, decayPolicy)
    }

    private fun cancelRuntimeTasks() {
        speedTask?.cancel()
        speedTask = null
        preferenceSaveTask?.cancel()
        preferenceSaveTask = null
        decayScheduler?.close()
        decayScheduler = null
    }

    private fun restoreAllSpeeds() {
        val online = server.onlinePlayers.associate { it.uniqueId to BukkitWalkSpeedTarget(it) }
        speedController.restoreAll(online)
    }

    private fun checkProtection(player: Player, block: Block): Boolean {
        if (protection.canCreate(player, block.location)) return true
        if (settings.sendDenyMessage && denyMessageCooldown.add(player.uniqueId)) {
            message(player, "messages.cantCreateTrails")
            server.scheduler.runTaskLater(
                this,
                Runnable { denyMessageCooldown.remove(player.uniqueId) },
                settings.denyMessageIntervalSeconds * 20L,
            )
        }
        return false
    }

    private fun ensureBundledResources() {
        listOf("lang/en-US.yml", "lang/ru-RU.yml", "lang/zh-CN.yml", "players.yml").forEach(::ensureBundledResource)
    }

    private fun ensureBundledResource(resource: String) {
        val target = dataFolder.toPath().resolve(resource)
        if (!Files.exists(target)) saveResource(resource, false)
    }

    private fun syncLocaleCommand() {
        val requested = settings.commandAlias ?: "trails"
        if (requested == "trails" || requested == "paths") {
            localeCommand?.unregister(server.commandMap)
            localeCommand = null
            return
        }
        if (localeCommand?.name == requested) return

        val candidate = LocaleCommand(requested, commandHandler)
        if (!server.commandMap.register("trails", candidate)) {
            candidate.unregister(server.commandMap)
            localeCommand?.unregister(server.commandMap)
            localeCommand = null
            logger.warning("Locale command '$requested' conflicts with another plugin and was not registered")
            return
        }
        localeCommand?.unregister(server.commandMap)
        localeCommand = candidate
    }

    private fun integrationSummary(): String {
        val manager = server.pluginManager
        fun state(enabled: Boolean, vararg names: String): String =
            when {
                !enabled -> "disabled"
                names.any(manager::isPluginEnabled) -> "active"
                else -> "missing"
            }
        val configured = settings.integrations
        return listOf(
            "Towny=${state(configured.townyEnabled, "Towny")}",
            "Lands=${state(configured.landsEnabled, "Lands")}",
            "GriefPrevention=${state(configured.griefPreventionEnabled, "GriefPrevention")}",
            "WorldGuard=${state(configured.worldGuardEnabled, "WorldGuard")}",
            "CoreProtect=${state(configured.coreProtectChanges, "CoreProtect")}",
            "LogBlock=${state(configured.logBlockChanges, "LogBlock")}",
            "PlayerPlot=${state(configured.playerPlotEnabled, "PlayerPlot")}",
            "RedProtect=${state(configured.redProtectEnabled, "RedProtect")}",
            "Residence=${state(configured.residenceEnabled, "Residence")}",
            "Dynmap=${state(configured.dynmapRender, "dynmap", "Dynmap")}",
            "PlaceholderAPI=${state(true, "PlaceholderAPI")}",
        ).joinToString(", ")
    }

    private fun org.bukkit.plugin.PluginManager.enabledPlugin(vararg names: String): Plugin? =
        names.firstNotNullOfOrNull { name -> getPlugin(name)?.takeIf(Plugin::isEnabled) }

    private class LocaleCommand(
        name: String,
        private val delegate: TrailsCommand,
    ) : Command(name) {
        init {
            description = "Configure trail creation and trail speed boost."
            usage = "/$name [on|off|boost|show|reload|status|validate|give]"
        }

        override fun execute(
            sender: CommandSender,
            commandLabel: String,
            args: Array<out String>,
        ): Boolean = delegate.onCommand(sender, this, commandLabel, args)

        override fun tabComplete(
            sender: CommandSender,
            alias: String,
            args: Array<out String>,
        ): List<String> = delegate.onTabComplete(sender, this, alias, args)
    }

    private companion object {
        const val BSTATS_PLUGIN_ID = 16930
    }
}
