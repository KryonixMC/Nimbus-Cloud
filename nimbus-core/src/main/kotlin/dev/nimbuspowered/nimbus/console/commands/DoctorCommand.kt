package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.doctor.DoctorReport
import dev.nimbuspowered.nimbus.doctor.DoctorRunner
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.api.DoctorCheck
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Runs a battery of deployment health checks and reports findings with actionable hints.
 * Delegates to [DoctorRunner] so the CLI, Remote CLI, REST endpoint and JSON output
 * all share the same checks and report shape.
 *
 * Usage:
 *   doctor          — pretty-printed text output
 *   doctor --json   — single-line JSON for scripting / monitoring
 */
class DoctorCommand(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val databaseManager: DatabaseManager? = null,
    private val nodeManager: NodeManager? = null,
    private val extraChecks: () -> List<DoctorCheck> = { emptyList() },
) : Command {

    override val name = "doctor"
    override val description = "Run diagnostic checks on the Nimbus deployment"
    override val usage = "doctor [--json]"
    override val permission = "nimbus.cloud.doctor"

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val asJson = args.any { it == "--json" }
        val report = DoctorRunner(config, registry, databaseManager, nodeManager, extraChecks).run()

        if (asJson) {
            output.text(json.encodeToString(report))
            return true
        }
        renderText(report, output)
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }

    private fun renderText(report: DoctorReport, output: CommandOutput) {
        output.header("Nimbus Doctor")
        for (section in report.sections) {
            output.text("")
            output.text(ConsoleFormatter.section(section.name))
            for (f in section.findings) {
                val line = when (f.level) {
                    "OK"   -> ConsoleFormatter.successLine(f.message)
                    "WARN" -> ConsoleFormatter.warnLine(f.message)
                    "FAIL" -> ConsoleFormatter.errorLine(f.message)
                    else   -> f.message
                }
                output.text("  $line")
                if (f.level != "OK" && f.hint != null) {
                    output.text("    ${ConsoleFormatter.hint("→ ${f.hint}")}")
                }
            }
        }
        output.text("")
        when {
            report.failCount > 0 -> output.error("${report.failCount} problem(s), ${report.warnCount} warning(s) — address the failures first")
            report.warnCount > 0 -> output.text(ConsoleFormatter.warnLine("${report.warnCount} warning(s) — deployment is functional, but consider reviewing"))
            else -> output.success("All checks passed")
        }
    }
}
