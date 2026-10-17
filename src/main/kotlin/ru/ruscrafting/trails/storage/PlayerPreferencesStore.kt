package ru.ruscrafting.trails.storage

import ru.ruscrafting.trails.config.YamlConfig
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.persistence.CoalescingAsyncWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Construct/load on storage; native getters and mutations only access the in-memory map. */
class PlayerPreferencesStore(
    dataFolder: Path,
    private val executor: BoundedAsyncExecutor = BoundedAsyncExecutor("Trails-player-data"),
    private val onSaveFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val config = try { YamlConfig(prepareLegacyFile(dataFolder), "players.yml") }
        catch (failure: Throwable) { executor.close(); throw failure }
    private val preferences = ConcurrentHashMap<UUID, PlayerPreferences>()
    private val closed = AtomicBoolean()
    private val drain = CompletableFuture<Unit>()
    private val writer = CoalescingAsyncWriter<Unit> { executor.submit { persist(snapshot()) } }

    init {
        try { load() } catch (failure: Throwable) { executor.close(); throw failure }
    }

    fun get(uuid: UUID): PlayerPreferences = preferences[uuid] ?: PlayerPreferences()

    fun setEnabled(uuid: UUID, enabled: Boolean) {
        check(!closed.get()) { "Player preferences are closed" }
        preferences.compute(uuid) { _, current -> (current ?: PlayerPreferences()).copy(enabled = enabled) }
    }

    fun setBoost(uuid: UUID, boost: Boolean) {
        check(!closed.get()) { "Player preferences are closed" }
        preferences.compute(uuid) { _, current -> (current ?: PlayerPreferences()).copy(boost = boost) }
    }

    fun saveAsync() {
        if (closed.get()) return
        // The coalesced marker is constant-size; enumeration/sort/YAML stay on storage.
        writer.submit(Unit).whenComplete { _, failure ->
            if (failure != null) onSaveFailure(failure.cause ?: failure)
        }
    }

    /** Worker-only explicit snapshot; lifecycle shutdown uses closeAsync instead. */
    fun saveNow() = persist(snapshot())

    internal fun snapshot(): Map<UUID, PlayerPreferences> = preferences.toMap()

    override fun close() { closeAsync() }

    fun closeAsync(): CompletableFuture<Unit> {
        if (closed.compareAndSet(false, true)) {
            // A final latest snapshot may recover a failed periodic write. Wait for every
            // coalesced admission before sealing the worker's reserved terminal slot.
            writer.closeAsync().handle { _, _ -> Unit }.thenCompose {
                executor.closeAsync { persist(snapshot()) }
            }.whenComplete { _, failure ->
                if (failure == null) drain.complete(Unit) else drain.completeExceptionally(failure)
            }
        }
        return drain.copy()
    }

    private fun load() {
        val serialized = if (config.exists("players")) config.map<Any>("players") else emptyMap()
        serialized.forEach { (rawUuid, rawValue) ->
            val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return@forEach
            val values = rawValue as? Map<*, *> ?: return@forEach
            preferences[uuid] =
                PlayerPreferences(
                    enabled = values["enable"].asBooleanOrNull(),
                    boost = values["boost"].asBooleanOrNull(),
                )
        }
    }

    private fun persist(snapshot: Map<UUID, PlayerPreferences>) {
        val serialized =
            snapshot.entries.sortedBy { it.key.toString() }.associate { (uuid, value) ->
                uuid.toString() to
                    buildMap<String, Boolean> {
                        value.enabled?.let { put("enable", it) }
                        value.boost?.let { put("boost", it) }
                    }
            }
        config.setStructured("players", serialized)
        config.saveStrict()
    }

    private fun Any?.asBooleanOrNull(): Boolean? =
        when (this) {
            is Boolean -> this
            is Number -> toInt() == 1
            is String ->
                when (trim().lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> null
                }
            else -> null
        }

    companion object {
        private fun prepareLegacyFile(dataFolder: Path): Path {
            val path = dataFolder.resolve("players.yml")
            if (Files.exists(path)) {
                val hasYamlContent = Files.readAllLines(path).any { line -> line.isNotBlank() && !line.trimStart().startsWith('#') }
                if (!hasYamlContent) Files.writeString(path, "players: {}\n")
            }
            return dataFolder
        }
    }
}
