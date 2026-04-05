package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.ConsoleFormatter.BOLD
import dev.kryonix.nimbus.console.ConsoleFormatter.CYAN
import dev.kryonix.nimbus.console.ConsoleFormatter.DIM
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.database.AuditLog
import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.module.CommandOutput
import dev.kryonix.nimbus.module.SubcommandMeta
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll

class AuditCommand(
    private val db: DatabaseManager
) : Command {

    override val name = "audit"
    override val description = "Show audit log of administrative actions"
    override val usage = "audit [limit] [--action <type>] [--actor <name>]"
    override val permission = "nimbus.cloud.audit"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("", "Show recent audit entries (default: 20)", "audit"),
        SubcommandMeta("[limit]", "Show N most recent entries", "audit 50"),
        SubcommandMeta("--action <type>", "Filter by action type", "audit --action SERVICE_STARTING"),
        SubcommandMeta("--actor <name>", "Filter by actor", "audit --actor console")
    )

    override suspend fun execute(args: List<String>) {
        var limit = 20
        var actionFilter: String? = null
        var actorFilter: String? = null

        val iter = args.iterator()
        while (iter.hasNext()) {
            when (val arg = iter.next()) {
                "--action" -> actionFilter = if (iter.hasNext()) iter.next() else null
                "--actor" -> actorFilter = if (iter.hasNext()) iter.next() else null
                else -> arg.toIntOrNull()?.let { limit = it.coerceIn(1, 200) }
            }
        }

        val entries = db.query {
            var query = AuditLog.selectAll()
            if (actionFilter != null) query = query.andWhere { AuditLog.action eq actionFilter }
            if (actorFilter != null) query = query.andWhere { AuditLog.actor eq actorFilter }
            query.orderBy(AuditLog.timestamp, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    Entry(
                        timestamp = row[AuditLog.timestamp],
                        actor = row[AuditLog.actor],
                        action = row[AuditLog.action],
                        target = row[AuditLog.target],
                        details = row[AuditLog.details]
                    )
                }
        }

        if (entries.isEmpty()) {
            println(ConsoleFormatter.info("No audit entries found."))
            return
        }

        println("${BOLD}Audit Log${RESET} ${DIM}(${entries.size} entries, newest first)${RESET}")
        println("")

        for (entry in entries) {
            val ts = entry.timestamp.substringBefore('T').let { date ->
                val time = entry.timestamp.substringAfter('T').substringBefore('.').take(8)
                "$date $time"
            }
            val targetStr = if (entry.target.isNotBlank()) " ${BOLD}${entry.target}${RESET}" else ""
            val detailStr = if (entry.details.isNotBlank()) " ${DIM}(${entry.details})${RESET}" else ""
            println("  ${DIM}$ts${RESET}  ${CYAN}${entry.actor}${RESET}  ${entry.action}$targetStr$detailStr")
        }
    }

    private data class Entry(
        val timestamp: String,
        val actor: String,
        val action: String,
        val target: String,
        val details: String
    )
}
