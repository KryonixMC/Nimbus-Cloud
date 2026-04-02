package dev.kryonix.nimbus.module

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
    private val context: ModuleContext
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

        for (jar in jars) {
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
                }
            } catch (e: Exception) {
                logger.error("Failed to load module from {}: {}", jar.fileName, e.message, e)
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
                logger.info("Disabled module: {}", module.name)
            } catch (e: Exception) {
                logger.error("Failed to disable module '{}': {}", id, e.message, e)
            }
        }
        for (cl in classLoaders) {
            try {
                cl.close()
            } catch (_: Exception) {}
        }
        classLoaders.clear()
    }

    val modulesDirectory: Path get() = modulesDir

    fun getModule(id: String): NimbusModule? = modules[id]
    fun getModules(): List<NimbusModule> = modules.values.toList()
    fun isLoaded(id: String): Boolean = modules.containsKey(id)

    /**
     * Discovers available module JARs embedded in the application resources.
     * Returns metadata for all available modules (installed or not).
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

    /**
     * Installs an embedded module by extracting its JAR to the modules directory.
     * Returns true if installed successfully, false if not found or already installed.
     */
    fun install(moduleId: String): InstallResult {
        val available = discoverAvailable()
        val info = available.find { it.id == moduleId }
            ?: return InstallResult.NOT_FOUND

        val target = modulesDir.resolve(info.fileName)
        if (target.exists()) return InstallResult.ALREADY_INSTALLED

        val resource = javaClass.classLoader.getResourceAsStream("controller-modules/${info.fileName}")
            ?: return InstallResult.NOT_FOUND

        if (!modulesDir.exists()) Files.createDirectories(modulesDir)
        resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        logger.info("Installed module '{}' to {}", info.name, target)
        return InstallResult.INSTALLED
    }

    /**
     * Uninstalls a module by deleting its JAR from the modules directory.
     * The module remains loaded until restart.
     */
    fun uninstall(moduleId: String): Boolean {
        // Find the JAR file for this module
        val available = discoverAvailable()
        val info = available.find { it.id == moduleId }
        val fileName = info?.fileName

        // Also check installed JARs directly
        val installedJars = if (modulesDir.exists()) modulesDir.listDirectoryEntries("*.jar") else emptyList()
        val jarToDelete = if (fileName != null) {
            modulesDir.resolve(fileName)
        } else {
            // Try to find by reading module.properties from installed JARs
            installedJars.find { jar ->
                readModuleProperties(jar)?.id == moduleId
            }
        }

        if (jarToDelete == null || !jarToDelete.exists()) return false
        jarToDelete.deleteIfExists()
        logger.info("Uninstalled module '{}' — restart required", moduleId)
        return true
    }

    enum class InstallResult { INSTALLED, ALREADY_INSTALLED, NOT_FOUND }

    companion object {
        /** Known embedded module JAR filenames. */
        private val EMBEDDED_MODULES = listOf(
            "nimbus-module-perms.jar",
            "nimbus-module-display.jar",
            "nimbus-module-refinery.jar"
        )
        /**
         * Reads module metadata from a JAR's `module.properties` resource
         * without loading the module class. Used by the SetupWizard.
         */
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
                        fileName = jarPath.fileName.toString()
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Lightweight module descriptor read from `module.properties` (no class loading required). */
data class ModuleInfo(
    val id: String,
    val name: String,
    val description: String,
    val defaultEnabled: Boolean,
    val fileName: String
)
