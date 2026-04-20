package dev.nimbuspowered.nimbus.service

/**
 * Classifier that turns a service's stdout tail + exit code into a single
 * operator-readable diagnosis sentence. Patterns are matched in order of
 * specificity; the first hit wins. A generic fallback guarantees every
 * crash produces *some* sentence, never null.
 *
 * Add new patterns to [PATTERNS] — each is a predicate + message. Keep the
 * list small; noisy matchers dilute signal.
 */
object StartupDiagnostic {

    /**
     * Either an explicit exit code (JVM exited) or null (timeout without
     * process exit — service is still running but never emitted the ready
     * pattern within the configured timeout).
     */
    sealed interface CrashContext {
        val timeoutSeconds: Long?

        data class Exited(val exitCode: Int) : CrashContext {
            override val timeoutSeconds: Long? = null
        }

        data class ReadyTimeout(override val timeoutSeconds: Long) : CrashContext {
            val exitCode: Int? = null
        }
    }

    private data class Pattern(
        val matches: (tail: List<String>, ctx: CrashContext) -> Boolean,
        val render: (tail: List<String>, ctx: CrashContext) -> String
    )

    private val PATTERNS: List<Pattern> = listOf(
        // Port conflict — most common real-world crash, highest signal value.
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("address already in use") || tail.anyContainsIgnoreCase("bindexception") },
            render = { tail, _ ->
                val port = extractPort(tail)
                if (port != null) "Port $port is already in use — check with `ss -tlnp | grep $port`."
                else "A port is already in use — check `ss -tlnp` for another process holding Nimbus ports."
            }
        ),
        // JVM OOM — separate from cgroup-OOM (which shows up via exit code 137).
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("outofmemoryerror") || tail.anyContainsIgnoreCase("java heap space") },
            render = { _, _ -> "JVM out of memory — raise `[group.resources] memory` or investigate a plugin memory leak." }
        ),
        // OOM-killed by kernel (cgroup MemoryMax hit or host OOM killer)
        Pattern(
            matches = { _, ctx -> ctx is CrashContext.Exited && ctx.exitCode == 137 },
            render = { _, _ -> "Process OOM-killed (exit 137) — either `[group.sandbox] memory_limit_mb` is too low or the host is out of RAM." }
        ),
        // Missing JAR
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("unable to access jarfile") || tail.anyContainsIgnoreCase("error: main class") },
            render = { _, _ -> "Server JAR missing or corrupt — template may be damaged, try `service redeploy`." }
        ),
        // EULA (shouldn't happen — Nimbus sets it automatically — but if it does it's a clear sign of a deeper issue)
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("you need to agree to the eula") },
            render = { _, _ -> "EULA not accepted — Nimbus should handle this automatically; please report as a bug." }
        ),
        // Java version mismatch
        Pattern(
            matches = { tail, _ ->
                tail.anyContainsIgnoreCase("unsupportedclassversionerror") ||
                        tail.anyContainsIgnoreCase("has been compiled by a more recent version")
            },
            render = { _, _ -> "Java version mismatch — the server needs a newer JDK; check the `[java]` section in `nimbus.toml`." }
        ),
        // Corrupt world / Paper DirectoryLock
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("failed to acquire directory lock") || tail.anyContainsIgnoreCase("session.lock") },
            render = { _, _ -> "Session lock still held — a previous process likely has the working directory; run `orphan sweep` or reboot." }
        ),
        // Ready timeout without any of the above
        Pattern(
            matches = { _, ctx -> ctx is CrashContext.ReadyTimeout },
            render = { _, ctx ->
                val t = (ctx as? CrashContext.ReadyTimeout)?.timeoutSeconds
                "Service did not reach the READY pattern within ${t ?: "?"}s — adjust `[group.ready_pattern]` or raise the timeout."
            }
        )
    )

    fun diagnose(tail: List<String>, ctx: CrashContext): String {
        for (p in PATTERNS) {
            if (p.matches(tail, ctx)) return p.render(tail, ctx)
        }
        return when (ctx) {
            is CrashContext.Exited -> "Process exited with code ${ctx.exitCode} — see the attached log lines."
            is CrashContext.ReadyTimeout -> "Service did not become READY before the timeout — see the attached log lines."
        }
    }

    private fun List<String>.anyContainsIgnoreCase(needle: String): Boolean {
        val lower = needle.lowercase()
        return any { it.lowercase().contains(lower) }
    }

    // Port matcher: 4–5 digit ports only. Dropping 2–3 digit ports avoids
    // matching "[10:30]"-style timestamps and narrows to the realistic
    // Minecraft/backend range (1024+ in practice, 25565/30000 by default).
    private val PORT_REGEX = Regex("""(?i)(?:port|bind(?:\s+to)?|:)\s*(\d{4,5})""")

    private fun extractPort(tail: List<String>): Int? {
        for (line in tail.reversed()) {
            val m = PORT_REGEX.find(line) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..65535) return n
        }
        return null
    }
}
