package dev.nimbuspowered.nimbus.service.spawn

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemdRunSandboxTest {

    @Test
    fun `empty limits returns command unchanged`() {
        val cmd = listOf("java", "-jar", "paper.jar")
        val out = SystemdRunSandbox.wrapCommand("Lobby-1", cmd, SandboxLimits())
        assertEquals(cmd, out)
    }

    @Test
    fun `memory-only limits produce MemoryMax and MemoryHigh`() {
        val cmd = listOf("java", "-Xmx2G", "-jar", "paper.jar")
        val out = SystemdRunSandbox.wrapCommand("Lobby-1", cmd, SandboxLimits(memoryMb = 2048))
        assertTrue(out.first() == "systemd-run")
        assertTrue(out.contains("MemoryMax=2048M"))
        assertTrue(out.any { it.startsWith("MemoryHigh=") })
        // Original command must appear after the -- separator
        val sepIdx = out.indexOf("--")
        assertTrue(sepIdx > 0)
        assertEquals(cmd, out.drop(sepIdx + 1))
    }

    @Test
    fun `cpu quota becomes percent`() {
        val cmd = listOf("java")
        val out = SystemdRunSandbox.wrapCommand("svc", cmd, SandboxLimits(cpuQuota = 1.5))
        assertTrue(out.contains("CPUQuota=150%"))
    }

    @Test
    fun `tasks max propagates`() {
        val cmd = listOf("java")
        val out = SystemdRunSandbox.wrapCommand("svc", cmd, SandboxLimits(tasksMax = 256))
        assertTrue(out.contains("TasksMax=256"))
    }

    @Test
    fun `unit name is derived from service name`() {
        val cmd = listOf("java")
        val out = SystemdRunSandbox.wrapCommand("Lobby-1", cmd, SandboxLimits(memoryMb = 512))
        assertTrue(out.any { it == "--unit=nimbus-Lobby-1" })
    }

    @Test
    fun `service names with unusual characters are sanitized`() {
        // Not strictly reachable through the validated naming regex, but cheap defense.
        val sanitized = SystemdRunSandbox.sanitizeUnitName("weird name!")
        assertEquals("nimbus-weird_name_", sanitized)
    }

    @Test
    fun `all three limits combine in one wrap`() {
        val cmd = listOf("java", "-jar", "x.jar")
        val out = SystemdRunSandbox.wrapCommand(
            "BedWars-3", cmd,
            SandboxLimits(memoryMb = 4096, cpuQuota = 2.0, tasksMax = 512)
        )
        assertTrue(out.contains("MemoryMax=4096M"))
        assertTrue(out.contains("CPUQuota=200%"))
        assertTrue(out.contains("TasksMax=512"))
    }
}
