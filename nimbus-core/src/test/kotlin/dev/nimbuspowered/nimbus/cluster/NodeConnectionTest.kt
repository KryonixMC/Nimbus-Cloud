package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NodeConnectionTest {

    private fun mkConn(maxMem: String = "2G"): NodeConnection =
        NodeConnection("node-1", "localhost", maxMem, 10, mockk(relaxed = true))

    @Test
    fun `parseMemoryMb handles G suffix`() {
        assertEquals(2048, NodeConnection.parseMemoryMb("2G"))
        assertEquals(1024, NodeConnection.parseMemoryMb("1G"))
    }

    @Test
    fun `parseMemoryMb handles M suffix`() {
        assertEquals(512, NodeConnection.parseMemoryMb("512M"))
    }

    @Test
    fun `parseMemoryMb returns 0 for bad input`() {
        assertEquals(0, NodeConnection.parseMemoryMb(""))
        assertEquals(0, NodeConnection.parseMemoryMb("x"))
        assertEquals(0, NodeConnection.parseMemoryMb("abcM"))
    }

    @Test
    fun `hasMemoryFor true when within service budget and system has RAM`() {
        val c = mkConn("4G")
        c.servicesUsedMb = 1024
        c.memoryTotalMb = 8192
        c.memoryUsedMb = 2048
        // budget: 4096 - 1024 = 3072 free ; need 512
        assertTrue(c.hasMemoryFor("512M"))
    }

    @Test
    fun `hasMemoryFor false when service budget exhausted`() {
        val c = mkConn("2G")
        c.servicesUsedMb = 1800
        c.memoryTotalMb = 8192
        c.memoryUsedMb = 2048
        assertFalse(c.hasMemoryFor("512M"))
    }

    @Test
    fun `hasMemoryFor false when system free RAM below requirement`() {
        val c = mkConn("8G")
        c.servicesUsedMb = 0
        c.memoryTotalMb = 2048
        c.memoryUsedMb = 1900 // only 148 MB free
        assertFalse(c.hasMemoryFor("512M"))
    }

    @Test
    fun `markDisconnected flips connected flag`() {
        val c = mkConn()
        assertTrue(c.isConnected)
        c.markDisconnected()
        assertFalse(c.isConnected)
    }

    @Test
    fun `reconnect restores connected state and heartbeat`() {
        val c = mkConn()
        c.markDisconnected()
        c.lastHeartbeat = 0
        c.reconnect(mockk(relaxed = true))
        assertTrue(c.isConnected)
        assertTrue(c.lastHeartbeat > 0)
    }

    @Test
    fun `applyAuthInfo fills static specs and updates host when publicHost provided`() {
        val c = mkConn()
        val auth = ClusterMessage.AuthRequest(
            token = "t", nodeName = "n", maxMemory = "4G", maxServices = 4,
            agentVersion = "1.2.3", os = "Linux", arch = "amd64",
            hostname = "host", osVersion = "6", cpuModel = "Ryzen",
            availableProcessors = 8, systemMemoryTotalMb = 16384,
            javaVersion = "21", javaVendor = "Temurin",
            publicHost = "agent.example.com"
        )
        c.applyAuthInfo(auth)
        assertEquals("1.2.3", c.agentVersion)
        assertEquals("Linux", c.os)
        assertEquals(8, c.availableProcessors)
        assertEquals("agent.example.com", c.host)
    }

    @Test
    fun `updateHeartbeat aggregates service RSS`() {
        val c = mkConn()
        val hb = ClusterMessage.HeartbeatResponse(
            timestamp = 12345L,
            cpuUsage = 0.5,
            processCpuLoad = 0.25,
            memoryUsedMb = 4000,
            memoryTotalMb = 8000,
            services = listOf(
                dev.nimbuspowered.nimbus.protocol.ServiceHeartbeat("s1", "g", "READY", 25565, 0, 0, null, 300L),
                dev.nimbuspowered.nimbus.protocol.ServiceHeartbeat("s2", "g", "READY", 25566, 0, 0, null, 600L)
            )
        )
        c.updateHeartbeat(hb)
        assertEquals(2, c.currentServices)
        assertEquals(900L, c.servicesUsedMb)
        assertEquals(0.5, c.cpuUsage, 0.001)
    }
}
