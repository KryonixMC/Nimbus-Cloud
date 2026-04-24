package dev.nimbuspowered.nimbus.event

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EventActorTest {

    @Test
    fun `actorType returns default system when unset`() {
        val event = NimbusEvent.ServiceReady("Lobby-1", "Lobby")
        assertEquals("system", event.actorType())
    }

    @Test
    fun `actorType returns bare actor when no colon present`() {
        val event = NimbusEvent.ServiceReady("Lobby-1", "Lobby").also { it.actor = "console" }
        assertEquals("console", event.actorType())
    }

    @Test
    fun `actorType returns prefix before first colon`() {
        val event = NimbusEvent.ServiceReady("Lobby-1", "Lobby").also { it.actor = "api:admin" }
        assertEquals("api", event.actorType())
    }

    @Test
    fun `actorType handles player UUID form`() {
        val event = NimbusEvent.ServiceReady("Lobby-1", "Lobby").also {
            it.actor = "player:550e8400-e29b-41d4-a716-446655440000"
        }
        assertEquals("player", event.actorType())
    }

    @Test
    fun `actorType keeps only first segment with multiple colons`() {
        val event = NimbusEvent.ServiceReady("Lobby-1", "Lobby").also { it.actor = "agent:node-1:extra" }
        assertEquals("agent", event.actorType())
    }
}
