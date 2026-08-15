package ru.ruscrafting.trails.storage

import ru.ruscrafting.trails.config.YamlConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerPreferencesStore(
    dataFolder: Path,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Trails-player-data").apply { isDaemon = true }
    },
    private val onSaveFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val config = YamlConfig(prepareLegacyFile(dataFolder), "players.yml")
    private val preferences = ConcurrentHashMap<UUID, PlayerPreferences>()

    init {
        load()
    }

    fun get(uuid: UUID): PlayerPreferences = preferences[uuid] ?: PlayerPreferences()

    fun setEnabled(uuid: UUID, enabled: Boolean) {
        preferences.compute(uuid) { _, current -> (current ?: PlayerPreferences()).copy(enabled = enabled) }
    }

    fun setBoost(uuid: UUID, boost: Boolean) {
        preferences.compute(uuid) { _, current -> (current ?: PlayerPreferences()).copy(boost = boost) }
    }

    fun saveAsync() {
        val snapshot = snapshot()
        executor.execute {
            runCatching { persist(snapshot) }.onFailure(onSaveFailure)
        }
    }

    fun saveNow() = persist(snapshot())

    internal fun snapshot(): Map<UUID, PlayerPreferences> = preferences.toMap()

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                "Timed out while saving Trails player preferences"
            }
        }
        saveNow()
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
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(10)

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
