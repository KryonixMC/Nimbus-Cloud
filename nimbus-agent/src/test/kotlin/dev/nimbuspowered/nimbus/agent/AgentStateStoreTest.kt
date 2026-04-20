package dev.nimbuspowered.nimbus.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class AgentStateStoreTest {

    private fun sampleService(name: String, port: Int = 30000) = PersistedService(
        serviceName = name,
        groupName = "Lobby",
        port = port,
        pid = 1234,
        workDir = "/tmp/$name",
        isStatic = false,
        templateName = "lobby",
        software = "paper",
        memory = "2G",
        startedAtEpochMs = 1_700_000_000_000L,
        syncEnabled = false,
        syncExcludes = emptyList(),
        isDedicated = false
    )

    @Test
    fun `load returns empty state when file missing`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        val state = store.load()
        assertEquals(1, state.version)
        assertTrue(state.services.isEmpty())
    }

    @Test
    fun `addService persists and load returns the service`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        store.addService(sampleService("Lobby-1"))
        val reloaded = AgentStateStore(dir).load()
        assertEquals(1, reloaded.services.size)
        assertEquals("Lobby-1", reloaded.services.first().serviceName)
    }

    @Test
    fun `addService deduplicates by name`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        store.addService(sampleService("Lobby-1", port = 30000))
        store.addService(sampleService("Lobby-1", port = 30001))
        val state = store.load()
        assertEquals(1, state.services.size)
        assertEquals(30001, state.services.first().port)
    }

    @Test
    fun `removeService drops matching entry`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        store.addService(sampleService("Lobby-1"))
        store.addService(sampleService("Lobby-2", port = 30001))
        store.removeService("Lobby-1")
        val state = store.load()
        assertEquals(1, state.services.size)
        assertEquals("Lobby-2", state.services.first().serviceName)
    }

    @Test
    fun `removeService on absent name is noop`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        store.addService(sampleService("Lobby-1"))
        store.removeService("Nope")
        assertEquals(1, store.load().services.size)
    }

    @Test
    fun `clear deletes state file`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        store.addService(sampleService("Lobby-1"))
        val stateFile = dir.resolve("state").resolve("services.json")
        assertTrue(stateFile.exists())
        store.clear()
        assertFalse(stateFile.exists())
    }

    @Test
    fun `load recovers from corrupt state as empty`(@TempDir dir: Path) {
        val stateDir = dir.resolve("state")
        java.nio.file.Files.createDirectories(stateDir)
        java.nio.file.Files.writeString(stateDir.resolve("services.json"), "{not json")
        val state = AgentStateStore(dir).load()
        assertTrue(state.services.isEmpty())
    }

    @Test
    fun `save round-trips sync fields`(@TempDir dir: Path) {
        val store = AgentStateStore(dir)
        val svc = sampleService("Dyn-1").copy(
            syncEnabled = true,
            syncExcludes = listOf("*.log", "cache/"),
            isDedicated = true
        )
        store.addService(svc)
        val reloaded = AgentStateStore(dir).load()
        val got = reloaded.services.first()
        assertTrue(got.syncEnabled)
        assertEquals(listOf("*.log", "cache/"), got.syncExcludes)
        assertTrue(got.isDedicated)
    }
}
