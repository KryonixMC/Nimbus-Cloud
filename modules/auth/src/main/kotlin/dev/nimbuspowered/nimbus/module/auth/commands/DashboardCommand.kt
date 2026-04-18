package dev.nimbuspowered.nimbus.module.auth.commands

import dev.nimbuspowered.nimbus.module.CommandCaller
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.ModuleCommand
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.service.LoginChallengeService
import dev.nimbuspowered.nimbus.module.auth.service.SessionService
import java.util.UUID

/**
 * Bridge-facing command that implements `/nimbus dashboard …` for in-game
 * players. Replaces the old standalone `/dashboard` command in
 * `plugins/auth-velocity/` so auth follows the same pattern as every other
 * module (`/nimbus punish …`, `/nimbus perms …`).
 *
 * Caller identity is required — the subcommands all operate on the invoking
 * player's own UUID. Console / non-player callers get a "player-only" error.
 */
class DashboardCommand(
    private val challengeService: LoginChallengeService,
    private val sessionService: SessionService,
    private val publicUrlSupplier: () -> String,
    private val configSupplier: () -> AuthConfig
) : ModuleCommand {

    override val name: String = "dashboard"
    override val description: String = "Request a dashboard login code or magic link"
    override val usage: String = "dashboard <login [link] | sessions | logout-all>"
    override val permission: String = "nimbus.cloud.dashboard"

    override val subcommandMeta: List<SubcommandMeta> = listOf(
        SubcommandMeta("login", "Get a 6-digit dashboard login code", "dashboard login"),
        SubcommandMeta("login link", "Get a clickable magic-link URL", "dashboard login link"),
        SubcommandMeta("sessions", "List your active dashboard sessions", "dashboard sessions"),
        SubcommandMeta("logout-all", "Revoke all your dashboard sessions", "dashboard logout-all")
    )

    override suspend fun execute(args: List<String>) {
        // Console-only invocation — the command is meaningful only per-player,
        // so we no-op rather than silently issuing a code for a phantom UUID.
    }

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        // Called without caller context (e.g. REPL). Surface a helpful error.
        output.error("This command must be run by a player.")
        return true
    }

    override suspend fun execute(args: List<String>, output: CommandOutput, caller: CommandCaller?): Boolean {
        if (caller == null) {
            output.error("This command must be run by a player (no caller identity).")
            return true
        }

        val sub = args.firstOrNull()?.lowercase() ?: "login"
        when (sub) {
            "login" -> handleLogin(args.drop(1), output, caller)
            "sessions" -> handleSessions(output, caller)
            "logout-all", "logoutall" -> handleLogoutAll(output, caller)
            else -> {
                output.error("Unknown subcommand: $sub")
                output.info("Usage: /nimbus $usage")
            }
        }
        return true
    }

    private suspend fun handleLogin(args: List<String>, output: CommandOutput, caller: CommandCaller) {
        val wantsLink = args.firstOrNull()?.equals("link", ignoreCase = true) == true

        if (wantsLink) {
            if (!configSupplier().loginChallenge.magicLinkEnabled) {
                output.error("Magic link login is disabled on this network. Use '/nimbus dashboard login' for a code instead.")
                return
            }
            try {
                val issued = challengeService.issueMagicLink(caller.uuid, caller.name.take(16), originIp = null)
                val base = publicUrlSupplier().trimEnd('/')
                val url = "$base/login?link=${issued.raw}"
                output.success("Your dashboard magic link:")
                // `text` lets the Bridge render it as a clickable element on the
                // proxy side where it re-renders Component output — the
                // CollectorOutput passes it through verbatim.
                output.text(url)
                val ttl = configSupplier().loginChallenge.magicLinkTtlSeconds
                output.info("Valid for ${ttl}s, single use.")
            } catch (e: LoginChallengeService.RateLimitedException) {
                output.error("Too many login requests — try again in a minute.")
            } catch (e: IllegalStateException) {
                output.error(e.message ?: "Magic link disabled.")
            }
            return
        }

        try {
            val issued = challengeService.issueCode(caller.uuid, caller.name.take(16))
            val ttl = configSupplier().loginChallenge.codeTtlSeconds
            output.success("Your dashboard login code: ${issued.raw}")
            output.info("Enter it at ${publicUrlSupplier().trimEnd('/')}/login — valid for ${ttl}s.")
        } catch (e: LoginChallengeService.RateLimitedException) {
            output.error("Too many login requests — try again in a minute.")
        }
    }

    private suspend fun handleSessions(output: CommandOutput, caller: CommandCaller) {
        val uuid = parseUuid(caller.uuid, output) ?: return
        val sessions = sessionService.listForUser(uuid)
        if (sessions.isEmpty()) {
            output.info("No active dashboard sessions.")
            return
        }
        output.header("Active dashboard sessions (${sessions.size})")
        sessions.forEach { s ->
            val ip = s.ip ?: "unknown"
            val ageSec = (System.currentTimeMillis() - s.lastUsedAt) / 1000
            output.item("${s.sessionId}  ip=$ip  last-used ${ageSec}s ago  method=${s.loginMethod}")
        }
    }

    private suspend fun handleLogoutAll(output: CommandOutput, caller: CommandCaller) {
        val uuid = parseUuid(caller.uuid, output) ?: return
        val revoked = sessionService.revokeAll(uuid)
        output.success("Revoked $revoked dashboard session(s).")
    }

    private fun parseUuid(raw: String, output: CommandOutput): UUID? {
        return runCatching { UUID.fromString(raw) }.getOrElse {
            output.error("Invalid caller UUID.")
            null
        }
    }
}
