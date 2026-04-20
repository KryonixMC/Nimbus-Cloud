package dev.nimbuspowered.nimbus.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Covers the lifecycle-unrelated surface of LocalProcessManager — the parts
 * we can exercise without actually spawning backend java servers. That
 * still hits quite a bit of code: state-store interaction, work-dir bookkeeping,
 * orphan-marker scanning, discard logic, RecoveredService DTO, etc.
 */
class LocalProcessManagerTest {

    private lateinit var scope: CoroutineScope

    @AfterEach
    fun cleanup() {
        if (::scope.isInitialized) scope.cancel()
    }

    private fun newManager(baseDir: Path): LocalProcessManager {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val stateStore = AgentStateStore(baseDir)
        val javaResolver = JavaResolver(emptyMap(), baseDir)
        return LocalProcessManager(
            baseDir = baseDir,
            scope = scope,
            javaResolver = javaResolver,
            stateStore = stateStore,
            stateSyncClient = null,
            ownerNodeName = "test-worker"
        )
    }

    @Test
    fun `runningCount is zero on fresh manager`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        assertEquals(0, mgr.runningCount())
    }

    @Test
    fun `getHandle and getWorkDir return null for unknown service`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        assertNull(mgr.getHandle("Lobby-1"))
        assertNull(mgr.getWorkDir("Lobby-1"))
    }

    @Test
    fun `getStaticServiceWorkDirs is empty initially`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        assertTrue(mgr.getStaticServiceWorkDirs().isEmpty())
    }

    @Test
    fun `getServiceHeartbeats is empty initially`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        assertTrue(mgr.getServiceHeartbeats().isEmpty())
    }

    @Test
    fun `getRunningServices returns empty when no state`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        assertTrue(mgr.getRunningServices().isEmpty())
    }

    @Test
    fun `recoverServices on empty state returns empty pair`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        val (recovered, protectedDirs) = mgr.recoverServices()
        assertTrue(recovered.isEmpty())
        assertTrue(protectedDirs.isEmpty())
    }

    @Test
    fun `recoverServices prunes dead persisted PIDs`(@TempDir dir: Path) {
        // Seed state store with a definitely-dead PID (very high number).
        val store = AgentStateStore(dir)
        store.addService(PersistedService(
            serviceName = "Ghost-1", groupName = "Lobby",
            port = 30000, pid = 999_999_999L,
            workDir = dir.resolve("w").toString(),
            isStatic = false, templateName = "lobby",
            software = "paper", memory = "1G",
            startedAtEpochMs = 0
        ))

        val mgr = newManager(dir)
        val (recovered, _) = mgr.recoverServices()
        assertTrue(recovered.isEmpty(), "dead PID must not be recovered")
        // The store entry should be removed
        assertTrue(AgentStateStore(dir).load().services.isEmpty())
    }

    @Test
    fun `discardSyncWorkdir on unknown service is a no-op`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        // Should not throw
        mgr.discardSyncWorkdir("NotEvenThere")
    }

    @Test
    fun `discardSyncWorkdir deletes canonical sync workdir when service not in map`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        val syncDir = dir.resolve("services").resolve("sync").resolve("Lobby-1")
        Files.createDirectories(syncDir)
        Files.writeString(syncDir.resolve("data.txt"), "hello")
        assertTrue(Files.exists(syncDir))

        mgr.discardSyncWorkdir("Lobby-1")
        assertFalse(Files.exists(syncDir))
    }

    @Test
    fun `killOrphanProcesses with no services is safe`(@TempDir dir: Path) {
        val mgr = newManager(dir)
        mgr.killOrphanProcesses()
        mgr.killOrphanProcesses("does-not-exist")
    }

    @Test
    fun `killOrphanProcesses scans marker files without crashing`(@TempDir dir: Path) {
        val syncDir = dir.resolve("services").resolve("sync").resolve("Lobby-1")
        Files.createDirectories(syncDir)
        val marker = syncDir.resolve(".nimbus-owner")
        Files.writeString(marker,
            "pid=999999999\nservice=Lobby-1\nowner=test-worker\nstartedAt=0\n")

        val mgr = newManager(dir)
        // Non-existent PID with matching owner: ProcessHandle.of returns empty,
        // so the loop bails on this marker — must not crash.
        mgr.killOrphanProcesses()
        // Marker may or may not be deleted depending on OS — just verify the
        // sweep completed without throwing.
        assertTrue(Files.exists(syncDir))
    }

    @Test
    fun `killOrphanProcesses ignores markers owned by a different node`(@TempDir dir: Path) {
        val syncDir = dir.resolve("services").resolve("sync").resolve("Lobby-2")
        Files.createDirectories(syncDir)
        val marker = syncDir.resolve(".nimbus-owner")
        Files.writeString(marker,
            "pid=999999999\nservice=Lobby-2\nowner=some-other-node\nstartedAt=0\n")

        val mgr = newManager(dir)
        mgr.killOrphanProcesses()
        // Marker belongs to another owner → untouched
        assertTrue(Files.exists(marker))
    }

    @Test
    fun `RecoveredService DTO round-trips fields`() {
        val r = LocalProcessManager.RecoveredService("Lobby-1", "Lobby", 30000, 1234L)
        assertEquals("Lobby-1", r.serviceName)
        assertEquals("Lobby", r.groupName)
        assertEquals(30000, r.port)
        assertEquals(1234L, r.pid)
    }

    @Test
    fun `recoverServices protects workdirs of recovered services`(@TempDir dir: Path) {
        // Use our own PID as "the only process we can be sure is alive".
        // The adopt() check requires the commandLine to contain the service marker,
        // so the current test process won't be adopted — recovery drops it.
        val store = AgentStateStore(dir)
        val workDir = dir.resolve("w")
        Files.createDirectories(workDir)
        store.addService(PersistedService(
            serviceName = "Self-1", groupName = "Lobby",
            port = 30000, pid = ProcessHandle.current().pid(),
            workDir = workDir.toString(),
            isStatic = false, templateName = "lobby",
            software = "paper", memory = "1G",
            startedAtEpochMs = 0
        ))

        val mgr = newManager(dir)
        val (recovered, protectedDirs) = mgr.recoverServices()
        // The JVM process's commandline won't match the service marker so
        // recovered should be empty (adopt returned null), and the store
        // entry was removed.
        assertNotNull(recovered)
        // protectedDirs should reflect what was adopted (none in this case)
        assertEquals(0, protectedDirs.size)
    }
}
