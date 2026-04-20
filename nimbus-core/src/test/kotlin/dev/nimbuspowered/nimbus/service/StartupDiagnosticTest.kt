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
}
