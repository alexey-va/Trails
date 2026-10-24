package ru.ruscrafting.trails

import net.coreprotect.CoreProtect
import net.kyori.adventure.text.Component
import org.bstats.bukkit.Metrics
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.whenCompleteSync
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.api.ArcNativeCleanupService
import ru.ruscrafting.trails.bukkit.RoadManager
import ru.ruscrafting.trails.bukkit.RoadResult
import ru.ruscrafting.trails.bukkit.RuntimeTaskSupervisor
import ru.ruscrafting.trails.bukkit.TrailsCommand
import ru.ruscrafting.trails.bukkit.TrailsListener
import ru.ruscrafting.trails.bukkit.TrailToolKind
import ru.ruscrafting.trails.bukkit.TrailFeedback
import ru.ruscrafting.trails.bukkit.TrailVisibilityController
import ru.ruscrafting.trails.bukkit.trailMovementDirection
import ru.ruscrafting.trails.config.LocaleService
import ru.ruscrafting.trails.config.RoadSettings
import ru.ruscrafting.trails.config.TrailsConfiguration
import ru.ruscrafting.trails.config.TrailsSettings
import ru.ruscrafting.trails.config.TrailsSettingsLoader
import ru.ruscrafting.trails.domain.TrailCatalog
import ru.ruscrafting.trails.integration.BukkitEventProtection
import ru.ruscrafting.trails.integration.CoreProtectObserver
import ru.ruscrafting.trails.integration.TrailsPlaceholderExpansion
import ru.ruscrafting.trails.service.BlockChangeObserver
import ru.ruscrafting.trails.service.TrailService
import ru.ruscrafting.trails.service.TrailMovementContext
import ru.ruscrafting.trails.service.TrailWalkResult
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import ru.ruscrafting.trails.api.TrailsFootTrafficActor
import ru.ruscrafting.trails.api.TrailsFootTrafficService.StepResult
import ru.ruscrafting.trails.storage.ChunkPersistentTrailStore
import ru.ruscrafting.trails.storage.PlayerPreferencesStore
import ru.ruscrafting.trails.storage.TrailBlockState
import ru.ruscrafting.trails.storage.TrailChunkRecoveryJournal
import ru.ruscrafting.trails.storage.RoadHistoryStore
import ru.ruscrafting.trails.storage.RoadCommitRecord
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.LongAdder

open class TrailsPlugin : JavaPlugin(), TrailsFootTrafficService {
    override fun speedMultiplier(ground: org.bukkit.block.Block): Double {
        check(Bukkit.isPrimaryThread()) { "Trails surface speed requires the server thread" }
        if (!ready || !settings.worldEnabled(ground.world.name) ||
            !ground.world.isChunkLoaded(ground.x shr 4, ground.z shr 4) || !blockStore.isSettled(ground.chunk)) return 1.0
        return trailService.speedMultiplier(ground, settings.speedBoostOnlyTrails)
    }

    override fun registerSurface(ground: Block): CompletionStage<Boolean> {
        check(Bukkit.isPrimaryThread()) { "Trails surface registration requires the server thread" }
        if (!ready || !settings.worldEnabled(ground.world.name) ||
            !ground.world.isChunkLoaded(ground.x shr 4, ground.z shr 4)) return CompletableFuture.completedFuture(false)
        val lifecycle = pluginRuntime ?: return CompletableFuture.completedFuture(false)
        val store = blockStore
        val service = trailService
        val configuration = settings
        val before = ground.blockData.asString
        fun process(): Boolean = ready && pluginRuntime === lifecycle && blockStore === store &&
            trailService === service && settings === configuration &&
            ground.world.isChunkLoaded(ground.x shr 4, ground.z shr 4) && ground.blockData.asString == before &&
            service.registerSurface(ground)
        if (store.isSettled(ground.chunk)) return try { CompletableFuture.completedFuture(process()) }
            catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        val result = CompletableFuture<Boolean>()
        val pending = store.whenReady(ground.chunk, store.mutationGuard(ground)) { result.complete(process()) }
        pending.whenComplete { _, failure ->
            if (failure == null) result.complete(false) else result.completeExceptionally(failure)
        }
        return result
    }

    @Volatile
    lateinit var settings: TrailsSettings
        private set
    private lateinit var locale: LocaleService
    private lateinit var configuration: TrailsConfiguration
    private lateinit var preferences: PlayerPreferencesStore
    private lateinit var blockStore: ChunkPersistentTrailStore
    private lateinit var trailService: TrailService
    private lateinit var trailFeedback: TrailFeedback
    @Volatile
    private lateinit var roadSettings: RoadSettings
    private lateinit var roadManager: RoadManager
    private lateinit var trailVisibility: TrailVisibilityController
    private val bukkitEventProtection by lazy { BukkitEventProtection(server.pluginManager) }
    private var placeholderExpansion: TrailsPlaceholderExpansion? = null
    private val denyMessageCooldown = ConcurrentHashMap.newKeySet<UUID>()
    private lateinit var runtimeTasks: RuntimeTaskSupervisor
    private lateinit var commandHandler: TrailsCommand
    private var localeCommand: Command? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private lateinit var storage: BoundedAsyncExecutor
    private val storageResources = ConcurrentLinkedDeque<AutoCloseable>()
    private var nativeQueue: BudgetedTaskQueue? = null
    private var cleanupService: ArcNativeCleanupService? = null
    private var nativeCleanupTail = CompletableFuture.completedFuture<Void>(null)
    private var shutdownFailure: Throwable? = null
    private var reloadInFlight: CompletableFuture<Result<Unit>>? = null
    private val movements = java.util.concurrent.ConcurrentHashMap<UUID, CompletableFuture<Unit>>()
    private val movementVersions = java.util.concurrent.ConcurrentHashMap<UUID, Long>()
    private val movementSequence = java.util.concurrent.atomic.AtomicLong()
    private class Prepared(
        val configuration: TrailsConfiguration,
        val loaded: ru.ruscrafting.trails.config.TrailsConfigurationSnapshot,
        val preferences: PlayerPreferencesStore,
        val historyStore: RoadHistoryStore,
        val history: LinkedHashMap<UUID, RoadCommitRecord>,
        val recoveryJournal: TrailChunkRecoveryJournal,
    )
    private var ready = false
    internal fun isReady(): Boolean = ready
    internal fun drained(): CompletableFuture<Unit> = shutdownFailure?.let { CompletableFuture.failedFuture(it) }
        ?: nativeCleanupTail.thenApply<Unit> { }
    internal fun tasks() = requireNotNull(pluginRuntime) { "Trails is closed" }.tasks
    internal fun nativeWork() = requireNotNull(nativeQueue) { "Trails native work is closed" }
    internal fun nativeCleanup() = requireNotNull(cleanupService) { "ArcCore native cleanup service is unavailable" }
    private val toolKindKey by lazy { NamespacedKey(this, "trail_tool_kind") }
    private val movementSamples = LongAdder()
    private val movementProcessingNanos = LongAdder()
    private val protectionVetoes = LongAdder()

    private fun prepare(): Prepared {
        saveDefaultConfig()
        val configuration =
            TrailsConfiguration(
                dataFolder = dataFolder.toPath(),
                ensureBundledResource = ::ensureBundledResource,
                migrationReporter = { migration ->
                    val details =
                        buildList {
                            migration.backup?.let { add("backup=${it.fileName}") }
                            if (migration.addedDefaults.isNotEmpty()) add("defaults=${migration.addedDefaults.size}")
                        }.joinToString(", ").ifEmpty { "no extra details" }
                    logger.info("Migrated config.yml to schema v${TrailsSettingsLoader.CONFIG_VERSION}; $details")
                },
            )
        val loaded = configuration.load(reload = false)
        val preferences = PlayerPreferencesStore(dataFolder.toPath()) { error ->
            logger.severe("Could not save players.yml asynchronously: ${error.message}")
        }.also(storageResources::add)
        val historyStore = RoadHistoryStore(dataFolder.toPath())
        val history = runCatching(historyStore::load).getOrElse { error ->
            logger.warning("Could not load road-history.yml; undo history starts empty: ${error.message}")
            linkedMapOf()
        }
        return Prepared(configuration, loaded, preferences, historyStore, history, TrailChunkRecoveryJournal(dataFolder.toPath()))
    }

    override fun onEnable() {
        if (!nativeCleanupTail.isDone || nativeCleanupTail.isCompletedExceptionally) {
            logger.severe("Previous Trails native cleanup is still pending or failed")
            server.pluginManager.disablePlugin(this)
            return
        }
        val lifecycle = PaperPluginRuntime(this, "trails", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            shutdownFailure = null
            cleanupService = requireNotNull(server.servicesManager.load(ArcNativeCleanupService::class.java)) {
                "ArcCore native cleanup service is unavailable"
            }
            storage = BoundedAsyncExecutor("Trails-storage")
            nativeQueue = BudgetedTaskQueue(lifecycle.tasks)
            finishEnable(lifecycle, storage.submit { prepare() }.get())
        } catch (failure: Throwable) { failStartup(lifecycle, failure) }
    }

    private fun finishEnable(lifecycle: PaperPluginRuntime, prepared: Prepared) {
        try {
            configuration = prepared.configuration
            preferences = prepared.preferences
            runtimeTasks = lifecycle.own(RuntimeTaskSupervisor(this, preferences, nativeWork(), storage))
            blockStore = ChunkPersistentTrailStore(this, nativeWork(), prepared.recoveryJournal,
                onForeignClear = { world, chunk, position -> trailService.forgetActivity(world, chunk, position) })
            val activeStore = blockStore
            lifecycle.own(activeStore)
            server.pluginManager.registerEvents(blockStore, this)
            lifecycle.tasks.runTimer(STORAGE_FLUSH_TICKS, STORAGE_FLUSH_TICKS) { blockStore.flushDirty() }
            val loaded = prepared.loaded
            applyRuntime(loaded.settings, loaded.locale)
            trailVisibility = TrailVisibilityController(server, nativeWork(),
                { chunk, after -> trailService.nextTrackedBlock(chunk, after) }, activeStore::prepare)
            val activeVisibility = trailVisibility
            lifecycle.own(activeVisibility)
            roadSettings = loaded.roads
            roadManager = RoadManager(this, loaded.roads, historyStore = prepared.historyStore, initialHistory = prepared.history)
            val activeRoadManager = roadManager
            lifecycle.own(AutoCloseable { activeRoadManager.close() })
            lifecycle.tasks.runTimer(5L, 5L, roadManager::tick)
            runCatching { Metrics(this, BSTATS_PLUGIN_ID) }
                .onFailure { logger.warning("bStats could not initialize and will be skipped: ${it.message}") }

            server.pluginManager.registerEvents(TrailsListener(this), this)
            val command = checkNotNull(getCommand("trails")) { "Command 'trails' is missing from plugin.yml" }
            commandHandler = TrailsCommand(this)
            command.setExecutor(commandHandler)
            command.tabCompleter = commandHandler
            syncLocaleCommand()
            lifecycle.own(AutoCloseable {
                localeCommand?.unregister(server.commandMap)
                localeCommand = null
            })

            if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
                placeholderExpansion = TrailsPlaceholderExpansion(this).also {
                    check(it.register()) { "Could not register PlaceholderAPI expansion" }
                    lifecycle.own(AutoCloseable { it.unregister() })
                }
            }
            lifecycle.registerHealth("runtime") {
                RuntimeHealthContribution(
                    schemas = mapOf(
                        "config" to settings.configVersion,
                        "trails" to settings.trailsConfigVersion,
                        "roads" to roadSettings.configVersion,
                    ),
                )
            }
            lifecycle.registerHealth("storage", blockStore::healthContribution)
            server.servicesManager.register(TrailsFootTrafficService::class.java, this, this, ServicePriority.Normal)
            lifecycle.own(AutoCloseable { server.servicesManager.unregister(TrailsFootTrafficService::class.java, this) })
            lifecycle.ready("trails" to settings.definitions.size, "roads" to roadSettings.profiles.size)
            ready = true
            lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info("Trails ${pluginMeta.version} enabled with ${settings.definitions.size} trail definitions")
        } catch (failure: Throwable) { failStartup(lifecycle, failure) }
    }

    private fun failStartup(lifecycle: PaperPluginRuntime, failure: Throwable) {
        runCatching { lifecycle.health.markDown(); lifecycle.emitHealth() }
        logger.severe("Trails failed closed during startup: ${failure.javaClass.simpleName}: ${failure.message}")
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        ready = false
        val lifecycle = pluginRuntime ?: return
        pluginRuntime = null
        val queue = nativeQueue.also { nativeQueue = null }
        val store = if (::blockStore.isInitialized) blockStore else null
        val roads = if (::roadManager.isInitialized) roadManager else null
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { queue?.close() }
        attempt { lifecycle.close() }
        if (roads != null) nativeCleanupTail = runCatching { roads.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        val chunks = runCatching { store?.closeAsync() ?: CompletableFuture.completedFuture(Unit) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        // Chunk/prefs futures are IO-only after cancellation; road cleanup needs future Main ticks.
        attempt {
            if (::storage.isInitialized) storage.closeAsync {
                var terminalFailure: Throwable? = null
                fun closeResource(close: () -> Unit) {
                    try { close() } catch (error: Throwable) {
                        if (terminalFailure == null) terminalFailure = error else if (terminalFailure !== error) terminalFailure.addSuppressed(error)
                    }
                }
                closeResource { chunks.join() }
                while (true) {
                    val resource = storageResources.pollLast() ?: break
                    closeResource { if (resource is PlayerPreferencesStore) resource.closeAsync().join() else resource.close() }
                }
                terminalFailure?.let { throw it }
            }.join()
        }
        shutdownFailure = failure
        failure?.let { logger.severe("Could not close Trails storage: ${it.javaClass.simpleName}: ${it.message}") }
        cleanupService = null
        reloadInFlight = null
        movements.clear()
        movementVersions.clear()
        placeholderExpansion = null
        localeCommand = null
    }

    fun reloadTrails(): CompletableFuture<Result<Unit>> {
        val lifecycle = pluginRuntime ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Trails is closed")))
        reloadInFlight?.takeUnless { it.isDone }?.let { return it.copy() }
        val activeConfiguration = configuration
        val activeRoadManager = roadManager
        val queue = nativeWork()
        val attempt = storage.submit { activeConfiguration.load(reload = true) }.thenCompose { loaded ->
            queue.call({ pluginRuntime === lifecycle && ready }) {
                applyRuntime(loaded.settings, loaded.locale)
                roadSettings = loaded.roads
                activeRoadManager.reconfigure(loaded.roads)
                if (::commandHandler.isInitialized) syncLocaleCommand()
                logger.info("Trails configuration reloaded")
            }
        }.handle { _, failure ->
            if (failure == null) Result.success(Unit) else Result.failure<Unit>(failure.cause ?: failure).also {
                logger.severe("Trails reload rejected; previous runtime remains active: ${it.exceptionOrNull()?.message}")
            }
        }
        reloadInFlight = attempt
        return attempt.copy()
    }

    fun validateConfiguration(): CompletableFuture<Result<TrailsSettings>> {
        if (pluginRuntime == null) return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Trails is closed")))
        val activeConfiguration = configuration
        return storage.submit { runCatching { activeConfiguration.load(reload = true).settings } }
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
        movementDirection: BlockFace? = null,
    ) {
        if (!ready) return
        val lifecycle = pluginRuntime ?: return
        val store = blockStore
        val service = trailService
        val configuration = settings
        val playerId = player.uniqueId
        val worldId = player.world.uid
        val sprinting = player.isSprinting
        val sneaking = player.isSneaking
        val version = movementSequence.incrementAndGet()
        movementVersions[playerId] = version
        val previous = movements[playerId]
        if (store.isSettled(block.chunk) && (previous == null || previous.isDone)) {
            measureMovement { handleMovementMeasured(player, block, createTrail, movementDirection, true, sprinting, sneaking) }
            movementVersions.remove(playerId, version)
            return
        }
        val guard = store.mutationGuard(block)
        val pending = store.whenReady(block.chunk, guard, previous) {
            if (ready && pluginRuntime === lifecycle && blockStore === store && trailService === service &&
                settings === configuration && player.isOnline && player.world.uid == worldId) {
                measureMovement { handleMovementMeasured(player, block, createTrail, movementDirection,
                    movementVersions[playerId] == version, sprinting, sneaking) }
            }
        }
        movements[playerId] = pending
        pending.whenComplete { _, _ ->
            movements.remove(playerId, pending)
            movementVersions.remove(playerId, version)
        }
    }

    override fun recordStep(actualNpc: Entity, authority: Player, from: Location, to: Location): CompletionStage<StepResult> {
        return recordStep(object : TrailsFootTrafficActor {
            override fun uniqueId(): UUID = actualNpc.uniqueId
            override fun location(): Location = actualNpc.location
            override fun isValid(): Boolean = actualNpc is LivingEntity && actualNpc.isValid &&
                !actualNpc.isDead && actualNpc.hasMetadata("NPC")
        }, authority, from, to)
    }

    override fun recordStep(actualNpc: TrailsFootTrafficActor, authority: Player, from: Location, to: Location): CompletionStage<StepResult> {
        check(Bukkit.isPrimaryThread()) { "Trails NPC footsteps must run on the server thread" }
        if (!ready) return CompletableFuture.completedFuture(StepResult.IGNORED)
        val origin = from.clone()
        val destination = to.clone()
        if (!actualNpc.isValid) return CompletableFuture.completedFuture(StepResult.IGNORED)
        val position = actualNpc.location()
        val world = position.world ?: return CompletableFuture.completedFuture(StepResult.IGNORED)
        if (!authority.isOnline || authority.hasMetadata("NPC") || authority.world.uid != world.uid ||
            origin.world?.uid != world.uid || destination.world?.uid != world.uid ||
            listOf(origin.x, origin.y, origin.z, destination.x, destination.y, destination.z,
                position.x, position.y, position.z).any { !it.isFinite() } ||
            origin.blockX == destination.blockX && origin.blockZ == destination.blockZ ||
            origin.distanceSquared(destination) > 2.25 || position.distanceSquared(destination) > 0.01 ||
            !world.isChunkLoaded(origin.blockX shr 4, origin.blockZ shr 4)) {
            return CompletableFuture.completedFuture(StepResult.IGNORED)
        }
        val lifecycle = pluginRuntime ?: return CompletableFuture.completedFuture(StepResult.IGNORED)
        val store = blockStore
        val service = trailService
        val configuration = settings
        if (!configuration.worldEnabled(world.name) ||
            configuration.usePermissionForTrails && !authority.hasPermission("trails.create-trails"))
            return CompletableFuture.completedFuture(StepResult.IGNORED)
        val direction = trailMovementDirection(origin, destination)
        val block = origin.subtract(0.0, 0.1, 0.0).block
        fun process(): StepResult {
            if (!ready || pluginRuntime !== lifecycle || blockStore !== store || trailService !== service ||
                settings !== configuration || !actualNpc.isValid ||
                actualNpc.location().world?.uid != world.uid || !authority.isOnline || authority.hasMetadata("NPC") ||
                authority.world.uid != world.uid || !configuration.worldEnabled(world.name) ||
                configuration.usePermissionForTrails && !authority.hasPermission("trails.create-trails")) return StepResult.IGNORED
            return when (walkTrail(authority, service.movementContext(block), direction, sprinting = false, notifyDenial = false)) {
                TrailWalkResult.NoChange -> StepResult.NO_CHANGE
                is TrailWalkResult.Counted, is TrailWalkResult.PopularCounted -> StepResult.COUNTED
                is TrailWalkResult.Advanced, is TrailWalkResult.Widened -> StepResult.CHANGED
            }
        }
        val npcId = actualNpc.uniqueId()
        val previous = movements[npcId]
        if (store.isSettled(block.chunk) && (previous == null || previous.isDone)) {
            return try { CompletableFuture.completedFuture(measureMovement(::process)) }
            catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        }
        val result = CompletableFuture<StepResult>()
        val pending = store.whenReady(block.chunk, store.mutationGuard(block), previous) {
            result.complete(measureMovement(::process))
        }
        movements[npcId] = pending
        pending.whenComplete { _, failure ->
            movements.remove(npcId, pending)
            if (failure == null) result.complete(StepResult.IGNORED) else result.completeExceptionally(failure)
        }
        return result
    }

    private inline fun <T> measureMovement(operation: () -> T): T {
        val startedAt = System.nanoTime()
        return try { operation() } finally {
            movementSamples.increment()
            movementProcessingNanos.add(System.nanoTime() - startedAt)
        }
    }

    private fun handleMovementMeasured(
        player: Player,
        block: Block,
        createTrail: Boolean,
        movementDirection: BlockFace?,
        applyBoost: Boolean = true,
        sprinting: Boolean = player.isSprinting,
        sneaking: Boolean = player.isSneaking,
    ) {
        if (!settings.worldEnabled(player.world.name)) {
            if (applyBoost) restoreSpeed(player)
            return
        }
        val movementContext by lazy(LazyThreadSafetyMode.NONE) { trailService.movementContext(block) }
        val canBoost =
            if (settings.usePermissionForBoost) player.hasPermission("trails.boost") else boostEnabled(player.uniqueId)
        if (canBoost && applyBoost) {
            val multiplier = trailService.speedMultiplier(movementContext, settings.speedBoostOnlyTrails)
            runtimeTasks.targetSpeed(
                player,
                multiplier,
                immediate = multiplier == 1.0 && settings.immediatelyRemoveBoost,
            )
        } else if (applyBoost) {
            restoreSpeed(player)
        }

        if (!createTrail) return
        if (settings.sneakBypass && sneaking) return
        if (!trailService.canAffect(movementContext)) return
        val canCreate =
            if (settings.usePermissionForTrails) player.hasPermission("trails.create-trails") else trailsEnabled(player.uniqueId)
        if (!canCreate) return
        val result = walkTrail(player, movementContext, movementDirection, sprinting, notifyDenial = true)
        trailFeedback.onWalk(player, block, result)
    }

    private fun walkTrail(authority: Player, context: TrailMovementContext, direction: BlockFace?, sprinting: Boolean,
        notifyDenial: Boolean): TrailWalkResult =
        trailService.walk(
            player = authority,
            context = context,
            sprintModifier = settings.runModifier,
            popularThreshold = settings.popularRoutes.terminalWalks.takeIf { settings.popularRoutes.enabled },
            wideningDirection = direction,
            sprinting = sprinting,
        ) { targetBlock, target -> checkEventProtection(authority, targetBlock, target, notifyDenial) }

    fun captureRoadMovement(
        player: Player,
        location: Location,
    ): Boolean = ready && roadManager.capture(player, location)

    fun roadProfiles(): Collection<String> = roadManager.profiles()

    fun showRoadProfiles(
        sender: CommandSender,
        requestedProfile: String?,
    ): Boolean {
        val profiles =
            if (requestedProfile == null) {
                roadSettings.profiles.values.toList()
            } else {
                listOfNotNull(roadSettings.profiles[requestedProfile.lowercase()])
            }
        if (profiles.isEmpty()) return false
        if (requestedProfile == null) {
            message(sender, "messages.roadProfilesHeader", mapOf("%count%" to profiles.size.toString()))
        }
        profiles.forEach { profile ->
            val descriptionPath = "roadProfiles.${profile.name}"
            message(
                sender,
                if (locale.exists(descriptionPath)) descriptionPath else "messages.roadProfileCustom",
                mapOf(
                    "%profile%" to profile.name,
                    "%width%" to profile.width.toString(),
                    "%material-count%" to profile.laneMaterials.size.toString(),
                    "%pattern-count%" to profile.decorationPatterns.size.toString(),
                ),
            )
        }
        return true
    }

    fun roadStart(
        player: Player,
        profile: String,
    ): RoadResult = roadManager.start(player, profile)

    fun roadCommit(player: Player, stillAuthorized: () -> Boolean = { true }): CompletableFuture<RoadResult> =
        roadManager.commit(player, stillAuthorized)

    fun roadCancel(player: Player): RoadResult = roadManager.cancel(player)

    fun roadUndo(player: Player, stillAuthorized: () -> Boolean = { true }): CompletableFuture<RoadResult> =
        roadManager.undo(player, stillAuthorized)

    internal fun awaitTrailData(chunk: Chunk): CompletableFuture<Unit> = blockStore.whenReady(chunk) {}

    fun roadStatus(player: Player): RoadResult = roadManager.status(player)

    fun discardRoadSession(player: Player) = roadManager.discard(player)

    fun canRoadChange(
        player: Player,
        block: Block,
        target: Material,
    ): Boolean =
        bypassesRoadProtection(player) ||
            checkProtectionResult(
                player,
                bukkitEventProtection.canChange(player, block, target, settings.integrations.blockPlaceCompatibilityEvent),
            )

    fun bypassesRoadProtection(player: Player): Boolean =
        player.hasPermission("trails.roads.bypass-protection")

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

    fun forceTrail(player: Player, block: Block, stillAuthorized: () -> Boolean = { true }) {
        if (!ready || !stillAuthorized()) return
        val lifecycle = pluginRuntime ?: return
        val store = blockStore
        val service = trailService
        val playerWorld = player.world.uid
        if (!store.isSettled(block.chunk)) {
            val guard = store.mutationGuard(block)
            store.whenReady(block.chunk, guard) {
                if (ready && pluginRuntime === lifecycle && blockStore === store && trailService === service &&
                    player.isOnline && player.world.uid == playerWorld && stillAuthorized()) forceTrailPrepared(player, block)
            }
            return
        }
        forceTrailPrepared(player, block)
    }

    private fun forceTrailPrepared(player: Player, block: Block) {
        if (!settings.worldEnabled(block.world.name)) return
        val context = trailService.movementContext(block)
        if (!trailService.canAffect(context)) return
        val bypass = player.hasPermission("trails.trail-tool.bypass-protection")
        val result =
            trailService.walk(player, context, settings.runModifier, forced = true) { targetBlock, target ->
                bypass || checkEventProtection(player, targetBlock, target)
            }
        trailFeedback.onWalk(player, block, result)
    }

    fun decayBlock(block: Block): Boolean =
        ready && blockStore.isSettled(block.chunk) && settings.worldEnabled(block.world.name) &&
            trailService.decay(block, settings.stepDecayFraction) { target -> bukkitEventProtection.canDecay(block, target) }

    fun clearTrailData(block: Block): Boolean = clearTrailData(listOf(block)) { it }

    fun isProtectionProbe(event: org.bukkit.event.Event): Boolean = bukkitEventProtection.isProbe(event)

    fun <T : Any> clearTrailData(blocks: Collection<T>, blockOf: (T) -> Block): Boolean {
        if (!ready) return false
        return try {
            blockStore.clearForeign(blocks, blockOf)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }

    fun trailDataReady(block: Block): Boolean = ready && block.world.isChunkLoaded(block.x shr 4, block.z shr 4) && blockStore.isSettled(block.chunk)

    fun prepareTrailData(block: Block): CompletableFuture<Unit> =
        if (ready && block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) blockStore.prepare(block.chunk)
        else CompletableFuture.failedFuture(IllegalStateException("trail chunk is not loaded or runtime is not ready"))

    fun forgetTrailActivity(chunk: Chunk) = trailService.forgetActivity(chunk)

    internal fun lastTrailActivity(block: Block): Long? = trailService.lastActivityMillis(block)

    fun moveTrailData(
        blocks: Collection<Block>,
        direction: BlockFace,
    ): Boolean {
        if (!canMoveTrailData(blocks, direction)) return false
        trailService.move(blocks, direction)
        return true
    }

    /**
     * The configured Purpur 1.21.11 push graph has 12 moved blocks: at most 637 event references,
     * 85 distinct moved/broken positions. This atomic pre-piston seam performs memory-only metadata
     * work. It imposes no event-size cap; a cold or pending chunk cancels the native event until warm.
     */
    fun canMoveTrailData(blocks: Collection<Block>, direction: BlockFace): Boolean {
        if (!ready) return false
        var settled = true
        blocks.distinct().forEach { source ->
            val target = source.getRelative(direction)
            if (!trailDataReady(source)) { prepareTrailData(source); settled = false }
            if (!trailDataReady(target)) { prepareTrailData(target); settled = false }
        }
        return settled
    }

    fun inspectTrail(block: Block): TrailBlockState? = trailService.inspect(block)

    fun showTrailInfo(player: Player, block: Block, stillAuthorized: () -> Boolean = { true }) {
        if (!ready || !stillAuthorized()) return
        val lifecycle = pluginRuntime ?: return
        val store = blockStore
        val service = trailService
        val playerWorld = player.world.uid
        if (!store.isSettled(block.chunk)) {
            store.whenReady(block.chunk) {
                if (ready && pluginRuntime === lifecycle && blockStore === store && trailService === service &&
                    player.isOnline && player.world.uid == playerWorld && stillAuthorized()) showTrailInfoPrepared(player, block)
            }
            return
        }
        showTrailInfoPrepared(player, block)
    }

    private fun showTrailInfoPrepared(player: Player, block: Block) {
        val inspection = trailService.inspection(block)
        if (!player.isSneaking) {
            trailFeedback.inspect(player, inspection)
            return
        }
        if (inspection == null) {
            message(player, "messages.trail-info-empty")
            return
        }
        message(
            player,
            "messages.trail-info",
            mapOf(
                "%walks%" to inspection.walks.toString(),
                "%required%" to (inspection.next?.let { inspection.stage.requiredWalks.toString() } ?: "—"),
                "%trail%" to inspection.stage.identity.serialize(),
            ),
        )
    }

    fun debugInspect(block: Block): String {
        val context = trailService.movementContext(block)
        val state = context.stored
        val inspection = trailService.inspection(block)
        val stage = inspection?.stage ?: context.stage
        val next = inspection?.next ?: stage?.let(trailService::next)
        return listOf(
            "TRAILS_DEBUG",
            "action=inspect",
            "world=${block.world.name}",
            "x=${block.x}",
            "y=${block.y}",
            "z=${block.z}",
            "material=${block.type.name}",
            "biome=${block.biome.key}",
            "tracked=${state != null}",
            "trail=${stage?.trailName ?: "-"}",
            "stage=${stage?.index ?: -1}",
            "walks=${state?.walks ?: 0}",
            "required=${next?.let { stage?.requiredWalks } ?: -1}",
            "next=${next?.material ?: "-"}",
            "terminal=${stage != null && next == null}",
            "decay_edge=${state != null && trailService.isDecayEdge(block)}",
        ).joinToString(" ")
    }

    fun debugPulse(
        player: Player,
        count: Int,
    ): String {
        require(count in 1..100) { "debug pulse count must be between 1 and 100" }
        val block = player.location.clone().subtract(0.0, 0.1, 0.0).block
        repeat(count) {
            handleMovement(player, block, createTrail = true, movementDirection = facingFromYaw(player.location.yaw))
        }
        return debugInspect(block).replaceFirst("action=inspect", "action=pulse pulses=$count")
    }

    fun debugDecay(block: Block): String {
        val changed = decayBlock(block)
        return debugInspect(block).replaceFirst("action=inspect", "action=decay changed=$changed")
    }

    fun debugStats(): String {
        var loadedChunks = 0
        var trackedBlocks = 0
        var terminalBlocks = 0
        var unresolvedBlocks = 0
        var scanTruncated = false
        val stages = sortedMapOf<String, Int>()
        val enabledWorlds = server.worlds.filter { settings.worldEnabled(it.name) }
        scan@ for (world in enabledWorlds) {
            for (chunk in world.loadedChunks) {
                loadedChunks++
                val remaining = DEBUG_STATS_MAX_TRACKED_BLOCKS - trackedBlocks
                val chunkBlocks = trailService.trackedBlocks(chunk, remaining + 1)
                if (chunkBlocks.size > remaining) scanTruncated = true
                for (block in chunkBlocks.take(remaining)) {
                    trackedBlocks++
                    val inspection = trailService.inspection(block)
                    if (inspection == null) {
                        unresolvedBlocks++
                    } else {
                        val key = inspection.stage.identity.serialize()
                        stages[key] = stages.getOrDefault(key, 0) + 1
                        if (inspection.next == null) terminalBlocks++
                    }
                }
                if (scanTruncated) break@scan
            }
        }
        val decay = runtimeTasks.decayStats()
        val samples = movementSamples.sum()
        val averageMicros = if (samples == 0L) 0L else movementProcessingNanos.sum() / samples / 1_000L
        val visibleStages = stages.entries.take(DEBUG_STATS_MAX_STAGE_GROUPS)
        val hiddenStageBlocks = stages.entries.drop(DEBUG_STATS_MAX_STAGE_GROUPS).sumOf { it.value }
        val stageSummary =
            buildList {
                visibleStages.mapTo(this) { (stage, count) -> "${stableDebugToken(stage)}:$count" }
                if (hiddenStageBlocks > 0) add("other:$hiddenStageBlocks")
            }.joinToString(",").ifEmpty { "-" }
        return listOf(
            "TRAILS_DEBUG",
            "action=stats",
            "enabled_worlds=${enabledWorlds.size}",
            "loaded_chunks=$loadedChunks",
            "tracked=$trackedBlocks",
            "scan_limit=$DEBUG_STATS_MAX_TRACKED_BLOCKS",
            "scan_truncated=$scanTruncated",
            "terminal=$terminalBlocks",
            "unresolved=$unresolvedBlocks",
            "decay_enabled=${decay.enabled}",
            "decay_last_ms=${decay.ranAtMillis}",
            "decay_sampled_chunks=${decay.sampledChunks}",
            "decay_idle=${decay.idleBlocks}",
            "decay_candidates=${decay.candidateBlocks}",
            "decay_changed=${decay.changedBlocks}",
            "decay_nearby_skipped=${decay.nearbySkipped}",
            "protection_vetoes=${protectionVetoes.sum()}",
            "movement_samples=$samples",
            "movement_avg_us=$averageMicros",
            "stages=$stageSummary",
        ).joinToString(" ")
    }

    private fun stableDebugToken(value: String): String =
        value.asSequence()
            .take(DEBUG_STATS_MAX_TOKEN_CHARS)
            .map { character -> if (character.isWhitespace()) '_' else character }
            .joinToString("")

    private fun facingFromYaw(yaw: Float): BlockFace {
        val normalized = ((yaw % 360F) + 360F) % 360F
        return when {
            normalized < 45F || normalized >= 315F -> BlockFace.SOUTH
            normalized < 135F -> BlockFace.WEST
            normalized < 225F -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
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
        val lifecycle = pluginRuntime ?: return
        if (!ready) return
        val selected = settings
        val viewer = player.uniqueId
        trailVisibility.show(viewer, radius, selected.particle) {
            pluginRuntime === lifecycle && ready && settings === selected && player.isOnline && player.hasPermission("trails.show")
        }.whenComplete { _, failure ->
            if (failure != null && pluginRuntime === lifecycle && ready && failure.cause !is java.util.concurrent.CancellationException)
                logger.fine("Trail visibility for $viewer ended: ${failure.message}")
        }
    }

    fun trailsEnabled(uuid: UUID): Boolean = preferences.get(uuid).trailsEnabled(settings.enabledByDefault)

    fun boostEnabled(uuid: UUID): Boolean = preferences.get(uuid).boostEnabled(settings.boostEnabledByDefault)

    fun setTrailsEnabled(uuid: UUID, enabled: Boolean) {
        val previouslyEnabled = trailsEnabled(uuid)
        preferences.setEnabled(uuid, enabled)
        if (enabled && !previouslyEnabled) ru.ruscrafting.trails.integration.ArcProductTelemetry.enabled(uuid)
    }

    fun setBoostEnabled(uuid: UUID, enabled: Boolean) = preferences.setBoost(uuid, enabled)

    fun restoreSpeed(player: Player) = runtimeTasks.restoreSpeed(player)

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

    private fun applyRuntime(newSettings: TrailsSettings, newLocale: LocaleService) {
        val newObserver = createObserver(newSettings)
        val newTrailService =
            TrailService(
                catalog = TrailCatalog(newSettings.definitions, newSettings.strictLinks),
                store = blockStore,
                observer = newObserver,
            )

        runtimeTasks.reconfigure(
            settings = newSettings,
            trailService = newTrailService,
            canDecay = { block, target ->
                bukkitEventProtection.canDecay(block, target).also { allowed ->
                    if (!allowed) protectionVetoes.increment()
                }
            },
        )
        settings = newSettings
        locale = newLocale
        trailService = newTrailService
        trailFeedback = TrailFeedback(newSettings.feedback, newLocale)
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

    private fun checkEventProtection(
        player: Player,
        block: Block,
        target: Material,
        notifyDenial: Boolean = true,
    ): Boolean =
        checkProtectionResult(
            player,
            bukkitEventProtection.canChange(
                player,
                block,
                target,
                settings.integrations.blockPlaceCompatibilityEvent,
            ),
            notifyDenial,
        )

    private fun checkProtectionResult(player: Player, allowed: Boolean, notifyDenial: Boolean = true): Boolean {
        if (allowed) return true
        protectionVetoes.increment()
        if (notifyDenial && settings.sendDenyMessage && denyMessageCooldown.add(player.uniqueId)) {
            message(player, "messages.cantCreateTrails")
            server.scheduler.runTaskLater(
                this,
                Runnable { denyMessageCooldown.remove(player.uniqueId) },
                settings.denyMessageIntervalSeconds * 20L,
            )
        }
        return false
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
            usage = "/$name [on|off|boost|show|reload|status|validate|give|build]"
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
        const val HEALTH_REPORT_TICKS = 1_200L
        const val STORAGE_FLUSH_TICKS = 1L
        const val DEBUG_STATS_MAX_TRACKED_BLOCKS = 8_192
        const val DEBUG_STATS_MAX_STAGE_GROUPS = 32
        const val DEBUG_STATS_MAX_TOKEN_CHARS = 64
    }
}
