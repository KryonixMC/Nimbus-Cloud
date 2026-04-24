package dev.nimbuspowered.nimbus.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupDiagnosticTest {

    @Test
    fun `port conflict is detected and port extracted`() {
        val tail = listOf(
            "[22:00:00] [Server thread/ERROR]: java.net.BindException: Address already in use",
            "Failed to bind to 0.0.0.0:30001"
        )
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("30001"), "should mention the port; got: $out")
        assertTrue(out.contains("already in use"))
    }

    @Test
    fun `port conflict without a port number falls back to generic hint`() {
        val tail = listOf("java.net.BindException: Address already in use")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("port"), out)
    }

    @Test
    fun `oom exit 137 is attributed to memory cap`() {
        val out = StartupDiagnostic.diagnose(listOf("killed by signal"), StartupDiagnostic.CrashContext.Exited(137))
        assertTrue(out.contains("OOM-killed"))
        assertTrue(out.contains("137"))
    }

    @Test
    fun `jvm oom error is detected`() {
        val tail = listOf("java.lang.OutOfMemoryError: Java heap space", "at foo.bar")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("JVM out of memory"))
    }

    @Test
    fun `missing jar message is recognized`() {
        val tail = listOf("Error: Unable to access jarfile paper.jar")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Server JAR missing"))
    }

    @Test
    fun `ready timeout without exit produces timeout-flavored diagnosis`() {
        val out = StartupDiagnostic.diagnose(
            listOf("[INFO] Loading libraries...", "[INFO] Starting minecraft server"),
            StartupDiagnostic.CrashContext.ReadyTimeout(120)
        )
        assertTrue(out.contains("READY pattern"), out)
        assertTrue(out.contains("120"))
    }

    @Test
    fun `java version mismatch is explained`() {
        val tail = listOf("UnsupportedClassVersionError: foo has been compiled by a more recent version of the Java Runtime")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Java version mismatch"))
    }

    @Test
    fun `session lock is called out`() {
        val tail = listOf("Failed to acquire directory lock: /srv/world/session.lock")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Session lock"))
    }

    @Test
    fun `generic non-zero exit produces fallback diagnosis`() {
        val out = StartupDiagnostic.diagnose(
            listOf("completely random log that matches nothing"),
            StartupDiagnostic.CrashContext.Exited(42)
        )
        assertEquals("Process exited with code 42 — see the attached log lines.", out)
    }

    @Test
    fun `patterns are matched case-insensitively`() {
        val tail = listOf("ADDRESS ALREADY IN USE: port 30050")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("30050"))
    }

    @Test
    fun `most recent port in the tail wins`() {
        val tail = listOf(
            "old port 20000 in some unrelated log",
            "Address already in use",
            "Failed to bind to :30123"
        )
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("30123"), "should prefer the most recent port; got $out")
    }

    @Test
    fun `timestamps like square-bracketed HH colon MM do not get read as ports`() {
        // Regression for the PORT_REGEX 2–5 digit matcher that previously caught ":30"
        // out of "[10:30]". Ports are now constrained to 4–5 digits.
        val tail = listOf(
            "[10:30] Server starting...",
            "java.net.BindException: Address already in use",
            "[22:00:05] Failed to bind to :25565"
        )
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("25565"), "should extract the real port, not a timestamp digit pair; got $out")
    }

    // -------------------------------------------------------------------------
    // EULA pattern
    // -------------------------------------------------------------------------

    @Test
    fun `EULA not accepted is detected`() {
        val tail = listOf("You need to agree to the EULA in order to run the server.")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("EULA"), "expected EULA message; got: $out")
    }

    @Test
    fun `EULA pattern is case-insensitive`() {
        val tail = listOf("you need to agree to the eula in order to run the server.")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("EULA"), "expected EULA message (lowercase input); got: $out")
    }

    // -------------------------------------------------------------------------
    // Alternative pattern variants
    // -------------------------------------------------------------------------

    @Test
    fun `bindexception keyword also triggers port-conflict diagnosis`() {
        val tail = listOf("java.net.BindException: Address already in use")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("port") || out.contains("already in use"), "expected port-conflict message; got: $out")
    }

    @Test
    fun `java heap space triggers jvm oom diagnosis`() {
        val tail = listOf("java.lang.OutOfMemoryError: Java heap space")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("JVM out of memory"), "expected OOM message for 'Java heap space'; got: $out")
    }

    @Test
    fun `error main class triggers missing jar diagnosis`() {
        // The pattern checks for the substring "error: main class" (case-insensitive).
        // The JVM emits this as "Error: Main class <name> not found" when a JAR's manifest
        // Main-Class attribute points to a class that cannot be found.
        val tail = listOf("Error: Main class com.example.Main not found or loaded in classpath")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Server JAR missing"), "expected missing-jar message for 'error: main class'; got: $out")
    }

    @Test
    fun `has been compiled by a more recent version triggers java version diagnosis`() {
        val tail = listOf("org.bukkit.craftbukkit.v1_20_R1.CraftServer has been compiled by a more recent version of the Java Runtime")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Java version mismatch"), "expected version-mismatch message; got: $out")
    }

    @Test
    fun `failed to acquire directory lock triggers session lock diagnosis`() {
        val tail = listOf("Failed to acquire directory lock: /srv/world")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Session lock"), "expected session-lock message for 'failed to acquire directory lock'; got: $out")
    }

    // -------------------------------------------------------------------------
    // Priority / first-match semantics
    // -------------------------------------------------------------------------

    @Test
    fun `port conflict takes priority over oom when both are present`() {
        // Port conflict is the first pattern in PATTERNS — it should win over OOM.
        val tail = listOf(
            "java.lang.OutOfMemoryError: Java heap space",
            "java.net.BindException: Address already in use",
            "Failed to bind to :30200"
        )
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("30200") || out.contains("already in use"),
            "port-conflict pattern should win over OOM; got: $out")
    }

    @Test
    fun `oom takes priority over eula when oom appears and port conflict absent`() {
        // OOM pattern precedes EULA in PATTERNS, so OOM wins when there is no port conflict.
        val tail = listOf(
            "java.lang.OutOfMemoryError: Java heap space",
            "You need to agree to the EULA in order to run the server."
        )
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("JVM out of memory"),
            "OOM pattern should win over EULA; got: $out")
    }

    @Test
    fun `specific pattern takes priority over ready timeout fallback`() {
        // When the log contains an OOM error AND it's a ReadyTimeout context the
        // specific OOM pattern should be returned, not the generic timeout fallback.
        val tail = listOf("java.lang.OutOfMemoryError: Java heap space")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.ReadyTimeout(120))
        assertTrue(out.contains("JVM out of memory"),
            "specific OOM match should beat ReadyTimeout fallback; got: $out")
    }

    // -------------------------------------------------------------------------
    // Edge cases and guarantees
    // -------------------------------------------------------------------------

    @Test
    fun `empty log lines with exit code 0 produce a non-empty fallback`() {
        val out = StartupDiagnostic.diagnose(emptyList(), StartupDiagnostic.CrashContext.Exited(0))
        assertTrue(out.isNotBlank(), "diagnosis must never be blank; got: '$out'")
        assertTrue(out.contains("0"), "fallback should mention exit code 0; got: $out")
    }

    @Test
    fun `diagnosis is never null or blank for any exit code`() {
        for (code in listOf(0, 1, 2, 127, 137, 255)) {
            val out = StartupDiagnostic.diagnose(emptyList(), StartupDiagnostic.CrashContext.Exited(code))
            assertTrue(out.isNotBlank(), "diagnosis must never be blank for exit code $code; got: '$out'")
        }
    }

    @Test
    fun `diagnosis is never null or blank for ready timeout`() {
        val out = StartupDiagnostic.diagnose(emptyList(), StartupDiagnostic.CrashContext.ReadyTimeout(60))
        assertTrue(out.isNotBlank(), "diagnosis must never be blank for ReadyTimeout; got: '$out'")
    }

    @Test
    fun `ready timeout fallback includes timeout seconds and READY keyword`() {
        val out = StartupDiagnostic.diagnose(
            listOf("completely unrelated log line"),
            StartupDiagnostic.CrashContext.ReadyTimeout(180)
        )
        assertTrue(out.contains("READY"), "should mention READY; got: $out")
        assertTrue(out.contains("180"), "should mention the timeout value; got: $out")
    }
}
