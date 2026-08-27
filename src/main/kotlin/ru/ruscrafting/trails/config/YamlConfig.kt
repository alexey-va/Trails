package ru.ruscrafting.trails.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class YamlConfig(
    private val folder: Path,
    private val relativePath: String,
) {
    private val lock = ReentrantReadWriteLock()
    private val bundledDefaults = loadBundledDefaults()
    private var yaml = YamlConfiguration()
    private var explicitPaths: Set<String> = emptySet()

    init {
        Files.createDirectories(folder)
        copyBundledDefault()
        reload()
    }

    fun reload() {
        lock.write {
            yaml = YamlConfiguration().also { configuration ->
                val file = folder.resolve(relativePath)
                if (Files.exists(file)) {
                    // Preserve literal dots while SnakeYAML turns maps into Bukkit
                    // sections, then restore normal dotted path lookups.
                    configuration.options().pathSeparator('\u0000')
                    configuration.options().parseComments(true)
                    configuration.load(file.toFile())
                    configuration.options().pathSeparator('.')
                }
                explicitPaths = configuration.getKeys(true)
                bundledDefaults?.let(configuration::setDefaults)
            }
        }
    }

    fun exists(path: String): Boolean = lock.read { yaml.contains(path) }

    fun existsExplicitly(path: String): Boolean = lock.read { path in explicitPaths }

    fun int(path: String, default: Int = 0): Int = number(path)?.toInt() ?: stringOrNull(path)?.toIntOrNull() ?: default

    fun long(path: String, default: Long = 0L): Long = number(path)?.toLong() ?: stringOrNull(path)?.toLongOrNull() ?: default

    fun double(path: String, default: Double = 0.0): Double = number(path)?.toDouble() ?: stringOrNull(path)?.toDoubleOrNull() ?: default

    fun boolean(path: String, default: Boolean = false): Boolean =
        lock.read {
            when (val value = yaml.get(path)) {
                is Boolean -> value
                is Number -> value.toInt() == 1
                is String ->
                    when (value.trim().lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> default
                    }
                else -> default
            }
        }

    fun string(path: String, default: String = ""): String = stringOrNull(path) ?: default

    fun stringOrNull(path: String): String? = lock.read { yaml.get(path)?.toString() }

    fun stringList(path: String, default: List<String> = emptyList()): List<String> = stringListOrNull(path) ?: default

    fun stringListOrNull(path: String): List<String>? =
        lock.read {
            when (val value = yaml.get(path)) {
                null -> null
                is Collection<*> -> value.mapNotNull { it?.toString() }
                else -> listOf(value.toString())
            }
        }

    fun keys(path: String): Set<String> = lock.read { yaml.getConfigurationSection(path)?.getKeys(false).orEmpty() }

    fun explicitKeys(path: String): Set<String> =
        lock.read {
            val prefix = "$path."
            explicitPaths
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix).substringBefore('.') }
                .filter(String::isNotEmpty)
                .toCollection(linkedSetOf())
        }

    fun value(path: String): Any? = lock.read { yaml.get(path).toYamlValue() }

    @Suppress("UNCHECKED_CAST")
    fun <T> map(path: String): Map<String, T> =
        lock.read {
            val section = yaml.getConfigurationSection(path) ?: return@read emptyMap()
            section.getValues(false).mapValues { (_, value) -> value.toYamlValue() as T }
        }

    fun setStructured(path: String, value: Any) {
        require(path.isNotBlank()) { "YAML path must not be blank" }
        validateStructured(value, path)
        lock.write { yaml.set(path, value) }
    }

    /**
     * Adds only bundled leaf values that are absent from the operator file.
     * Existing values and unknown keys remain untouched. The optional schema
     * version is advanced only from an older supported integer.
     */
    fun mergeBundledDefaults(
        versionPath: String? = null,
        targetVersion: Int? = null,
    ): Set<String> {
        val defaults = bundledDefaults ?: return emptySet()
        val added = linkedSetOf<String>()
        lock.write {
            defaults.getKeys(true)
                .filterNot(defaults::isConfigurationSection)
                .forEach { path ->
                    if (path !in explicitPaths && explicitScalarAncestor(path) == null) {
                        yaml.set(path, defaults.get(path).toYamlValue())
                        added += path
                    }
                }
            if (versionPath != null && targetVersion != null) {
                val current = yaml.get(versionPath).integerOrNull()
                if (current != null && current < targetVersion) {
                    yaml.set(versionPath, targetVersion)
                    added += versionPath
                }
            }
        }
        if (added.isNotEmpty()) {
            saveStrict()
            reload()
        }
        return added
    }

    fun saveStrict() {
        val serialized = lock.read { yaml.saveToString() }
        val target = folder.resolve(relativePath)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, serialized)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun number(path: String): Number? = lock.read { yaml.get(path) as? Number }

    private fun explicitScalarAncestor(path: String): String? {
        var ancestor = path.substringBeforeLast('.', missingDelimiterValue = "")
        while (ancestor.isNotEmpty()) {
            if (ancestor in explicitPaths && !yaml.isConfigurationSection(ancestor)) return ancestor
            ancestor = ancestor.substringBeforeLast('.', missingDelimiterValue = "")
        }
        return null
    }

    private fun Any?.integerOrNull(): Int? =
        when (this) {
            is Number -> toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
            is String -> trim().toIntOrNull()
            else -> null
        }

    private fun Any?.toYamlValue(): Any? =
        when (this) {
            is ConfigurationSection -> getValues(false).mapValues { (_, value) -> value.toYamlValue() }
            is Collection<*> -> map { it.toYamlValue() }
            else -> this
        }

    private fun validateStructured(value: Any?, path: String) {
        when (value) {
            null, is String, is Boolean, is Number -> Unit
            is Collection<*> -> value.forEachIndexed { index, child -> validateStructured(child, "$path[$index]") }
            is Map<*, *> ->
                value.forEach { (key, child) ->
                    require(key is String && key.isNotBlank()) { "YAML map key at $path must be a non-blank string" }
                    validateStructured(child, "$path.$key")
                }
            else -> throw IllegalArgumentException("Unsupported YAML value at $path: ${value.javaClass.name}")
        }
    }

    private fun copyBundledDefault() {
        val target = folder.resolve(relativePath)
        if (Files.exists(target)) return
        bundledResource()?.use { source ->
            Files.createDirectories(target.parent)
            Files.copy(source, target)
        }
    }

    private fun loadBundledDefaults(): YamlConfiguration? {
        return bundledResource()?.use { source ->
            InputStreamReader(source, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        }
    }

    private fun bundledResource() =
        javaClass.classLoader.getResourceAsStream(relativePath)
            ?: Thread.currentThread().contextClassLoader
                ?.takeUnless { it === javaClass.classLoader }
                ?.getResourceAsStream(relativePath)
}
