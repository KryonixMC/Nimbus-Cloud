package dev.nimbuspowered.nimbus.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Lightweight tests for LocalProcessHandle that use real OS processes
 * (echo/sleep) — still fast (<1s each) and portable between Linux and macOS.
 * Windows is skipped because the shell semantics differ.
 */
class LocalProcessHandleTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `isAlive is false before start`() {
        val h = LocalProcessHandle()
        assertFalse(h.isAlive())
        assertNull(h.pid())
        assertNull(h.exitCode())
    }

    @Test
    fun `start assigns pid and marks alive`(@TempDir dir: Path) = runBlocking {
        assumeFalse(isWindows, "shell script test only runs on unix")

        val h = LocalProcessHandle()
        h.start(dir, listOf("sh", "-c", "sleep 2"))
        try {
            // allow OS to register the process
            var pid: Long? = null
            repeat(20) {
                pid = h.pid()
                if (pid != null && pid!! > 0) return@repeat
                Thread.sleep(50)
            }
            assertNotNull(pid)
            assertTrue(h.isAlive())
        } finally {
            h.destroy()
        }
    }

    @Test
    fun `waitForReady returns false on timeout`(@TempDir dir: Path) = runBlocking {
        assumeFalse(isWindows, "shell script test only runs on unix")

        val h = LocalProcessHandle()
        h.setReadyPattern(Regex("NEVER_APPEARS"))
        h.start(dir, listOf("sh", "-c", "sleep 2"))
        try {
            val ready = h.waitForReady(1.seconds)
            assertFalse(ready)
        } finally {
            h.destroy()
        }
    }

    @Test
    fun `sendCommand on unstarted handle logs warning and does not throw`() = runBlocking {
        val h = LocalProcessHandle()
        // Must not throw — the implementation logs a warning when stdin is null
        h.sendCommand("say hello")
    }

    @Test
    fun `destroy on unstarted handle is safe`() {
        val h = LocalProcessHandle()
        h.destroy()
        assertFalse(h.isAlive())
    }

    @Test
    fun `adopt returns null for non-existent pid`() {
        val handle = LocalProcessHandle.adopt(999_999_999L, "Lobby-1")
        assertNull(handle)
    }

    @Test
    fun `adopt returns null when pid exists but commandLine does not match service marker`() {
        val myPid = ProcessHandle.current().pid()
        val adopted = LocalProcessHandle.adopt(myPid, "MyNonMatchingService")
        // Our own process's commandLine doesn't contain the service marker, so null.
        assertEquals(null, adopted)
    }

    @Test
    fun `pid and exitCode report something sane for a started process`(@TempDir dir: Path) = runBlocking {
        assumeFalse(isWindows, "shell script test only runs on unix")

        val h = LocalProcessHandle()
        h.start(dir, listOf("sh", "-c", "exit 7"))
        try {
            // let it finish
            val code = h.awaitExit()
            assertEquals(7, code)
            assertFalse(h.isAlive())
        } finally {
            h.destroy()
        }
    }
}
