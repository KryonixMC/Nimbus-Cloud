package dev.nimbuspowered.nimbus.module.docker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [DockerContainerMatcher] directly — the same filter that
 * [DockerServiceHandleFactory.recover] uses to decide which containers to
 * reattach to on controller startup.
 */
class DockerLabelMatchTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val listContainersResponse = """
        [
          {
            "Id": "abc123",
            "Names": ["/nimbus-lobby-1"],
            "Image": "eclipse-temurin:21-jre",
            "State": "running",
            "Labels": {
              "nimbus.managed": "true",
              "nimbus.service": "Lobby-1",
              "nimbus.group": "Lobby",
              "nimbus.port": "30001"
            }
          },
          {
            "Id": "def456",
            "Names": ["/nimbus-lobby-2"],
            "Image": "eclipse-temurin:21-jre",
            "State": "exited",
            "Labels": {
              "nimbus.managed": "true",
              "nimbus.service": "Lobby-2",
              "nimbus.group": "Lobby",
              "nimbus.port": "30002"
            }
          },
          {
            "Id": "ghi789",
            "Names": ["/some-other-container"],
            "Image": "redis",
            "State": "running",
            "Labels": {
              "app": "redis"
            }
          }
        ]
    """.trimIndent()

    private fun containers() =
        (json.parseToJsonElement(listContainersResponse) as JsonArray).map { it.jsonObject }

    @Test
    fun `isManagedRunning picks only running nimbus containers`() {
        val matches = containers().filter { DockerContainerMatcher.isManagedRunning(it) }
        assertEquals(1, matches.size)
        assertEquals("Lobby-1", DockerContainerMatcher.serviceName(matches.single()))
    }

    @Test
    fun `isManagedExited detects crashed nimbus container`() {
        val exited = containers().filter { DockerContainerMatcher.isManagedExited(it) }
        assertEquals(1, exited.size)
        assertEquals("Lobby-2", DockerContainerMatcher.serviceName(exited.single()))
    }

    @Test
    fun `isManaged ignores containers without the nimbus-managed label`() {
        val managed = containers().filter { DockerContainerMatcher.isManaged(it) }
        assertEquals(2, managed.size)
        assertTrue(managed.all { DockerContainerMatcher.serviceName(it) != null })
    }

    @Test
    fun `serviceName is null when nimbus-service label absent`() {
        val redis = containers().first {
            DockerContainerMatcher.state(it) == "running" && !DockerContainerMatcher.isManaged(it)
        }
        assertNull(DockerContainerMatcher.serviceName(redis))
    }

    @Test
    fun `state returns empty string when State field missing`() {
        val empty = json.parseToJsonElement("""{"Id":"x"}""").jsonObject
        assertEquals("", DockerContainerMatcher.state(empty))
        assertFalse(DockerContainerMatcher.isManagedRunning(empty))
    }
}
