package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.service.Service
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DockerMemorySourceTest {

    private fun svc(name: String) = Service(
        name = name,
        groupName = "Lobby",
        port = 30000,
        workingDirectory = Paths.get(".")
    )

    @Test
    fun `returns null when no handle is registered`() {
        val src = DockerMemorySource(handleLookup = { null })
        assertNull(src.readRssMb(svc("Lobby-1")))
    }

    @Test
    fun `returns null when liveStats reports zero or null`() {
        val handle = mockk<DockerServiceHandle>()
        every { handle.liveStats() } returns null
        val src = DockerMemorySource { handle }
        assertNull(src.readRssMb(svc("Lobby-1")))

        every { handle.liveStats() } returns DockerStats(memoryBytes = 0, memoryLimitBytes = 0, cpuPercent = 0.0)
        assertNull(src.readRssMb(svc("Lobby-1")))
    }

    @Test
    fun `converts bytes to megabytes`() {
        val handle = mockk<DockerServiceHandle>()
        every { handle.liveStats() } returns DockerStats(
            memoryBytes = 512L * 1024 * 1024,
            memoryLimitBytes = 2L * 1024 * 1024 * 1024,
            cpuPercent = 50.0
        )
        val src = DockerMemorySource { handle }
        assertEquals(512L, src.readRssMb(svc("Lobby-1")))
    }
}
