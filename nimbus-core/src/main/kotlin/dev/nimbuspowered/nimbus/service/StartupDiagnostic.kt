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
                if (port != null) "Port $port ist bereits belegt — prüfe mit `ss -tlnp | grep $port`."
                else "Ein Port ist bereits belegt — prüfe `ss -tlnp`, ob ein anderer Prozess Nimbus-Ports hält."
            }
        ),
        // JVM OOM — separate from cgroup-OOM (which shows up via exit code 137).
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("outofmemoryerror") || tail.anyContainsIgnoreCase("java heap space") },
            render = { _, _ -> "JVM-OOM — erhöhe `[group.resources] memory` oder prüfe auf Plugin-Speicher-Leak." }
        ),
        // OOM-killed by kernel (cgroup MemoryMax hit or host OOM killer)
        Pattern(
            matches = { _, ctx -> ctx is CrashContext.Exited && ctx.exitCode == 137 },
            render = { _, _ -> "Prozess wurde OOM-gekillt (Exit 137) — entweder `[group.sandbox] memory_limit_mb` zu niedrig oder Host hat keinen RAM mehr." }
        ),
        // Missing JAR
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("unable to access jarfile") || tail.anyContainsIgnoreCase("error: main class") },
            render = { _, _ -> "Server-JAR fehlt oder ist korrupt — Template evtl. defekt, `service redeploy` versuchen." }
        ),
        // EULA (shouldn't happen — Nimbus sets it automatically — but if it does it's a clear sign of a deeper issue)
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("you need to agree to the eula") },
            render = { _, _ -> "EULA nicht akzeptiert — Nimbus sollte das automatisch erledigen; bitte als Bug reporten." }
        ),
        // Java version mismatch
        Pattern(
            matches = { tail, _ ->
                tail.anyContainsIgnoreCase("unsupportedclassversionerror") ||
                        tail.anyContainsIgnoreCase("has been compiled by a more recent version")
            },
            render = { _, _ -> "Java-Version passt nicht — der Server braucht ein neueres JDK; `[java]`-Sektion in `nimbus.toml` prüfen." }
        ),
        // Corrupt world / Paper DirectoryLock
        Pattern(
            matches = { tail, _ -> tail.anyContainsIgnoreCase("failed to acquire directory lock") || tail.anyContainsIgnoreCase("session.lock") },
            render = { _, _ -> "Session-Lock liegt noch — ein Vorgänger-Prozess hält vermutlich das Arbeitsverzeichnis; `orphan sweep` oder Reboot." }
        ),
        // Ready timeout without any of the above
        Pattern(
            matches = { _, ctx -> ctx is CrashContext.ReadyTimeout },
            render = { _, ctx ->
                val t = (ctx as? CrashContext.ReadyTimeout)?.timeoutSeconds
                "Service hat das READY-Pattern in ${t ?: "?"}s nicht erreicht — evtl. `[group.ready_pattern]` anpassen oder Timeout erhöhen."
            }
        )
    )

    fun diagnose(tail: List<String>, ctx: CrashContext): String {
        for (p in PATTERNS) {
            if (p.matches(tail, ctx)) return p.render(tail, ctx)
        }
        return when (ctx) {
            is CrashContext.Exited -> "Prozess beendet mit Exit-Code ${ctx.exitCode} — siehe angehängte Log-Zeilen."
            is CrashContext.ReadyTimeout -> "Service ist nach dem Timeout noch nicht READY geworden — siehe angehängte Log-Zeilen."
        }
    }

    private fun List<String>.anyContainsIgnoreCase(needle: String): Boolean {
        val lower = needle.lowercase()
        return any { it.lowercase().contains(lower) }
    }

    private val PORT_REGEX = Regex("""(?i)(?:port|:)\s*(\d{2,5})""")

    private fun extractPort(tail: List<String>): Int? {
        for (line in tail.reversed()) {
            val m = PORT_REGEX.find(line) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n in 1..65535) return n
        }
        return null
    }
}
