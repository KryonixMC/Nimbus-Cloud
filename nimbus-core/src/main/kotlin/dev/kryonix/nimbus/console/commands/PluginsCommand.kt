package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.InteractivePicker
import dev.kryonix.nimbus.console.LiveSearchPicker
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.template.PluginSearchClient
import dev.kryonix.nimbus.template.SoftwareResolver
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Plugin management command.
 *
 * - `plugins` or `plugins search` — interactive live search (Hangar + Modrinth)
 * - `plugins list [target]` — show installed plugins
 * - `plugins remove <file> <target>` — remove a plugin JAR
 */
class PluginsCommand(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val softwareResolver: SoftwareResolver,
    private val terminal: Terminal
) : Command {

    override val name = "plugins"
    override val description = "Search, install, and manage server plugins"
    override val usage = "plugins [list [target] | search [target] | remove <file> <target>]"

    private val templatesDir: Path get() = Path.of(config.paths.templates)
    private val staticDir: Path get() = Path.of(config.paths.services).resolve("static")

    override suspend fun execute(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "search", "find" -> search(args.drop(if (args.firstOrNull()?.lowercase() in listOf("search", "find") ) 1 else 0))
            "list", "ls" -> list(args.getOrNull(1))
            "remove", "rm" -> remove(args.getOrNull(1), args.getOrNull(2))
            else -> {
                // Treat unknown first arg as a search query
                search(args)
            }
        }
    }

    // ── List installed plugins ─────────────────────────────

    private fun list(target: String?) {
        val dirsToCheck = if (target != null) {
            val dir = resolvePluginsDir(target)
            if (dir == null) {
                println(ConsoleFormatter.error("Unknown target: $target"))
                return
            }
            listOf(target to dir)
        } else {
            // Show all targets
            buildList {
                val globalDir = templatesDir.resolve("global").resolve("plugins")
                if (globalDir.exists()) add("global" to globalDir)
                val globalProxyDir = templatesDir.resolve("global_proxy").resolve("plugins")
                if (globalProxyDir.exists()) add("global_proxy" to globalProxyDir)
                for (group in groupManager.getAllGroups()) {
                    val templateName = group.config.group.template.ifEmpty { group.name }
                    val dir = templatesDir.resolve(templateName).resolve("plugins")
                    if (dir.exists()) add(group.name to dir)
                }
            }
        }

        if (dirsToCheck.isEmpty()) {
            println(ConsoleFormatter.emptyState("No plugin directories found."))
            return
        }

        for ((label, dir) in dirsToCheck) {
            val jars = listJars(dir)
            if (jars.isEmpty()) continue

            println("${ConsoleFormatter.colorize(label, ConsoleFormatter.BOLD)}  ${ConsoleFormatter.hint("(${jars.size} plugin${if (jars.size != 1) "s" else ""})")}")
            for (jar in jars) {
                val sizeMb = String.format("%.1f", jar.fileSize() / 1024.0 / 1024.0)
                println("  ${ConsoleFormatter.success("●")} ${jar.name}  ${ConsoleFormatter.hint("$sizeMb MB")}")
            }
            println()
        }
    }

    // ── Remove plugin ──────────────────────────────────────

    private fun remove(fileArg: String?, targetArg: String?) {
        if (fileArg == null || targetArg == null) {
            println(ConsoleFormatter.error("Usage: plugins remove <filename.jar> <target>"))
            return
        }

        val pluginsDir = resolvePluginsDir(targetArg)
        if (pluginsDir == null) {
            println(ConsoleFormatter.error("Unknown target: $targetArg"))
            return
        }

        val jar = pluginsDir.resolve(fileArg)
        if (!jar.exists()) {
            println(ConsoleFormatter.hint("$fileArg not found in $targetArg."))
            return
        }

        Files.deleteIfExists(jar)
        println(ConsoleFormatter.successLine("Removed ${ConsoleFormatter.info(fileArg)} from ${ConsoleFormatter.info(targetArg)}"))
    }

    // ── Interactive search (Hangar + Modrinth) ─────────────

    private suspend fun search(args: List<String>) {
        // Determine target
        val target = args.firstOrNull()?.takeIf { resolvePluginsDir(it) != null || it.lowercase() == "global" } ?: run {
            val options = mutableListOf(
                InteractivePicker.Option("global", "global", "all backend servers")
            )
            for (group in groupManager.getAllGroups()) {
                if (group.config.group.software != ServerSoftware.VELOCITY) {
                    options.add(InteractivePicker.Option(group.name, group.name, group.config.group.version))
                }
            }
            println(ConsoleFormatter.colorize("Install target:", ConsoleFormatter.BOLD))
            val idx = InteractivePicker.pickOne(terminal, options)
            if (idx == InteractivePicker.BACK) {
                println(ConsoleFormatter.hint("Cancelled."))
                return
            }
            options[idx].id
        }

        val pluginsDir = resolvePluginsDir(target)!!
        val mcVersion = resolveMcVersion(target)
        if (mcVersion == null) {
            println(ConsoleFormatter.error("Could not determine Minecraft version for '$target'"))
            return
        }

        val searchClient = PluginSearchClient(softwareResolver.client)
        val initialQuery = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() } ?: ""

        // Live search with multi-select
        val selectedPlugins = LiveSearchPicker.liveSearchMulti(
            terminal = terminal,
            title = "Search plugins ${ConsoleFormatter.hint("($mcVersion)")}",
            initialQuery = initialQuery,
            identify = { it.slug },
            search = { query -> searchClient.search(query, mcVersion) },
            render = { result ->
                val tag = when (result.source) {
                    PluginSearchClient.PluginSource.HANGAR -> "H"
                    PluginSearchClient.PluginSource.MODRINTH -> "M"
                }
                LiveSearchPicker.SearchLine(tag, result.name, result.author, formatDownloads(result.downloads), result.description)
            }
        )

        if (selectedPlugins.isNullOrEmpty()) {
            println(ConsoleFormatter.hint("Cancelled."))
            return
        }

        // Fetch versions
        println()
        data class Resolved(val result: PluginSearchClient.PluginSearchResult, val version: PluginSearchClient.PluginVersionInfo)

        val resolved = mutableListOf<Resolved>()
        for (plugin in selectedPlugins) {
            print(ConsoleFormatter.hint("  Fetching ${plugin.name}..."))
            val version = searchClient.fetchVersion(plugin, mcVersion)
            if (version != null) {
                resolved.add(Resolved(plugin, version))
                println("\r  ${ConsoleFormatter.success("●")} ${ConsoleFormatter.colorize(plugin.name, ConsoleFormatter.BOLD)} ${ConsoleFormatter.hint("v${version.versionName}")}")
            } else {
                println("\r  ${ConsoleFormatter.error("●")} ${plugin.name} ${ConsoleFormatter.hint("— no compatible version for $mcVersion")}")
            }
        }

        if (resolved.isEmpty()) {
            println(ConsoleFormatter.error("No compatible versions found"))
            return
        }

        // Show dependencies
        val allDeps = resolved.flatMap { it.version.dependencies.filter { d -> d.required } }
        if (allDeps.isNotEmpty()) {
            println()
            println(ConsoleFormatter.warn("  Dependencies (auto-install):"))
            for (dep in allDeps) println("    ${ConsoleFormatter.hint("●")} ${dep.name}")
        }

        // Confirm
        val count = resolved.size
        val depCount = allDeps.size
        val summary = buildString {
            append("$count plugin${if (count != 1) "s" else ""}")
            if (depCount > 0) append(" + $depCount dep${if (depCount != 1) "s" else ""}")
        }
        println()
        print(ConsoleFormatter.warn("  Install $summary to ${ConsoleFormatter.info(target)}${ConsoleFormatter.warn("? [Y/n]")} "))
        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm == "n" || confirm == "no") {
            println(ConsoleFormatter.hint("Cancelled."))
            return
        }

        // Download
        if (!pluginsDir.exists()) pluginsDir.createDirectories()
        println()

        for ((result, version) in resolved) {
            val file = searchClient.download(version, pluginsDir)
            if (file != null) {
                val size = if (version.fileSize > 0) " ${ConsoleFormatter.hint("(${PluginSearchClient.formatSize(version.fileSize)} MB)")}" else ""
                println(ConsoleFormatter.successLine("${ConsoleFormatter.info(result.name)} v${version.versionName}$size"))
            } else {
                println(ConsoleFormatter.errorLine("Failed to download ${result.name}"))
            }

            for (dep in version.dependencies.filter { it.required }) {
                val depFile = searchClient.resolveAndDownloadDependency(dep, mcVersion, pluginsDir)
                if (depFile != null) {
                    println("  ${ConsoleFormatter.success("+")} ${ConsoleFormatter.hint(dep.name)}")
                } else {
                    println("  ${ConsoleFormatter.warn("!")} ${ConsoleFormatter.hint("${dep.name} — install manually")}")
                }
            }
        }

        println()
        println(ConsoleFormatter.hint("Done. Restart affected services to load new plugins."))
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun resolvePluginsDir(target: String): Path? {
        val lower = target.lowercase()
        if (lower == "global") return templatesDir.resolve("global").resolve("plugins")
        if (lower == "global_proxy") return templatesDir.resolve("global_proxy").resolve("plugins")

        val group = groupManager.getGroup(target)
        if (group != null) {
            val templateName = group.config.group.template.ifEmpty { group.name }
            return templatesDir.resolve(templateName).resolve("plugins")
        }

        val staticServiceDir = staticDir.resolve(target)
        if (staticServiceDir.exists()) return staticServiceDir.resolve("plugins")

        return null
    }

    private fun resolveMcVersion(target: String): String? {
        val group = groupManager.getGroup(target)
        if (group != null) return group.config.group.version

        if (target.lowercase() in listOf("global", "global_proxy")) {
            return groupManager.getAllGroups()
                .filter { it.config.group.software != ServerSoftware.VELOCITY }
                .groupBy { it.config.group.version }
                .maxByOrNull { it.value.size }
                ?.key
        }
        return null
    }

    private fun listJars(dir: Path): List<Path> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.name.endsWith(".jar") }.sorted().toList()
        }
    }

    private fun formatDownloads(count: Long): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
