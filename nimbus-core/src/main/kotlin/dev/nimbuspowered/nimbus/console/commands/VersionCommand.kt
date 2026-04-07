package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.update.UpdateChecker
import kotlin.io.path.Path

class VersionCommand : Command {

    override val name = "version"
    override val description = "Show Nimbus version and check for updates"
    override val usage = "version"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val version = NimbusVersion.version
        val kotlinVersion = KotlinVersion.CURRENT
        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val javaVendor = System.getProperty("java.vendor") ?: "unknown"
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"

        output.header("Nimbus")
        output.text(ConsoleFormatter.field("Version", ConsoleFormatter.colorize(version, ConsoleFormatter.CYAN), labelWidth = 22))
        output.text(ConsoleFormatter.field("Kotlin", kotlinVersion.toString(), labelWidth = 22))
        output.text(ConsoleFormatter.field("Java", "$javaVersion ($javaVendor)", labelWidth = 22))
        output.text(ConsoleFormatter.field("OS", os, labelWidth = 22))

        if (version == "dev") {
            output.text("")
            output.info("Running dev build — update check skipped.")
            return true
        }

        output.text("")
        output.info("Checking for updates...")

        val checker = UpdateChecker(Path("").toAbsolutePath())
        try {
            val update = checker.checkForUpdate()
            if (update == null) {
                output.success("Up to date")
            } else {
                val channel = if (update.isPreRelease) " (pre-release)" else ""
                output.info("Update available: v${update.currentVersion} -> v${update.latestVersion}$channel (${update.type.name.lowercase()})")
                if (update.releaseUrl.isNotEmpty()) {
                    output.info("Release: ${update.releaseUrl}")
                }
                output.info("Restart Nimbus to install this update.")
            }
        } catch (_: Exception) {
            output.info("Could not reach GitHub to check for updates.")
        } finally {
            checker.close()
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
