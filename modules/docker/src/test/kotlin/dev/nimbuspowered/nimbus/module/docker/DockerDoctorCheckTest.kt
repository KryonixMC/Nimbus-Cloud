package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.module.DoctorLevel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DockerDoctorCheckTest {

    private fun running(): JsonObject = buildJsonObject { put("State", "running") }
    private fun exited(): JsonObject = buildJsonObject { put("State", "exited") }

    private fun mgr(dir: Path, enabled: Boolean = true): DockerConfigManager {
        val m = DockerConfigManager(dir).apply { load() }
        if (!enabled) {
            // Write a disabled config and reload
            dir.resolve("docker.toml").toFile().writeText("""
                [docker]
                enabled = false
                socket = "/var/run/docker.sock"
                [docker.defaults]
                memory_limit = "2G"
                cpu_limit = 2.0
                network = "nimbus"
                java_image = "eclipse-temurin:21-jre"
                java_17_image = "eclipse-temurin:17-jre"
                java_21_image = "eclipse-temurin:21-jre"
            """.trimIndent())
            m.load()
        }
        return m
    }

    @Test
    fun `disabled module returns single OK finding`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        val check = DockerDoctorCheck(client, mgr(dir, enabled = false))
        val findings = check.run()
        assertEquals(1, findings.size)
        assertEquals(DoctorLevel.OK, findings[0].level)
        assertTrue(findings[0].message.contains("disabled"))
    }

    @Test
    fun `ping failure returns single FAIL with hint`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        every { client.ping() } returns false
        val check = DockerDoctorCheck(client, mgr(dir))
        val f = check.run()
        assertEquals(1, f.size)
        assertEquals(DoctorLevel.FAIL, f[0].level)
        assertTrue(f[0].hint!!.contains("Start Docker"))
    }

    @Test
    fun `healthy daemon returns OK and counts managed containers`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        every { client.ping() } returns true
        every { client.version() } returns DockerVersionInfo("24.0", "1.43", "linux", "amd64")
        every { client.listContainers(any(), any()) } returns listOf(running(), running(), exited())
        val f = DockerDoctorCheck(client, mgr(dir)).run()
        // Daemon OK + container count OK = 2 findings
        assertEquals(2, f.size)
        assertEquals(DoctorLevel.OK, f[0].level)
        assertTrue(f[0].message.contains("24.0"))
        assertTrue(f[1].message.contains("2 running, 1 stopped"))
    }

    @Test
    fun `old API version produces WARN`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        every { client.ping() } returns true
        every { client.version() } returns DockerVersionInfo("19.0", "1.40", "linux", "amd64")
        every { client.listContainers(any(), any()) } returns emptyList()
        val f = DockerDoctorCheck(client, mgr(dir)).run()
        assertTrue(f.any { it.level == DoctorLevel.WARN && it.message.contains("older") })
    }

    @Test
    fun `stale containers above threshold produce WARN`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        every { client.ping() } returns true
        every { client.version() } returns DockerVersionInfo("24.0", "1.43", "linux", "amd64")
        every { client.listContainers(any(), any()) } returns List(8) { exited() }
        val f = DockerDoctorCheck(client, mgr(dir)).run()
        assertTrue(f.any { it.level == DoctorLevel.WARN && it.message.contains("accumulating") })
    }

    @Test
    fun `listContainers throwing is swallowed`(@TempDir dir: Path) = runBlocking {
        val client = mockk<DockerClient>()
        every { client.ping() } returns true
        every { client.version() } returns DockerVersionInfo("24.0", "1.43", "linux", "amd64")
        every { client.listContainers(any(), any()) } throws RuntimeException("boom")
        val f = DockerDoctorCheck(client, mgr(dir)).run()
        // Still returns daemon-OK + "0 running, 0 stopped"
        assertTrue(f.any { it.message.contains("0 running, 0 stopped") })
    }
}
