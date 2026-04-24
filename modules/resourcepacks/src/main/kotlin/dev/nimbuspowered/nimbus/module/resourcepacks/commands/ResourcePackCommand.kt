package dev.nimbuspowered.nimbus.module.resourcepacks.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.api.CompletionMeta
import dev.nimbuspowered.nimbus.module.api.CompletionType
import dev.nimbuspowered.nimbus.module.api.SubcommandMeta
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePackManager
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePacksEvents
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ResourcePackCommand(
    private val manager: ResourcePackManager,
    private val eventBus: EventBus,
    private val maxUploadBytes: Long
) : Command {

    override val name = "resourcepack"
    override val description = "Manage network-wide resource packs"
    override val usage = "resourcepack <list|add|upload|remove|assign|unassign|assignments>"
    override val permission = "nimbus.cloud.resourcepack"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("list", "List all packs", "resourcepack list"),
        SubcommandMeta("add", "Register a URL pack", "resourcepack add <name> <url> [--force] [--prompt <msg>]"),
        SubcommandMeta("upload", "Upload a local zip", "resourcepack upload <name> <path> [--force]"),
        SubcommandMeta("remove", "Delete a pack", "resourcepack remove <id>"),
        SubcommandMeta("assign", "Assign pack to scope", "resourcepack assign <id> <global|group <name>|service <name>> [priority]",
            listOf(CompletionMeta(0, CompletionType.FREE_TEXT))),
        SubcommandMeta("unassign", "Remove assignment", "resourcepack unassign <assignment-id>"),
        SubcommandMeta("assignments", "List assignments", "resourcepack assignments [pack-id]")
    )

    override suspend fun execute(args: List<String>) {
        val out = object : CommandOutput {
            override fun header(text: String) = println(ConsoleFormatter.header(text))
            override fun info(text: String) = println(ConsoleFormatter.info(text))
            override fun success(text: String) = println(ConsoleFormatter.successLine(text))
            override fun error(text: String) = println(ConsoleFormatter.error(text))
            override fun item(text: String) = println("  $text")
            override fun text(text: String) = println(text)
        }
        execute(args, out)
    }

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) { output.error("Usage: $usage"); return true }
        when (args[0].lowercase()) {
            "list" -> listPacks(output)
            "add" -> addUrl(args.drop(1), output)
            "upload" -> upload(args.drop(1), output)
            "remove" -> remove(args.drop(1), output)
            "assign" -> assign(args.drop(1), output)
            "unassign" -> unassign(args.drop(1), output)
            "assignments" -> listAssignments(args.drop(1), output)
            else -> output.error("Unknown subcommand '${args[0]}'")
        }
        return true
    }

    private suspend fun listPacks(output: CommandOutput) {
        val packs = manager.listPacks()
        if (packs.isEmpty()) { output.info("No resource packs registered"); return }
        output.header("Resource Packs (${packs.size})")
        for (p in packs) {
            val sizeStr = if (p.fileSize > 0) " ${p.fileSize / 1024} KB" else ""
            output.item("#${p.id} ${ConsoleFormatter.BOLD}${p.name}${ConsoleFormatter.RESET} " +
                    "${ConsoleFormatter.DIM}[${p.source}${sizeStr}]${ConsoleFormatter.RESET} " +
                    if (p.force) "${ConsoleFormatter.YELLOW}force${ConsoleFormatter.RESET} " else "")
            output.item("  ${ConsoleFormatter.DIM}${p.url}${ConsoleFormatter.RESET}")
        }
    }

    private suspend fun addUrl(args: List<String>, output: CommandOutput) {
        if (args.size < 2) { output.error("Usage: resourcepack add <name> <url> [--force] [--prompt <msg>]"); return }
        val name = args[0]
        val url = args[1]
        val force = args.contains("--force")
        val promptIdx = args.indexOf("--prompt")
        val prompt = if (promptIdx >= 0 && promptIdx + 1 < args.size) args[promptIdx + 1] else ""

        output.info("Downloading $url to compute SHA-1…")
        val hash = try { downloadAndHash(url, maxUploadBytes) }
        catch (e: Exception) { output.error("Failed to fetch URL: ${e.message}"); return }

        val pack = manager.createUrlPack(name, url, hash, prompt, force, "console")
        eventBus.emit(ResourcePacksEvents.created(pack))
        output.success("Registered '${pack.name}' (#${pack.id}) — sha1=$hash")
    }

    private suspend fun upload(args: List<String>, output: CommandOutput) {
        if (args.size < 2) { output.error("Usage: resourcepack upload <name> <path> [--force] [--prompt <msg>]"); return }
        val name = args[0]
        val path = Path.of(args[1])
        if (!Files.exists(path)) { output.error("File not found: $path"); return }
        val force = args.contains("--force")
        val promptIdx = args.indexOf("--prompt")
        val prompt = if (promptIdx >= 0 && promptIdx + 1 < args.size) args[promptIdx + 1] else ""

        val pack = try {
            Files.newInputStream(path).use { input ->
                manager.uploadLocalPack(name, input, maxUploadBytes, prompt, force, "console")
            }
        } catch (e: Exception) { output.error("Upload failed: ${e.message}"); return }

        eventBus.emit(ResourcePacksEvents.created(pack))
        output.success("Uploaded '${pack.name}' (#${pack.id}, ${pack.fileSize / 1024} KB)")
    }

    private suspend fun remove(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: resourcepack remove <id>"); return }
        val id = args[0].toIntOrNull() ?: run { output.error("Invalid id"); return }
        val pack = manager.getPack(id) ?: run { output.error("Pack #$id not found"); return }
        manager.deletePack(id)
        eventBus.emit(ResourcePacksEvents.deleted(id, pack.name))
        output.success("Removed '${pack.name}' (#$id)")
    }

    private suspend fun assign(args: List<String>, output: CommandOutput) {
        if (args.size < 2) { output.error("Usage: resourcepack assign <id> <global|group <name>|service <name>> [priority]"); return }
        val id = args[0].toIntOrNull() ?: run { output.error("Invalid id"); return }
        val scope = args[1].lowercase()
        val (actualScope, target, priorityIdx) = when (scope) {
            "global" -> Triple("GLOBAL", "", 2)
            "group" -> {
                if (args.size < 3) { output.error("Missing group name"); return }
                Triple("GROUP", args[2], 3)
            }
            "service" -> {
                if (args.size < 3) { output.error("Missing service name"); return }
                Triple("SERVICE", args[2], 3)
            }
            else -> { output.error("Scope must be 'global', 'group <name>', or 'service <name>'"); return }
        }
        val priority = args.getOrNull(priorityIdx)?.toIntOrNull() ?: 0

        val assignment = manager.createAssignment(id, actualScope, target, priority)
            ?: run { output.error("Pack #$id not found"); return }
        eventBus.emit(ResourcePacksEvents.assigned(id, actualScope, target))
        output.success("Assigned pack #$id to $actualScope${if (target.isNotEmpty()) " '$target'" else ""} " +
                "(priority=$priority, assignment #${assignment.id})")
    }

    private suspend fun unassign(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: resourcepack unassign <assignment-id>"); return }
        val id = args[0].toIntOrNull() ?: run { output.error("Invalid id"); return }
        if (!manager.deleteAssignment(id)) { output.error("Assignment #$id not found"); return }
        output.success("Removed assignment #$id")
    }

    private suspend fun listAssignments(args: List<String>, output: CommandOutput) {
        val packId = args.firstOrNull()?.toIntOrNull()
        val list = manager.listAssignments(packId)
        if (list.isEmpty()) { output.info("No assignments"); return }
        output.header("Assignments (${list.size})")
        for (a in list) {
            val targetStr = if (a.target.isNotEmpty()) " '${a.target}'" else ""
            output.item("#${a.id} pack=${a.packId} ${ConsoleFormatter.BOLD}${a.scope}${ConsoleFormatter.RESET}$targetStr " +
                    "${ConsoleFormatter.DIM}priority=${a.priority}${ConsoleFormatter.RESET}")
        }
    }

    /**
     * Fetch a URL and compute its SHA-1 while streaming. Used by `resourcepack add`
     * to save admins the manual hashing step.
     */
    private fun downloadAndHash(url: String, maxBytes: Long): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        var size = 0L
        URI.create(url).toURL().openStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                size += read
                if (size > maxBytes) throw IOException("File exceeds max size ($maxBytes bytes)")
                sha1.update(buffer, 0, read)
            }
        }
        return sha1.digest().joinToString("") { "%02x".format(it) }
    }
}
