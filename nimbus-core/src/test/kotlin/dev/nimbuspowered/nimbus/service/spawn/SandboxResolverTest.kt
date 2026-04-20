package dev.nimbuspowered.nimbus.service.spawn

import dev.nimbuspowered.nimbus.config.GlobalSandboxConfig
import dev.nimbuspowered.nimbus.config.ResourcesConfig
import dev.nimbuspowered.nimbus.config.SandboxConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxResolverTest {

    private fun resolver(systemdAvailable: Boolean, global: GlobalSandboxConfig = GlobalSandboxConfig()) =
        SandboxResolver(global) { systemdAvailable }

    @Test
    fun `empty group mode inherits global default`() {
        val r = resolver(systemdAvailable = true, GlobalSandboxConfig(defaultMode = "bare"))
        val out = r.resolve("svc", SandboxConfig(mode = ""), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.BARE, out.mode)
    }

    @Test
    fun `auto with systemd-run yields managed`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.MANAGED, out.mode)
        assertTrue(out.limits.memoryMb > 0)
    }

    @Test
    fun `auto without systemd-run falls back to bare`() {
        val r = resolver(systemdAvailable = false)
        val out = r.resolve("svc", SandboxConfig(), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.BARE, out.mode)
    }

    @Test
    fun `group-level managed without systemd falls back to bare`() {
        val r = resolver(systemdAvailable = false)
        val out = r.resolve("svc", SandboxConfig(mode = "managed"), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.BARE, out.mode)
    }

    @Test
    fun `explicit docker mode is respected`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(mode = "docker"), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.DOCKER, out.mode)
    }

    @Test
    fun `unknown mode logs and falls back to bare`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(mode = "nonsense"), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.BARE, out.mode)
    }

    @Test
    fun `memory cap uses heap plus overhead floor`() {
        val r = resolver(systemdAvailable = true, GlobalSandboxConfig(
            memoryOverheadPercent = 30, memoryOverheadMinMb = 256
        ))
        val out = r.resolve("svc", SandboxConfig(), ResourcesConfig(memory = "1G"))
        // 1024 MB heap + max(30% of 1024, 256) = 1024 + 307 = 1331 (floor kicks in)
        // 30% of 1024 = 307, so overhead = max(307, 256) = 307 → 1024+307 = 1331
        assertEquals(1331L, out.limits.memoryMb)
    }

    @Test
    fun `small heap triggers absolute overhead floor`() {
        val r = resolver(systemdAvailable = true, GlobalSandboxConfig(
            memoryOverheadPercent = 30, memoryOverheadMinMb = 256
        ))
        val out = r.resolve("svc", SandboxConfig(), ResourcesConfig(memory = "512M"))
        // 512 MB heap + max(30% of 512=153, 256) = 512 + 256 = 768
        assertEquals(768L, out.limits.memoryMb)
    }

    @Test
    fun `per-group memory override beats derived value`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(memoryLimitMb = 4096), ResourcesConfig(memory = "1G"))
        assertEquals(4096L, out.limits.memoryMb)
    }

    @Test
    fun `cpu quota and tasks max flow through when managed`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(cpuQuota = 2.5, tasksMax = 400), ResourcesConfig(memory = "2G"))
        assertEquals(2.5, out.limits.cpuQuota)
        assertEquals(400, out.limits.tasksMax)
    }

    @Test
    fun `bare mode returns empty limits even if configured`() {
        val r = resolver(systemdAvailable = true)
        val out = r.resolve("svc", SandboxConfig(mode = "bare", memoryLimitMb = 9999), ResourcesConfig(memory = "2G"))
        assertEquals(SandboxMode.BARE, out.mode)
        assertTrue(out.limits.isEmpty())
    }
}
