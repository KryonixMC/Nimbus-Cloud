package dev.nimbuspowered.nimbus.module.docker.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.module.api.ModuleCommand
import dev.nimbuspowered.nimbus.module.docker.DockerClient
import dev.nimbuspowered.nimbus.module.docker.DockerConfigManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DockerCommand(
    private val client: DockerClient,
    private val configManager: DockerConfigManager
) : ModuleCommand {

    override val name = "docker"
    override val description = "Docker module: daemon status, Nimbus-managed containers, prune"
    override val usage = "docker <status|ps|inspect|prune>"

    override suspend fun execute(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null, "status" -> status()
            "ps" -> ps()
            "inspect" -> inspect(args.getOrNull(1))
            "prune" -> prune()
            else -> println("${error("Unknown subcommand")} — $usage")
        }
    }

    private fun status() {
        val cfg = configManager.config.docker
        println("${BOLD}Docker Module${RESET}")
        println("  ${DIM}enabled:${RESET} ${cfg.enabled}")
        println("  ${DIM}socket:${RESET}  ${cfg.socket}")

        if (!cfg.enabled) {
            println("  ${info("module is disabled in config")}")
            return
        }

        val ok = client.ping()
        if (!ok) {
            println("  ${error("daemon:")} unreachable at ${cfg.socket}")
            return
        }

        val v = client.version()
        if (v != null) {
            println("  ${success("daemon:")} ${v.version} ${DIM}(api ${v.apiVersion}, ${v.os}/${v.arch})${RESET}")
        } else {
            println("  ${success("daemon:")} reachable")
        }

        val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
            .getOrDefault(emptyList())
        val running = containers.count { (it["State"]?.jsonPrimitive?.content ?: "") == "running" }
        println("  ${DIM}containers:${RESET} $running running / ${containers.size} total (Nimbus-managed)")
    }

    private fun ps() {
        val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
            .getOrElse {
                println("${error("Failed to list containers:")} ${it.message}")
                return
            }

        if (containers.isEmpty()) {
            println(info("No Nimbus-managed containers."))
            return
        }

        println("${BOLD}${"ID".padEnd(14)}${"SERVICE".padEnd(24)}${"STATE".padEnd(12)}${"IMAGE".padEnd(32)}PORTS${RESET}")
        for (c in containers) {
            val id = c["Id"]?.jsonPrimitive?.content?.take(12) ?: "?"
            val svc = c["Labels"]?.jsonObject?.get("nimbus.service")?.jsonPrimitive?.content ?: "?"
            val state = c["State"]?.jsonPrimitive?.content ?: "?"
            val image = c["Image"]?.jsonPrimitive?.content ?: "?"
            val ports = formatPorts(c)
            println("${id.padEnd(14)}${svc.padEnd(24)}${state.padEnd(12)}${image.padEnd(32)}$ports")
        }
    }

    private fun inspect(name: String?) {
        if (name == null) {
            println("${error("Usage:")} docker inspect <container-name-or-id>")
            return
        }
        val data = runCatching { client.inspect(name) }
            .getOrElse {
                println("${error("Inspect failed:")} ${it.message}")
                return
            }
        if (data == null) {
            println(info("No such container: $name"))
            return
        }

        val state = data["State"]?.jsonObject
        val config = data["Config"]?.jsonObject
        val host = data["HostConfig"]?.jsonObject

        println("${BOLD}Container${RESET} ${data["Name"]?.jsonPrimitive?.content ?: name}")
        println("  ${DIM}id:${RESET}     ${data["Id"]?.jsonPrimitive?.content?.take(12)}")
        println("  ${DIM}image:${RESET}  ${config?.get("Image")?.jsonPrimitive?.content}")
        println("  ${DIM}running:${RESET} ${state?.get("Running")?.jsonPrimitive?.content}")
        println("  ${DIM}pid:${RESET}    ${state?.get("Pid")?.jsonPrimitive?.content}")
        println("  ${DIM}memory:${RESET} ${host?.get("Memory")?.jsonPrimitive?.content} bytes")
        println("  ${DIM}nano-cpus:${RESET} ${host?.get("NanoCpus")?.jsonPrimitive?.content}")

        val stats = client.stats(data["Id"]?.jsonPrimitive?.content ?: return)
        if (stats != null) {
            val mb = stats.memoryBytes / 1024 / 1024
            val limitMb = stats.memoryLimitBytes / 1024 / 1024
            println("  ${DIM}mem live:${RESET} ${mb}MB / ${limitMb}MB  ${DIM}cpu:${RESET} %.1f%%".format(stats.cpuPercent))
        }
    }

    private fun prune() {
        val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
            .getOrElse {
                println("${error("List failed:")} ${it.message}")
                return
            }
        val stopped = containers.filter {
            val state = it["State"]?.jsonPrimitive?.content ?: ""
            state != "running"
        }
        if (stopped.isEmpty()) {
            println(info("Nothing to prune — no stopped Nimbus containers."))
            return
        }
        var removed = 0
        for (c in stopped) {
            val id = c["Id"]?.jsonPrimitive?.content ?: continue
            try {
                client.removeContainer(id, force = true)
                removed++
            } catch (e: Exception) {
                println("  ${error("failed")} to remove ${id.take(12)}: ${e.message}")
            }
        }
        println(success("Removed $removed stopped container(s)."))
    }

    private fun formatPorts(c: JsonObject): String {
        val arr = c["Ports"]?.jsonArray ?: return ""
        return arr.joinToString(", ") { p ->
            val obj = p.jsonObject
            val public = obj["PublicPort"]?.jsonPrimitive?.content
            val private = obj["PrivatePort"]?.jsonPrimitive?.content
            val type = obj["Type"]?.jsonPrimitive?.content ?: "tcp"
            if (public != null) "$public→$private/$type" else "$private/$type"
        }
    }
}
