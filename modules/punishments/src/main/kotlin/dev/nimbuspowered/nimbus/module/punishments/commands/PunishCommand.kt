package dev.nimbuspowered.nimbus.module.punishments.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.CompletionMeta
import dev.nimbuspowered.nimbus.module.CompletionType
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.punishments.DurationParser
import dev.nimbuspowered.nimbus.module.punishments.PunishmentManager
import dev.nimbuspowered.nimbus.module.punishments.PunishmentRecord
import dev.nimbuspowered.nimbus.module.punishments.PunishmentScope
import dev.nimbuspowered.nimbus.module.punishments.PunishmentType
import dev.nimbuspowered.nimbus.module.punishments.PunishmentsEvents
import java.time.Duration
import java.time.Instant

/**
 * Player identity resolver — produces a (uuid, name) pair from a player identifier.
 * Implementations may consult online players, a DB cache, or external Mojang API.
 */
fun interface PlayerResolver {
    /** Returns (uuid, name), or null if the player cannot be resolved. */
    suspend fun resolve(input: String): Pair<String, String>?
}

class PunishCommand(
    private val manager: PunishmentManager,
    private val eventBus: EventBus,
    private val resolver: PlayerResolver
) : Command {

    override val name = "punish"
    override val description = "Network-wide ban/mute/kick/warn management"
    override val usage = "punish <ban|tempban|ipban|mute|tempmute|kick|warn|unban|unmute|history|list> <args...>"
    override val permission = "nimbus.cloud.punish"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("ban", "Permanently ban a player", "punish ban <player> [--group <g>|--service <s>] <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("tempban", "Ban for a duration", "punish tempban <player> <duration> [--group <g>|--service <s>] <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("ipban", "Ban an IP address", "punish ipban <player> <ip> <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("mute", "Permanently mute a player", "punish mute <player> [--group <g>|--service <s>] <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("tempmute", "Mute for a duration", "punish tempmute <player> <duration> [--group <g>|--service <s>] <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("kick", "Kick a player once", "punish kick <player> <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("warn", "Record a warning", "punish warn <player> <reason>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("unban", "Revoke active ban", "punish unban <player> [--group <g>|--service <s>]",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("unmute", "Revoke active mute", "punish unmute <player> [--group <g>|--service <s>]",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("history", "Show player history", "punish history <player>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("list", "List active punishments", "punish list [type]")
    )

    /**
     * Pulls `--group <name>` / `--service <name>` out of a token list and returns
     * the resulting scope + the leftover tokens (for reason parsing).
     *
     * Only one of the two flags is honored — if both appear, `--service` wins.
     * Unknown flags are left in place; the caller's reason joiner will include them,
     * which is fine for free-form reason text that happens to start with `--`.
     */
    private data class ParsedScope(
        val scope: PunishmentScope,
        val target: String?,
        val rest: List<String>,
        val error: String? = null
    )

    private fun parseScopeFlags(tokens: List<String>): ParsedScope {
        var scope = PunishmentScope.NETWORK
        var target: String? = null
        val rest = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t) {
                "--group", "-g" -> {
                    if (i + 1 >= tokens.size) return ParsedScope(scope, target, rest, "Missing group name after $t")
                    scope = PunishmentScope.GROUP
                    target = tokens[i + 1]
                    i += 2
                }
                "--service", "-s" -> {
                    if (i + 1 >= tokens.size) return ParsedScope(scope, target, rest, "Missing service name after $t")
                    scope = PunishmentScope.SERVICE
                    target = tokens[i + 1]
                    i += 2
                }
                else -> {
                    rest.add(t)
                    i += 1
                }
            }
        }
        return ParsedScope(scope, target, rest)
    }

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
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }
        when (args[0].lowercase()) {
            "ban" -> handleIssue(args.drop(1), PunishmentType.BAN, needsDuration = false, output)
            "tempban" -> handleIssue(args.drop(1), PunishmentType.TEMPBAN, needsDuration = true, output)
            "ipban" -> handleIpban(args.drop(1), output)
            "mute" -> handleIssue(args.drop(1), PunishmentType.MUTE, needsDuration = false, output)
            "tempmute" -> handleIssue(args.drop(1), PunishmentType.TEMPMUTE, needsDuration = true, output)
            "kick" -> handleIssue(args.drop(1), PunishmentType.KICK, needsDuration = false, output)
            "warn" -> handleIssue(args.drop(1), PunishmentType.WARN, needsDuration = false, output)
            "unban" -> handleRevokeBan(args.drop(1), output)
            "unmute" -> handleRevokeMute(args.drop(1), output)
            "history" -> handleHistory(args.drop(1), output)
            "list" -> handleList(args.drop(1), output)
            else -> output.error("Unknown subcommand '${args[0]}'. See: $usage")
        }
        return true
    }

    private suspend fun handleIssue(
        args: List<String>,
        type: PunishmentType,
        needsDuration: Boolean,
        output: CommandOutput
    ) {
        val required = if (needsDuration) 3 else 2
        if (args.size < required) {
            output.error(if (needsDuration) "Usage: punish ${type.name.lowercase()} <player> <duration> [--group <g>|--service <s>] <reason>"
                         else "Usage: punish ${type.name.lowercase()} <player> [--group <g>|--service <s>] <reason>")
            return
        }

        val resolved = resolver.resolve(args[0])
        if (resolved == null) {
            output.error("Player '${args[0]}' not found — provide a UUID or make sure they've joined before")
            return
        }
        val (uuid, name) = resolved

        val duration: Duration? = if (needsDuration) {
            try { DurationParser.parse(args[1]) } catch (e: IllegalArgumentException) {
                output.error(e.message ?: "Invalid duration"); return
            }
        } else null

        if (needsDuration && duration == null) {
            output.error("${type.name} cannot be permanent. Use 'ban' or 'mute' instead.")
            return
        }

        val rawAfterFixed = if (needsDuration) args.drop(2) else args.drop(1)
        val parsed = parseScopeFlags(rawAfterFixed)
        if (parsed.error != null) { output.error(parsed.error); return }
        val reason = parsed.rest.joinToString(" ").ifBlank { "No reason given" }

        val record = manager.issue(
            type = type,
            targetUuid = uuid,
            targetName = name,
            targetIp = null,
            duration = duration,
            reason = reason,
            issuer = "console",
            issuerName = "Console",
            scope = parsed.scope,
            scopeTarget = parsed.target
        )
        eventBus.emit(PunishmentsEvents.issued(record))

        val durationStr = DurationParser.format(duration)
        val scopeStr = when (parsed.scope) {
            PunishmentScope.NETWORK -> ""
            else -> " ${ConsoleFormatter.DIM}[${parsed.scope}:${parsed.target}]${ConsoleFormatter.RESET}"
        }
        output.success("${type.name} issued against ${ConsoleFormatter.BOLD}$name${ConsoleFormatter.RESET} " +
                "(#${record.id}, $durationStr)$scopeStr — $reason")
    }

    private suspend fun handleIpban(args: List<String>, output: CommandOutput) {
        if (args.size < 3) {
            output.error("Usage: punish ipban <player> <ip> <reason>")
            return
        }
        val resolved = resolver.resolve(args[0])
        if (resolved == null) {
            output.error("Player '${args[0]}' not found")
            return
        }
        val (uuid, name) = resolved
        val ip = args[1]
        val reason = args.drop(2).joinToString(" ").ifBlank { "No reason given" }
        val record = manager.issue(
            type = PunishmentType.IPBAN,
            targetUuid = uuid,
            targetName = name,
            targetIp = ip,
            duration = null,
            reason = reason,
            issuer = "console",
            issuerName = "Console"
        )
        eventBus.emit(PunishmentsEvents.issued(record))
        output.success("IPBAN issued against $name ($ip) — $reason")
    }

    private suspend fun handleRevokeBan(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: punish unban <player> [--group <g>|--service <s>]"); return }
        val resolved = resolver.resolve(args[0])
        val key = resolved?.first ?: args[0]
        val parsed = parseScopeFlags(args.drop(1))
        if (parsed.error != null) { output.error(parsed.error); return }
        val record = manager.findActiveBan(key, parsed.scope, parsed.target)
        if (record == null) {
            val scopeStr = if (parsed.scope == PunishmentScope.NETWORK) "network-wide"
                           else "${parsed.scope}:${parsed.target}"
            output.error("No active $scopeStr ban found for '${args[0]}'")
            return
        }
        val reason = parsed.rest.joinToString(" ").ifBlank { null }
        val revoked = manager.revoke(record.id, "console", reason)
        if (revoked == null) {
            output.error("Could not revoke punishment #${record.id}")
            return
        }
        eventBus.emit(PunishmentsEvents.revoked(revoked))
        output.success("Unbanned ${revoked.targetName} (#${revoked.id}, ${revoked.scope}${revoked.scopeTarget?.let { ":$it" } ?: ""})")
    }

    private suspend fun handleRevokeMute(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: punish unmute <player> [--group <g>|--service <s>]"); return }
        val resolved = resolver.resolve(args[0])
        val key = resolved?.first ?: args[0]
        val parsed = parseScopeFlags(args.drop(1))
        if (parsed.error != null) { output.error(parsed.error); return }
        val record = manager.findActiveMute(key, parsed.scope, parsed.target)
        if (record == null) {
            val scopeStr = if (parsed.scope == PunishmentScope.NETWORK) "network-wide"
                           else "${parsed.scope}:${parsed.target}"
            output.error("No active $scopeStr mute found for '${args[0]}'")
            return
        }
        val reason = parsed.rest.joinToString(" ").ifBlank { null }
        val revoked = manager.revoke(record.id, "console", reason)
        if (revoked == null) {
            output.error("Could not revoke punishment #${record.id}")
            return
        }
        eventBus.emit(PunishmentsEvents.revoked(revoked))
        output.success("Unmuted ${revoked.targetName} (#${revoked.id}, ${revoked.scope}${revoked.scopeTarget?.let { ":$it" } ?: ""})")
    }

    private suspend fun handleHistory(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: punish history <player>"); return }
        val resolved = resolver.resolve(args[0])
        val uuid = resolved?.first ?: args[0]
        val history = manager.getHistory(uuid, 50)
        if (history.isEmpty()) {
            output.info("No punishments on record for '${args[0]}'")
            return
        }
        output.header("History for ${resolved?.second ?: args[0]} (${history.size} records)")
        history.forEach { output.item(formatLine(it)) }
    }

    private suspend fun handleList(args: List<String>, output: CommandOutput) {
        val type = args.firstOrNull()?.let {
            runCatching { PunishmentType.valueOf(it.uppercase()) }.getOrNull()
        }
        val records = manager.list(activeOnly = true, type = type, limit = 50, offset = 0)
        if (records.isEmpty()) {
            output.info("No active punishments")
            return
        }
        output.header("Active punishments (${records.size})")
        records.forEach { output.item(formatLine(it)) }
    }

    private fun formatLine(r: PunishmentRecord): String {
        val remaining = r.expiresAt?.let {
            try {
                val secs = Duration.between(Instant.now(), Instant.parse(it)).seconds.coerceAtLeast(0)
                DurationParser.format(Duration.ofSeconds(secs))
            } catch (_: Exception) { "?" }
        } ?: "permanent"
        val status = if (r.active) ConsoleFormatter.success(r.type.name)
                     else "${ConsoleFormatter.DIM}${r.type.name}${ConsoleFormatter.RESET}"
        val scopeStr = when (r.scope) {
            PunishmentScope.NETWORK -> ""
            else -> " ${ConsoleFormatter.YELLOW}[${r.scope}:${r.scopeTarget ?: "?"}]${ConsoleFormatter.RESET}"
        }
        return "#${r.id} $status ${ConsoleFormatter.BOLD}${r.targetName}${ConsoleFormatter.RESET}$scopeStr " +
               "${ConsoleFormatter.DIM}by ${r.issuerName} • $remaining${ConsoleFormatter.RESET} — ${r.reason}"
    }
}
