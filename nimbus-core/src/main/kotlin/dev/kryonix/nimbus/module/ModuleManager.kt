package dev.kryonix.nimbus.module

import dev.kryonix.nimbus.NimbusVersion
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ServiceLoader
import java.util.jar.JarFile
import java.util.Properties
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Discovers, loads, and manages [NimbusModule] instances from JAR files.
 *
 * Modules are loaded from the `modules/` directory using [ServiceLoader].
 * Each JAR must declare its module implementation in
 * `META-INF/services/dev.kryonix.nimbus.module.NimbusModule`.
 */
class ModuleManager(
    private val modulesDir: Path,
    private val context: ModuleContext,
    private val eventBus: EventBus
) {

    private val logger = LoggerFactory.getLogger(ModuleManager::class.java)
    private val modules = linkedMapOf<String, NimbusModule>()
    private val classLoaders = mutableListOf<URLClassLoader>()

    /** Load all module JARs from [modulesDir]. */
    fun loadAll() {
        if (!modulesDir.exists() || !modulesDir.isDirectory()) {
            logger.debug("Modules directory does not exist: {}", modulesDir)
            return
        }

        val jars = modulesDir.listDirectoryEntries("*.jar")
        if (jars.isEmpty()) {
            logger.info("No modules found in {}", modulesDir)
            return
        }

        logger.info("Found {} module JAR(s) in {}", jars.size, modulesDir)

        // Pre-read all module metadata for dependency resolution
        val metadataByJar = mutableMapOf<Path, ModuleInfo>()
        for (jar in jars) {
            val info = readModuleProperties(jar)
            if (info != null) metadataByJar[jar] = info
        }

        // Check version compatibility before loading
        val nimbusVersion = parseVersion(NimbusVersion.version)
        for ((jar, info) in metadataByJar) {
            if (info.minNimbusVersion != null && nimbusVersion != null) {
                val minVersion = parseVersion(info.minNimbusVersion)
                if (minVersion != null && compareVersions(nimbusVersion, minVersion) < 0) {
                    logger.warn("Module '{}' requires Nimbus {} but running {} — skipping",
                        info.name, info.minNimbusVersion, NimbusVersion.version)
                    continue
                }
            }
        }

        // Sort by dependencies (modules with no deps first)
        val loadOrder = resolveDependencyOrder(metadataByJar)

        for (jar in loadOrder) {
            try {
                val classLoader = URLClassLoader(
                    arrayOf(jar.toUri().toURL()),
                    this::class.java.classLoader
                )
                classLoaders.add(classLoader)

                val loader = ServiceLoader.load(NimbusModule::class.java, classLoader)
                for (module in loader) {
                    if (modules.containsKey(module.id)) {
                        logger.warn("Duplicate module id '{}' from {} — skipping", module.id, jar.fileName)
                        continue
                    }
                    modules[module.id] = module
                    logger.info("Loaded module: {} v{} ({})", module.name, module.version, module.id)
                    runBlocking { eventBus.emit(NimbusEvent.ModuleLoaded(module.id, module.name, module.version)) }
                }
            } catch (e: Exception) {
                logger.error("Failed to load module from {}: {}", jar.fileName, e.message, e)
            }
        }

        // Warn about missing dependencies
        for ((_, info) in metadataByJar) {
            for (dep in info.dependencies) {
                if (!modules.containsKey(dep)) {
                    logger.warn("Module '{}' depends on '{}' which is not installed", info.id, dep)
                }
            }
        }
    }

    /** Initialize and enable all loaded modules. */
    suspend fun enableAll() {
        for ((id, module) in modules) {
            try {
                module.init(context)
                logger.info("Initialized module: {}", module.name)
            } catch (e: Exception) {
                logger.error("Failed to initialize module '{}': {}", id, e.message, e)
            }
        }
        for ((id, module) in modules) {
            try {
                module.enable()
                eventBus.emit(NimbusEvent.ModuleEnabled(module.id, module.name))
            } catch (e: Exception) {
                logger.error("Failed to enable module '{}': {}", id, e.message, e)
            }
        }
    }

    /** Disable all modules (in reverse order) and close class loaders. */
    fun disableAll() {
        for ((id, module) in modules.entries.reversed()) {
            try {
                module.disable()
                runBlocking { eventBus.emit(NimbusEvent.ModuleDisabled(module.id, module.name)) }
                logger.info("Disabled module: {}", module.name)
            } catch (e: Exception) {
                logger.error("Failed to disable module '{}': {}", id, e.message, e)
            }
        }
        for (cl in classLoaders) {
            try { cl.close() } catch (_: Exception) {}
        }
        classLoaders.clear()
    }

    val modulesDirectory: Path get() = modulesDir

    fun getModule(id: String): NimbusModule? = modules[id]
    fun getModules(): List<NimbusModule> = modules.values.toList()
    fun isLoaded(id: String): Boolean = modules.containsKey(id)

    /**
     * Discovers available module JARs embedded in the application resources.
     */
    fun discoverAvailable(): List<ModuleInfo> {
        val result = mutableListOf<ModuleInfo>()
        for (name in EMBEDDED_MODULES) {
            val resource = javaClass.classLoader.getResourceAsStream("controller-modules/$name") ?: continue
            try {
                val tempFile = Files.createTempFile("nimbus-module-", ".jar")
                resource.use { Files.copy(it, tempFile, StandardCopyOption.REPLACE_EXISTING) }
                val info = readModuleProperties(tempFile)
                if (info != null) result.add(info.copy(fileName = name))
                Files.deleteIfExists(tempFile)
            } catch (_: Exception) {}
        }
        return result
    }

    fun install(moduleId: String): InstallResult {
        val available = discoverAvailable()
        val info = available.find { it.id == moduleId } ?: return InstallResult.NOT_FOUND
        val target = modulesDir.resolve(info.fileName)
        if (target.exists()) return InstallResult.ALREADY_INSTALLED
        val resource = javaClass.classLoader.getResourceAsStream("controller-modules/${info.fileName}")
            ?: return InstallResult.NOT_FOUND
        if (!modulesDir.exists()) Files.createDirectories(modulesDir)
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        logger.info("Installed module '{}' to {}", info.name, target)
        return InstallResult.INSTALLED
    }

    fun uninstall(moduleId: String): Boolean {
        val available = discoverAvailable()
        val info = available.find { it.id == moduleId }
        val fileName = info?.fileName
        val installedJars = if (modulesDir.exists()) modulesDir.listDirectoryEntries("*.jar") else emptyList()
        val jarToDelete = if (fileName != null) {
            modulesDir.resolve(fileName)
        } else {
            installedJars.find { jar -> readModuleProperties(jar)?.id == moduleId }
        }
        if (jarToDelete == null || !jarToDelete.exists()) return false
        jarToDelete.deleteIfExists()
        logger.info("Uninstalled module '{}' — restart required", moduleId)
        return true
    }

    enum class InstallResult { INSTALLED, ALREADY_INSTALLED, NOT_FOUND }

    // ── Dependency resolution ──────────────────────────────

    private fun resolveDependencyOrder(metadataByJar: Map<Path, ModuleInfo>): List<Path> {
        val idToJar = metadataByJar.entries.associate { it.value.id to it.key }
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<Path>()

        fun visit(id: String) {
            if (id in visited) return
            visited.add(id)
            val info = metadataByJar.values.find { it.id == id }
            if (info != null) {
                for (dep in info.dependencies) visit(dep)
            }
            val jar = idToJar[id]
            if (jar != null) ordered.add(jar)
        }

        for (info in metadataByJar.values) visit(info.id)
        // Add any JARs without metadata
        for (jar in metadataByJar.keys) {
            if (jar !in ordered) ordered.add(jar)
        }
        return ordered
    }

    // ── Version comparison ─────────────────────────────────

    private fun parseVersion(version: String): List<Int>? {
        if (version == "dev") return null
        return version.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    companion object {
        private val EMBEDDED_MODULES = listOf(
            "nimbus-module-perms.jar",
            "nimbus-module-display.jar"
        )

        fun readModuleProperties(jarPath: Path): ModuleInfo? {
            return try {
                JarFile(jarPath.toFile()).use { jar ->
                    val entry = jar.getEntry("module.properties") ?: return null
                    val props = Properties()
                    jar.getInputStream(entry).use { props.load(it) }
                    ModuleInfo(
                        id = props.getProperty("id") ?: return null,
                        name = props.getProperty("name") ?: return null,
                        description = props.getProperty("description") ?: "",
                        defaultEnabled = props.getProperty("default")?.toBoolean() ?: false,
                        fileName = jarPath.fileName.toString(),
                        dependencies = props.getProperty("dependencies")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                        minNimbusVersion = props.getProperty("min_nimbus_version")
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Lightweight module descriptor read from `module.properties`. */
data class ModuleInfo(
    val id: String,
    val name: String,
    val description: String,
    val defaultEnabled: Boolean,
    val fileName: String,
    val dependencies: List<String> = emptyList(),
    val minNimbusVersion: String? = null
)
