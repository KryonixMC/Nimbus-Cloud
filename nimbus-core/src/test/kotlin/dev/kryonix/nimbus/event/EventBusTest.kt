package dev.kryonix.nimbus.event

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBusTest {

    @Test
    fun `emit event is received by subscriber`() = runTest {
        val bus = EventBus(this)
        val received = mutableListOf<NimbusEvent>()

        val job = bus.on<NimbusEvent.ServiceReady> { received.add(it) }
        advanceUntilIdle()

        bus.emit(NimbusEvent.ServiceReady("Lobby-1", "Lobby"))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals("Lobby-1", (received[0] as NimbusEvent.ServiceReady).serviceName)
        job.cancel()
    }

    @Test
    fun `on only receives matching event type`() = runTest {
        val bus = EventBus(this)
        val readyEvents = mutableListOf<NimbusEvent.ServiceReady>()
        val crashEvents = mutableListOf<NimbusEvent.ServiceCrashed>()
        val jobs = mutableListOf<Job>()

        jobs += bus.on<NimbusEvent.ServiceReady> { readyEvents.add(it) }
        jobs += bus.on<NimbusEvent.ServiceCrashed> { crashEvents.add(it) }
        advanceUntilIdle()

        bus.emit(NimbusEvent.ServiceReady("Lobby-1", "Lobby"))
        bus.emit(NimbusEvent.ServiceCrashed("BedWars-1", 1, 0))
        bus.emit(NimbusEvent.ServiceReady("Lobby-2", "Lobby"))
        advanceUntilIdle()

        assertEquals(2, readyEvents.size)
        assertEquals(1, crashEvents.size)
        assertEquals("BedWars-1", crashEvents[0].serviceName)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `multiple subscribers all receive same event`() = runTest {
        val bus = EventBus(this)
        val subscriber1 = mutableListOf<NimbusEvent.ScaleUp>()
        val subscriber2 = mutableListOf<NimbusEvent.ScaleUp>()
        val subscriber3 = mutableListOf<NimbusEvent.ScaleUp>()
        val jobs = mutableListOf<Job>()

        jobs += bus.on<NimbusEvent.ScaleUp> { subscriber1.add(it) }
        jobs += bus.on<NimbusEvent.ScaleUp> { subscriber2.add(it) }
        jobs += bus.on<NimbusEvent.ScaleUp> { subscriber3.add(it) }
        advanceUntilIdle()

        bus.emit(NimbusEvent.ScaleUp("Lobby", 1, 3, "high demand"))
        advanceUntilIdle()

        assertEquals(1, subscriber1.size)
        assertEquals(1, subscriber2.size)
        assertEquals(1, subscriber3.size)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `emit various NimbusEvent subtypes`() = runTest {
        val bus = EventBus(this)
        val allEvents = mutableListOf<NimbusEvent>()

        val job = bus.on<NimbusEvent> { allEvents.add(it) }
        advanceUntilIdle()

        bus.emit(NimbusEvent.ServiceStarting("Lobby-1", "Lobby", 30000))
        bus.emit(NimbusEvent.ServiceReady("Lobby-1", "Lobby"))
        bus.emit(NimbusEvent.ServiceCrashed("BedWars-1", 137, 1))
        bus.emit(NimbusEvent.ScaleUp("Lobby", 1, 2, "player threshold"))
        bus.emit(NimbusEvent.ScaleDown("BedWars", "BedWars-3", "idle"))
        bus.emit(NimbusEvent.GroupCreated("SkyWars"))
        bus.emit(NimbusEvent.ConfigReloaded(5))
        advanceUntilIdle()

        assertEquals(7, allEvents.size)
        assertTrue(allEvents[0] is NimbusEvent.ServiceStarting)
        assertTrue(allEvents[1] is NimbusEvent.ServiceReady)
        assertTrue(allEvents[2] is NimbusEvent.ServiceCrashed)
        assertTrue(allEvents[3] is NimbusEvent.ScaleUp)
        assertTrue(allEvents[4] is NimbusEvent.ScaleDown)
        assertTrue(allEvents[5] is NimbusEvent.GroupCreated)
        assertTrue(allEvents[6] is NimbusEvent.ConfigReloaded)
        job.cancel()
    }

    @Test
    fun `rapid emit of many events are all collected`() = runTest {
        val bus = EventBus(this)
        val received = mutableListOf<NimbusEvent.ServiceReady>()

        val job = bus.on<NimbusEvent.ServiceReady> { received.add(it) }
        advanceUntilIdle()

        val count = 100
        for (i in 1..count) {
            bus.emit(NimbusEvent.ServiceReady("Service-$i", "Group"))
        }
        advanceUntilIdle()

        assertEquals(count, received.size)
        for (i in 1..count) {
            assertEquals("Service-$i", received[i - 1].serviceName)
        }
        job.cancel()
    }

    @Test
    fun `on returns a cancellable Job`() = runTest {
        val bus = EventBus(this)
        val received = mutableListOf<NimbusEvent.ServiceReady>()

        val job = bus.on<NimbusEvent.ServiceReady> { received.add(it) }
        advanceUntilIdle()

        bus.emit(NimbusEvent.ServiceReady("Lobby-1", "Lobby"))
        advanceUntilIdle()

        assertEquals(1, received.size)

        job.cancel()
        advanceUntilIdle()

        bus.emit(NimbusEvent.ServiceReady("Lobby-2", "Lobby"))
        advanceUntilIdle()

        // Should still be 1 since the job was cancelled
        assertEquals(1, received.size)
    }
}
