package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.config.DockerServiceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DockerConfigManagerTest {

    @Test
    fun `creates default config on first load when file missing`(@TempDir dir: Path) {
        val mgr = DockerConfigManager(dir)
        val cfg = mgr.load()
        assertTrue(dir.resolve("docker.toml").exists())
        assertTrue(cfg.docker.enabled)
        assertEquals("/var/run/docker.sock", cfg.docker.socket)
        assertEquals("2G", cfg.docker.defaults.memoryLimit)
        assertEquals(2.0, cfg.docker.defaults.cpuLimit)
        assertEquals("nimbus", cfg.docker.defaults.network)
    }

    @Test
    fun `parses user-supplied toml`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        dir.resolve("docker.toml").writeText(
            """
            [docker]
            enabled = false
            socket = "tcp://localhost:2375"

            [docker.defaults]
            memory_limit = "4G"
            cpu_limit = 3.5
            network = "custom-net"
            java_image = "adoptopenjdk:21"
            java_17_image = "adoptopenjdk:17"
            java_21_image = "adoptopenjdk:21"
            """.trimIndent()
        )
        val mgr = DockerConfigManager(dir)
        val cfg = mgr.load()
        assertEquals(false, cfg.docker.enabled)
        assertEquals("tcp://localhost:2375", cfg.docker.socket)
        assertEquals("4G", cfg.docker.defaults.memoryLimit)
        assertEquals(3.5, cfg.docker.defaults.cpuLimit)
        assertEquals("custom-net", cfg.docker.defaults.network)
    }

    @Test
    fun `malformed toml falls back to defaults`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        dir.resolve("docker.toml").writeText("this is not [ valid toml =!=")
        val mgr = DockerConfigManager(dir)
        val cfg = mgr.load()
        // Returned defaults
        assertTrue(cfg.docker.enabled)
        assertEquals("/var/run/docker.sock", cfg.docker.socket)
    }

    @Test
    fun `effectiveFor uses defaults when service overrides empty`(@TempDir dir: Path) {
        val mgr = DockerConfigManager(dir).apply { load() }
        val eff = mgr.effectiveFor(DockerServiceConfig())
        assertEquals(2L * 1024 * 1024 * 1024, eff.memoryBytes)
        assertEquals(2.0, eff.cpuLimit)
        assertEquals("eclipse-temurin:21-jre", eff.javaImage)
        assertEquals("nimbus", eff.network)
    }

    @Test
    fun `effectiveFor honors per-service memory cpu image network overrides`(@TempDir dir: Path) {
        val mgr = DockerConfigManager(dir).apply { load() }
        val eff = mgr.effectiveFor(
            DockerServiceConfig(
                enabled = true,
                memoryLimit = "512M",
                cpuLimit = 1.5,
                javaImage = "myorg/java:21",
                network = "other"
            )
        )
        assertEquals(512L * 1024 * 1024, eff.memoryBytes)
        assertEquals(1.5, eff.cpuLimit)
        assertEquals("myorg/java:21", eff.javaImage)
        assertEquals("other", eff.network)
    }

    @Test
    fun `effectiveFor picks java17 and java21 defaults by version`(@TempDir dir: Path) {
        val mgr = DockerConfigManager(dir).apply { load() }
        val e17 = mgr.effectiveFor(DockerServiceConfig(), javaVersion = 17)
        assertEquals("eclipse-temurin:17-jre", e17.javaImage)
        val e21 = mgr.effectiveFor(DockerServiceConfig(), javaVersion = 21)
        assertEquals("eclipse-temurin:21-jre", e21.javaImage)
        val eOther = mgr.effectiveFor(DockerServiceConfig(), javaVersion = 8)
        assertEquals("eclipse-temurin:21-jre", eOther.javaImage)
    }

    @Test
    fun `effectiveFor parses all memory suffixes`(@TempDir dir: Path) {
        val mgr = DockerConfigManager(dir).apply { load() }
        assertEquals(100L, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "100")).memoryBytes)
        assertEquals(100L * 1024, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "100K")).memoryBytes)
        assertEquals(100L * 1024 * 1024, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "100M")).memoryBytes)
        assertEquals(1L * 1024 * 1024 * 1024, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "1G")).memoryBytes)
        assertEquals(1L * 1024 * 1024 * 1024 * 1024, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "1T")).memoryBytes)
        // Invalid -> 0
        assertEquals(0L, mgr.effectiveFor(DockerServiceConfig(memoryLimit = "garbage")).memoryBytes)
    }

    @Test
    fun `default config file contents are valid toml sections`(@TempDir dir: Path) {
        DockerConfigManager(dir).load()
        val content = dir.resolve("docker.toml").readText()
        assertTrue(content.contains("[docker]"))
        assertTrue(content.contains("[docker.defaults]"))
        assertTrue(content.contains("memory_limit = \"2G\""))
    }
}
