package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.update.UpdateChecker
import kotlin.io.path.Path

class VersionCommand : Command {

    override val name = "version"
    override val description = "Show Nimbus version and check for updates"
    override val usage = "version"

    override suspend fun execute(args: List<String>) {
        val version = NimbusVersion.version
        val kotlinVersion = KotlinVersion.CURRENT
        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val javaVendor = System.getProperty("java.vendor") ?: "unknown"
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

        println(ConsoleFormatter.header("Nimbus"))
        println(ConsoleFormatter.field("Version", ConsoleFormatter.colorize(version, ConsoleFormatter.CYAN), labelWidth = 22))
        println(ConsoleFormatter.field("Kotlin", kotlinVersion.toString(), labelWidth = 22))
        println(ConsoleFormatter.field("Java", "$javaVersion ($javaVendor)", labelWidth = 22))
        println(ConsoleFormatter.field("OS", os, labelWidth = 22))

        if (version == "dev") {
            println()
            println(ConsoleFormatter.hint("  Running dev build — update check skipped."))
            return
        }

        println()
        print(ConsoleFormatter.hint("  Checking for updates... "))

        val checker = UpdateChecker(Path("").toAbsolutePath())
        try {
            val update = checker.checkForUpdate()
            if (update == null) {
                println(ConsoleFormatter.success("up to date"))
            } else {
                val channel = if (update.isPreRelease) " (pre-release)" else ""
                println()
                println(ConsoleFormatter.warn("  Update available: v${update.currentVersion} -> v${update.latestVersion}$channel (${update.type.name.lowercase()})"))
                if (update.releaseUrl.isNotEmpty()) {
                    println(ConsoleFormatter.hint("  Release: ${update.releaseUrl}"))
                }
                println(ConsoleFormatter.hint("  Restart Nimbus to install this update."))
            }
        } catch (_: Exception) {
            println(ConsoleFormatter.warn("failed"))
            println(ConsoleFormatter.hint("  Could not reach GitHub to check for updates."))
        } finally {
            checker.close()
        }
    }
}
