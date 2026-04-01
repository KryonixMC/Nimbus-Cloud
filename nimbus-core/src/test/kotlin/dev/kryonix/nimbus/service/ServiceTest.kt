package dev.kryonix.nimbus.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ServiceTest {

    private lateinit var service: Service

    @BeforeEach
    fun setUp() {
        service = Service(
            name = "Lobby-1",
            groupName = "Lobby",
            port = 30001,
            workingDirectory = Path.of("/tmp/nimbus/Lobby-1")
        )
    }

    @Test
    fun `service initializes with correct properties`() {
        assertEquals("Lobby-1", service.name)
        assertEquals("Lobby", service.groupName)
        assertEquals(30001, service.port)
        assertEquals(ServiceState.PREPARING, service.state)
        assertNull(service.pid)
        assertNull(service.customState)
        assertEquals(0, service.playerCount)
        assertNull(service.startedAt)
        assertEquals(0, service.restartCount)
        assertFalse(service.isStatic)
    }

    @Test
    fun `service initializes with custom initial state`() {
        val svc = Service(
            name = "BedWars-1",
            groupName = "BedWars",
            port = 30002,
            initialState = ServiceState.STOPPED,
            workingDirectory = Path.of("/tmp/nimbus/BedWars-1")
        )
        assertEquals(ServiceState.STOPPED, svc.state)
    }

    // --- Valid transitions ---

    @Test
    fun `PREPARING to STARTING is valid`() {
        assertTrue(service.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.STARTING, service.state)
    }

    @Test
    fun `PREPARING to STOPPED is valid`() {
        assertTrue(service.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, service.state)
    }

    @Test
    fun `STARTING to READY is valid`() {
        service.transitionTo(ServiceState.STARTING)
        assertTrue(service.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.READY, service.state)
    }

    @Test
    fun `STARTING to CRASHED is valid`() {
        service.transitionTo(ServiceState.STARTING)
        assertTrue(service.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.CRASHED, service.state)
    }

    @Test
    fun `STARTING to STOPPED is valid`() {
        service.transitionTo(ServiceState.STARTING)
        assertTrue(service.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, service.state)
    }

    @Test
    fun `READY to STOPPING is valid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertTrue(service.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STOPPING, service.state)
    }

    @Test
    fun `READY to CRASHED is valid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertTrue(service.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.CRASHED, service.state)
    }

    @Test
    fun `STOPPING to STOPPED is valid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        service.transitionTo(ServiceState.STOPPING)
        assertTrue(service.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.STOPPED, service.state)
    }

    @Test
    fun `CRASHED to PREPARING is valid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.CRASHED)
        assertTrue(service.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.PREPARING, service.state)
    }

    // --- Invalid transitions ---

    @Test
    fun `PREPARING to READY is invalid`() {
        assertFalse(service.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.PREPARING, service.state)
    }

    @Test
    fun `PREPARING to STOPPING is invalid`() {
        assertFalse(service.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.PREPARING, service.state)
    }

    @Test
    fun `PREPARING to CRASHED is invalid`() {
        assertFalse(service.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.PREPARING, service.state)
    }

    @Test
    fun `PREPARING to PREPARING is invalid`() {
        assertFalse(service.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.PREPARING, service.state)
    }

    @Test
    fun `STARTING to STARTING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        assertFalse(service.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.STARTING, service.state)
    }

    @Test
    fun `STARTING to PREPARING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        assertFalse(service.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.STARTING, service.state)
    }

    @Test
    fun `STARTING to STOPPING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        assertFalse(service.transitionTo(ServiceState.STOPPING))
        assertEquals(ServiceState.STARTING, service.state)
    }

    @Test
    fun `READY to PREPARING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertFalse(service.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.READY, service.state)
    }

    @Test
    fun `READY to STARTING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertFalse(service.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.READY, service.state)
    }

    @Test
    fun `READY to READY is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertFalse(service.transitionTo(ServiceState.READY))
        assertEquals(ServiceState.READY, service.state)
    }

    @Test
    fun `READY to STOPPED is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        assertFalse(service.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.READY, service.state)
    }

    @Test
    fun `STOPPING to PREPARING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        service.transitionTo(ServiceState.STOPPING)
        assertFalse(service.transitionTo(ServiceState.PREPARING))
        assertEquals(ServiceState.STOPPING, service.state)
    }

    @Test
    fun `STOPPING to CRASHED is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.READY)
        service.transitionTo(ServiceState.STOPPING)
        assertFalse(service.transitionTo(ServiceState.CRASHED))
        assertEquals(ServiceState.STOPPING, service.state)
    }

    @Test
    fun `CRASHED to STOPPED is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.CRASHED)
        assertFalse(service.transitionTo(ServiceState.STOPPED))
        assertEquals(ServiceState.CRASHED, service.state)
    }

    @Test
    fun `CRASHED to STARTING is invalid`() {
        service.transitionTo(ServiceState.STARTING)
        service.transitionTo(ServiceState.CRASHED)
        assertFalse(service.transitionTo(ServiceState.STARTING))
        assertEquals(ServiceState.CRASHED, service.state)
    }

    // --- STOPPED is a dead-end ---

    @Test
    fun `STOPPED allows no transitions`() {
        service.transitionTo(ServiceState.STOPPED)
        for (state in ServiceState.entries) {
            assertFalse(service.transitionTo(state), "STOPPED should not transition to $state")
        }
        assertEquals(ServiceState.STOPPED, service.state)
    }
}
