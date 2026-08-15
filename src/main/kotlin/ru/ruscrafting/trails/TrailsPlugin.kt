package ru.ruscrafting.trails

import net.coreprotect.CoreProtect
import net.kyori.adventure.text.Component
import org.bstats.bukkit.Metrics
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
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
import ru.ruscrafting.trails.bukkit.RoadManager
import ru.ruscrafting.trails.bukkit.RoadResult
import ru.ruscrafting.trails.bukkit.TrailsCommand
import ru.ruscrafting.trails.bukkit.TrailsListener
import ru.ruscrafting.trails.bukkit.TrailToolKind
import ru.ruscrafting.trails.config.LocaleService
import ru.ruscrafting.trails.config.LegacyConfigMigrator
import ru.ruscrafting.trails.config.RoadSettings
import ru.ruscrafting.trails.config.RoadSettingsLoader
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.config.TrailsSettingsLoader
import ru.ruscrafting.trails.config.YamlConfig
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.integration.BukkitEventProtection
import ru.ruscrafting.trails.integration.CoreProtectObserver
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
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
    private lateinit var roadsFile: YamlConfig
    private lateinit var preferences: PlayerPreferencesStore
    private lateinit var blockStore: CustomBlockTrailStore
    private lateinit var trailService: TrailService
    private lateinit var roadSettings: RoadSettings
    private lateinit var roadManager: RoadManager
    private val bukkitEventProtection by lazy { BukkitEventProtection(server.pluginManager) }
    private var placeholderExpansion: TrailsPlaceholderExpansion? = null
    private val speedController = SpeedController()
    private val denyMessageCooldown = ConcurrentHashMap.newKeySet<UUID>()
    private var speedTask: BukkitTask? = null
    private var preferenceSaveTask: BukkitTask? = null
    private var decayScheduler: DecayScheduler? = null
    private var roadMaintenanceTask: BukkitTask? = null
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
            logger.info("Migrated config.yml to schema v3; backup: ${migration.backup?.fileName}")
            configFile.reload()
        }
        ensureBundledResource("trails.yml")
        trailsFile = YamlConfig(dataFolder.toPath(), "trails.yml")
        ensureBundledResource("roads.yml")
        roadsFile = YamlConfig(dataFolder.toPath(), "roads.yml")
        loadLocale(loadSettings(reload = false))
    }

    override fun onEnable() {
        preferences =
            PlayerPreferencesStore(dataFolder.toPath()) { error ->
                logger.severe("Could not save players.yml asynchronously: ${error.message}")
            }
        blockStore = CustomBlockTrailStore(this)
        val loadedSettings = loadSettings(reload = true)
        val loadedRoadSettings = loadRoadSettings(reload = true)
        val loadedLocale = loadLocale(loadedSettings)
        applyRuntime(loadedSettings, loadedLocale)
        roadSettings = loadedRoadSettings
        roadManager = RoadManager(this, loadedRoadSettings)
        roadMaintenanceTask = server.scheduler.runTaskTimer(this, Runnable(roadManager::tick), 5L, 5L)
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
        logger.info("Trails ${pluginMeta.version} enabled with ${settings.definitions.size} trail definitions")
    }

    override fun onDisable() {
        restoreAllSpeeds()
        if (::roadManager.isInitialized) roadManager.close()
        roadMaintenanceTask?.cancel()
        roadMaintenanceTask = null
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
            val loadedRoadSettings = loadRoadSettings(reload = true)
            val loadedLocale = loadLocale(loadedSettings)
            applyRuntime(loadedSettings, loadedLocale)
            roadSettings = loadedRoadSettings
            roadManager.reconfigure(loadedRoadSettings)
            if (::commandHandler.isInitialized) syncLocaleCommand()
            logger.info("Trails configuration reloaded")
        }.onFailure { error ->
            logger.severe("Trails reload rejected; previous runtime remains active: ${error.message}")
        }

    fun validateConfiguration(): Result<TrailsSettings> =
        runCatching {
            val loadedSettings = loadSettings(reload = true)
            loadRoadSettings(reload = true)
            loadLocale(loadedSettings)
            loadedSettings
        }

    fun showStatus(sender: CommandSender) {
        val replacements =
            mapOf(
                "%version%" to pluginMeta.version,
                "%config-version%" to settings.configVersion.toString(),
                "%trails-version%" to settings.trailsConfigVersion.toString(),
                "%roads-version%" to roadSettings.configVersion.toString(),
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

    fun handleMovement(
        player: Player,
        block: Block,
        createTrail: Boolean = true,
    ) {
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

        if (!createTrail) return
        if (settings.sneakBypass && player.isSneaking) return
        if (!trailService.canAffect(block)) return
        val canCreate =
            if (settings.usePermissionForTrails) player.hasPermission("trails.create-trails") else trailsEnabled(player.uniqueId)
        if (!canCreate) return
        trailService.walk(player, block, settings.runModifier) { target ->
            checkEventProtection(player, block, target)
        }
    }

    fun captureRoadMovement(
        player: Player,
        location: Location,
    ): Boolean = roadManager.capture(player, location)

    fun roadProfiles(): Collection<String> = roadManager.profiles()

    fun roadStart(
        player: Player,
        profile: String,
    ): RoadResult = roadManager.start(player, profile)

    fun roadCommit(player: Player): RoadResult = roadManager.commit(player)

    fun roadCancel(player: Player): RoadResult = roadManager.cancel(player)

    fun roadUndo(player: Player): RoadResult = roadManager.undo(player)

    fun roadStatus(player: Player): RoadResult = roadManager.status(player)

    fun discardRoadSession(player: Player) = roadManager.discard(player)

    fun canRoadChange(
        player: Player,
        block: Block,
        target: Material,
    ): Boolean =
        checkProtectionResult(
            player,
            bukkitEventProtection.canChange(player, block, target, settings.integrations.blockPlaceCompatibilityEvent),
        )

    fun placeRoad(
        actor: String,
        block: Block,
        after: BlockData,
    ): TrailBlockState? = trailService.placeRoad(actor, block, after)

    fun restoreRoad(
        actor: String,
        block: Block,
        before: BlockData,
        previous: TrailBlockState?,
    ) = trailService.restoreRoad(actor, block, before, previous)

    fun forceTrail(player: Player, block: Block) {
        if (!settings.worldEnabled(block.world.name) || !trailService.canAffect(block)) return
        val bypass = player.hasPermission("trails.trail-tool.bypass-protection")
        trailService.walk(player, block, settings.runModifier, forced = true) { target ->
            bypass || checkEventProtection(player, block, target)
        }
    }

    fun decayBlock(block: Block): Boolean =
        settings.worldEnabled(block.world.name) &&
            trailService.decay(block, settings.stepDecayFraction) { target -> bukkitEventProtection.canDecay(block, target) }

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

    private fun loadRoadSettings(reload: Boolean): RoadSettings {
        if (reload) roadsFile.reload()
        return RoadSettingsLoader.load(roadsFile)
    }

    private fun applyRuntime(newSettings: TrailsSettings, newLocale: LocaleService) {
        val newObserver = createObserver(newSettings)
        val newTrailService =
            TrailService(
                catalog = TrailCatalog(newSettings.definitions, newSettings.strictLinks),
                store = blockStore,
                observer = newObserver,
            )

        if (::settings.isInitialized) restoreAllSpeeds()
        cancelRuntimeTasks()
        settings = newSettings
        locale = newLocale
        trailService = newTrailService
        scheduleRuntimeTasks()
    }

    private fun createObserver(settings: TrailsSettings): BlockChangeObserver {
        val observers = mutableListOf<BlockChangeObserver>()
        if (settings.integrations.coreProtectChanges) {
            (server.pluginManager.enabledPlugin("CoreProtect") as? CoreProtect)?.let { coreProtect ->
                addObserver(observers, "CoreProtect") { CoreProtectObserver(coreProtect) }
            }
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
        if (settings.trailDecay) {
            decayScheduler =
                DecayScheduler(
                    this,
                    settings,
                    trailService,
                    canChange = { block, target -> bukkitEventProtection.canDecay(block, target) },
                )
        }
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

    private fun checkEventProtection(
        player: Player,
        block: Block,
        target: Material,
    ): Boolean =
        checkProtectionResult(
            player,
            bukkitEventProtection.canChange(
                player,
                block,
                target,
                settings.integrations.blockPlaceCompatibilityEvent,
            ),
        )

    private fun checkProtectionResult(player: Player, allowed: Boolean): Boolean {
        if (allowed) return true
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
            "Protection=events${if (configured.blockPlaceCompatibilityEvent) "+place" else ""}",
            "CoreProtect=${state(configured.coreProtectChanges, "CoreProtect")}",
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
            usage = "/$name [on|off|boost|show|reload|status|validate|give|road]"
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
