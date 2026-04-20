package dev.nimbuspowered.nimbus.module.docker

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerContainerMatcherMoreTest {

    private fun container(managed: Boolean, state: String, service: String? = null) = buildJsonObject {
        put("State", state)
        putJsonObject("Labels") {
            if (managed) put("nimbus.managed", "true")
            if (service != null) put("nimbus.service", service)
        }
    }

    @Test
    fun `isManaged returns true only when label is true`() {
        assertTrue(DockerContainerMatcher.isManaged(container(true, "running")))
        assertFalse(DockerContainerMatcher.isManaged(container(false, "running")))
    }

    @Test
    fun `isManagedRunning and Exited discriminate on state`() {
        assertTrue(DockerContainerMatcher.isManagedRunning(container(true, "running")))
        assertFalse(DockerContainerMatcher.isManagedRunning(container(true, "exited")))
        assertTrue(DockerContainerMatcher.isManagedExited(container(true, "exited")))
        assertFalse(DockerContainerMatcher.isManagedExited(container(false, "exited")))
    }

    @Test
    fun `state returns raw value or empty string`() {
        assertEquals("running", DockerContainerMatcher.state(container(true, "running")))
        assertEquals("", DockerContainerMatcher.state(buildJsonObject {}))
    }

    @Test
    fun `serviceName reads label when present`() {
        assertEquals("Lobby-1", DockerContainerMatcher.serviceName(container(true, "running", "Lobby-1")))
        assertNull(DockerContainerMatcher.serviceName(container(true, "running", null)))
    }
}
