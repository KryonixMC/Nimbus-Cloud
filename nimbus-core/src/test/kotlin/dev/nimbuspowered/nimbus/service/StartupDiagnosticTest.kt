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
        assertTrue(out.contains("belegt"))
    }

    @Test
    fun `port conflict without a port number falls back to generic hint`() {
        val tail = listOf("java.net.BindException: Address already in use")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Port"), out)
    }

    @Test
    fun `oom exit 137 is attributed to memory cap`() {
        val out = StartupDiagnostic.diagnose(listOf("killed by signal"), StartupDiagnostic.CrashContext.Exited(137))
        assertTrue(out.contains("OOM-gekillt"))
        assertTrue(out.contains("137"))
    }

    @Test
    fun `jvm oom error is detected`() {
        val tail = listOf("java.lang.OutOfMemoryError: Java heap space", "at foo.bar")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("JVM-OOM"))
    }

    @Test
    fun `missing jar message is recognized`() {
        val tail = listOf("Error: Unable to access jarfile paper.jar")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Server-JAR fehlt"))
    }

    @Test
    fun `ready timeout without exit produces timeout-flavored diagnosis`() {
        val out = StartupDiagnostic.diagnose(
            listOf("[INFO] Loading libraries...", "[INFO] Starting minecraft server"),
            StartupDiagnostic.CrashContext.ReadyTimeout(120)
        )
        assertTrue(out.contains("READY-Pattern"), out)
        assertTrue(out.contains("120"))
    }

    @Test
    fun `java version mismatch is explained`() {
        val tail = listOf("UnsupportedClassVersionError: foo has been compiled by a more recent version of the Java Runtime")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Java-Version"))
    }

    @Test
    fun `session lock is called out`() {
        val tail = listOf("Failed to acquire directory lock: /srv/world/session.lock")
        val out = StartupDiagnostic.diagnose(tail, StartupDiagnostic.CrashContext.Exited(1))
        assertTrue(out.contains("Session-Lock"))
    }

    @Test
    fun `generic non-zero exit produces fallback diagnosis`() {
        val out = StartupDiagnostic.diagnose(
            listOf("completely random log that matches nothing"),
            StartupDiagnostic.CrashContext.Exited(42)
        )
        assertEquals("Prozess beendet mit Exit-Code 42 — siehe angehängte Log-Zeilen.", out)
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
}
