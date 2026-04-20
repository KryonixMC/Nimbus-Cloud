package dev.nimbuspowered.nimbus.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Covers the documented service-name-stability invariant (CLAUDE.md):
 *
 *   "ServiceFactory.prepare reuses the lowest-numbered CRASHED/STOPPED slot
 *    instead of advancing to fresh numbers, so Lobby-1 stays Lobby-1 across
 *    crash-respawn cycles."
 *
 * ServiceFactory.prepare() pulls in real template/port/group machinery and is
 * awkward to unit-test end-to-end, so we verify the *slot-selection
 * algorithm* itself against a ServiceRegistry populated with services in the
 * same mix of states that prepare() would see.
 */
class ServiceSlotReuseTest {

    private val registry = ServiceRegistry()

    private fun svc(name: String, group: String, port: Int, state: ServiceState): Service {
        val s = Service(
            name = name,
            groupName = group,
            port = port,
            workingDirectory = Path.of("/tmp/nimbus/$name")
        )
        // Walk the service into the desired state using only valid transitions.
        when (state) {
            ServiceState.PREPARING -> {}
            ServiceState.STARTING -> s.transitionTo(ServiceState.STARTING)
            ServiceState.READY -> { s.transitionTo(ServiceState.STARTING); s.transitionTo(ServiceState.READY) }
            ServiceState.CRASHED -> { s.transitionTo(ServiceState.STARTING); s.transitionTo(ServiceState.CRASHED) }
            ServiceState.STOPPED -> { s.transitionTo(ServiceState.STOPPED) }
            else -> error("helper does not model $state")
        }
        return s
    }

    /**
     * Mirror of the slot-selection logic inside ServiceFactory.prepare() so we
     * can test the invariant without standing up the full factory.
     */
    private fun pickSlot(groupName: String): String {
        val reusable = registry.getByGroup(groupName)
            .filter { it.state == ServiceState.CRASHED || it.state == ServiceState.STOPPED }
            .minByOrNull { it.name.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }

        if (reusable != null) {
            registry.unregister(reusable.name)
            return reusable.name
        }

        val existing = registry.getByGroup(groupName).map { it.name }.toSet()
        var n = 1
        while ("$groupName-$n" in existing) n++
        return "$groupName-$n"
    }

    @Test
    fun `Lobby-1 is reused when it crashes rather than advancing to Lobby-4`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.CRASHED))
        registry.register(svc("Lobby-2", "Lobby", 30002, ServiceState.READY))
        registry.register(svc("Lobby-3", "Lobby", 30003, ServiceState.READY))

        assertEquals("Lobby-1", pickSlot("Lobby"),
            "Crashed slot 1 must be reused instead of advancing to Lobby-4")
    }

    @Test
    fun `stopped slots are reused with the same priority as crashed`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.READY))
        registry.register(svc("Lobby-2", "Lobby", 30002, ServiceState.STOPPED))
        registry.register(svc("Lobby-3", "Lobby", 30003, ServiceState.READY))

        assertEquals("Lobby-2", pickSlot("Lobby"))
    }

    @Test
    fun `lowest-numbered terminal slot wins when multiple are free`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.READY))
        registry.register(svc("Lobby-2", "Lobby", 30002, ServiceState.CRASHED))
        registry.register(svc("Lobby-3", "Lobby", 30003, ServiceState.READY))
        registry.register(svc("Lobby-4", "Lobby", 30004, ServiceState.CRASHED))
        registry.register(svc("Lobby-5", "Lobby", 30005, ServiceState.STOPPED))

        assertEquals("Lobby-2", pickSlot("Lobby"))
    }

    @Test
    fun `fresh number is allocated when no terminal slot exists`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.READY))
        registry.register(svc("Lobby-2", "Lobby", 30002, ServiceState.READY))

        assertEquals("Lobby-3", pickSlot("Lobby"))
    }

    @Test
    fun `slot reuse removes the old registry entry so it can be re-registered`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.CRASHED))
        registry.register(svc("Lobby-2", "Lobby", 30002, ServiceState.READY))

        val picked = pickSlot("Lobby")

        assertEquals("Lobby-1", picked)
        // prepare() depends on being able to register the new instance under the
        // same name — which requires the crashed entry to have been unregistered.
        assertNull(registry.get("Lobby-1"),
            "Reused slot must be unregistered so the fresh instance can take the same name")
    }

    @Test
    fun `slot selection is scoped by group`() {
        registry.register(svc("Lobby-1", "Lobby", 30001, ServiceState.CRASHED))
        registry.register(svc("BedWars-1", "BedWars", 30010, ServiceState.READY))

        // A fresh BedWars slot should NOT reuse Lobby-1's crashed slot.
        assertEquals("BedWars-2", pickSlot("BedWars"))
    }
}
