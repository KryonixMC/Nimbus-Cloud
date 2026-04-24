package dev.nimbuspowered.nimbus.protocol

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusterMessageSerializationTest {

    private inline fun <reified T : ClusterMessage> roundtrip(msg: T) {
        val encoded = clusterJson.encodeToString<ClusterMessage>(msg)
        val decoded = clusterJson.decodeFromString<ClusterMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `type discriminator is emitted on encode`() {
        val encoded = clusterJson.encodeToString<ClusterMessage>(
            ClusterMessage.AuthResponse(accepted = true, nodeId = "node-1")
        )
        assertTrue(encoded.contains("\"type\":\"AUTH_RESPONSE\""), "expected type discriminator in: $encoded")
    }

    @Test
    fun `AuthResponse roundtrip`() =
        roundtrip(ClusterMessage.AuthResponse(accepted = true, nodeId = "n1", reason = "ok"))

    @Test
    fun `StartService roundtrip with all fields`() = roundtrip(
        ClusterMessage.StartService(
            serviceName = "Lobby-1",
            groupName = "Lobby",
            port = 30000,
            templateName = "lobby",
            templateNames = listOf("base", "lobby"),
            templateHash = "abc123",
            software = "paper",
            version = "1.21.4",
            memory = "2G",
            jvmArgs = listOf("-XX:+UseG1GC"),
            jvmOptimize = true,
            jarName = "paper.jar",
            modloaderVersion = "",
            readyPattern = "Done",
            readyTimeoutSeconds = 120,
            forwardingMode = "modern",
            forwardingSecret = "secret",
            isStatic = false,
            isModded = false,
            customJarName = "",
            apiUrl = "http://127.0.0.1:8080",
            apiToken = "tok",
            nimbusProperties = mapOf("k" to "v"),
            javaVersion = 21,
            bedrockPort = 0,
            bedrockEnabled = false,
            syncEnabled = true,
            syncExcludes = listOf("*.log"),
            isDedicated = false
        )
    )

    @Test
    fun `StopService roundtrip`() = roundtrip(ClusterMessage.StopService("Lobby-1", 30))

    @Test
    fun `DiscardSyncWorkdir roundtrip`() = roundtrip(ClusterMessage.DiscardSyncWorkdir("BedWars-3"))

    @Test
    fun `SendCommand roundtrip`() = roundtrip(ClusterMessage.SendCommand("Lobby-1", "stop"))

    @Test
    fun `HeartbeatRequest roundtrip`() = roundtrip(ClusterMessage.HeartbeatRequest(timestamp = 42L))

    @Test
    fun `TemplateInfo roundtrip`() =
        roundtrip(ClusterMessage.TemplateInfo("tpl", "hash", "http://host/tpl.zip", 1024L))

    @Test
    fun `ShutdownAgent roundtrip`() =
        roundtrip(ClusterMessage.ShutdownAgent(reason = "bye", graceful = false))

    @Test
    fun `FileListRequest roundtrip`() =
        roundtrip(ClusterMessage.FileListRequest("svc", "/plugins", "req-1"))

    @Test
    fun `FileReadRequest roundtrip`() =
        roundtrip(ClusterMessage.FileReadRequest("svc", "/server.properties", "req-2"))

    @Test
    fun `FileWriteRequest roundtrip`() =
        roundtrip(ClusterMessage.FileWriteRequest("svc", "/foo.txt", "hello", "req-3"))

    @Test
    fun `FileDeleteRequest roundtrip`() =
        roundtrip(ClusterMessage.FileDeleteRequest("svc", "/foo.txt", "req-4"))

    @Test
    fun `AuthRequest roundtrip with full host details`() = roundtrip(
        ClusterMessage.AuthRequest(
            token = "tok",
            nodeName = "worker-1",
            maxMemory = "16G",
            maxServices = 10,
            currentServices = 2,
            agentVersion = "0.11.1",
            os = "Linux",
            arch = "amd64",
            hostname = "worker",
            osVersion = "6.6",
            cpuModel = "AMD EPYC",
            availableProcessors = 8,
            systemMemoryTotalMb = 16384,
            javaVersion = "21",
            javaVendor = "Temurin",
            publicHost = "10.0.0.5",
            runningServices = listOf("Lobby-1", "BedWars-2")
        )
    )

    @Test
    fun `HeartbeatResponse roundtrip with services`() = roundtrip(
        ClusterMessage.HeartbeatResponse(
            timestamp = 100L,
            cpuUsage = 0.25,
            processCpuLoad = 0.1,
            memoryUsedMb = 2048,
            memoryTotalMb = 16384,
            services = listOf(
                ServiceHeartbeat(
                    serviceName = "Lobby-1",
                    groupName = "Lobby",
                    state = "RUNNING",
                    port = 30000,
                    pid = 1234,
                    playerCount = 10,
                    customState = null,
                    memoryUsedMb = 512
                )
            )
        )
    )

    @Test
    fun `ServiceStateChanged roundtrip`() =
        roundtrip(ClusterMessage.ServiceStateChanged("Lobby-1", "Lobby", "RUNNING", 30000, 1234))

    @Test
    fun `ServiceStdout roundtrip`() = roundtrip(ClusterMessage.ServiceStdout("Lobby-1", "Done (5s)"))

    @Test
    fun `ServicePlayerCount roundtrip`() = roundtrip(ClusterMessage.ServicePlayerCount("Lobby-1", 7))

    @Test
    fun `CommandResult roundtrip`() =
        roundtrip(ClusterMessage.CommandResult("Lobby-1", success = false, error = "boom"))

    @Test
    fun `TemplateRequest roundtrip`() = roundtrip(ClusterMessage.TemplateRequest("lobby"))

    @Test
    fun `LogMessage roundtrip`() = roundtrip(ClusterMessage.LogMessage("WARN", "something"))

    @Test
    fun `FileListResponse roundtrip with entries`() = roundtrip(
        ClusterMessage.FileListResponse(
            requestId = "req-1",
            entries = listOf(
                RemoteFileEntry("a", "/a", isDirectory = false, size = 10, lastModified = "2026-01-01"),
                RemoteFileEntry("d", "/d", isDirectory = true)
            )
        )
    )

    @Test
    fun `FileReadResponse roundtrip`() =
        roundtrip(ClusterMessage.FileReadResponse("req", "contents", 8))

    @Test
    fun `FileWriteResponse roundtrip`() =
        roundtrip(ClusterMessage.FileWriteResponse("req", success = true))

    @Test
    fun `FileDeleteResponse roundtrip`() =
        roundtrip(ClusterMessage.FileDeleteResponse("req", success = true))

    @Test
    fun `StateManifest roundtrip`() {
        val m = StateManifest(
            files = mapOf(
                "world/level.dat" to StateFileEntry(sha256 = "a".repeat(64), size = 1024L),
                "server.properties" to StateFileEntry(sha256 = "b".repeat(64), size = 512L)
            )
        )
        val encoded = clusterJson.encodeToString(m)
        val decoded = clusterJson.decodeFromString<StateManifest>(encoded)
        assertEquals(m, decoded)
    }

    @Test
    fun `unknown keys are ignored on decode`() {
        val json = """{"type":"STOP_SERVICE","serviceName":"X","timeoutSeconds":5,"futureField":"ignored"}"""
        val decoded = clusterJson.decodeFromString<ClusterMessage>(json) as ClusterMessage.StopService
        assertEquals("X", decoded.serviceName)
        assertEquals(5, decoded.timeoutSeconds)
    }

    // ── Protocol version handshake (T1–T3) ──────────────────────────────

    @Test
    fun `T1 AuthRequest without protocolVersion decodes with default 1`() {
        // Simulates an older agent JAR that pre-dates the field: the wire format
        // omits protocolVersion entirely. ignoreUnknownKeys + default = 1 means
        // the controller sees protocolVersion=1 and accepts the handshake.
        val json = """{"type":"AUTH_REQUEST","token":"t","nodeName":"n","maxMemory":"1G","maxServices":1}"""
        val decoded = clusterJson.decodeFromString<ClusterMessage>(json) as ClusterMessage.AuthRequest
        assertEquals(1, decoded.protocolVersion)
        assertEquals(ClusterMessage.CURRENT_PROTOCOL_VERSION, decoded.protocolVersion)
    }

    @Test
    fun `T2 AuthRequest protocolVersion emitted and roundtrips`() {
        val msg = ClusterMessage.AuthRequest(
            token = "t",
            nodeName = "n",
            maxMemory = "1G",
            maxServices = 1,
            protocolVersion = 1
        )
        val encoded = clusterJson.encodeToString<ClusterMessage>(msg)
        assertTrue(
            encoded.contains("\"protocolVersion\":1"),
            "expected protocolVersion in wire format: $encoded"
        )
        val decoded = clusterJson.decodeFromString<ClusterMessage>(encoded) as ClusterMessage.AuthRequest
        assertEquals(msg, decoded)
    }

    @Test
    fun `T3 AuthResponse protocolVersion default and roundtrip`() {
        // Default case — older controller that never sets the field still yields 1.
        val jsonNoField = """{"type":"AUTH_RESPONSE","accepted":true,"nodeId":"n1"}"""
        val decodedDefault = clusterJson.decodeFromString<ClusterMessage>(jsonNoField) as ClusterMessage.AuthResponse
        assertEquals(1, decodedDefault.protocolVersion)

        // Round-trip with an explicit value.
        val msg = ClusterMessage.AuthResponse(
            accepted = false,
            reason = "protocol version mismatch: agent=2, controller=1",
            protocolVersion = 1
        )
        val encoded = clusterJson.encodeToString<ClusterMessage>(msg)
        assertTrue(encoded.contains("\"protocolVersion\":1"), "expected protocolVersion in: $encoded")
        val decoded = clusterJson.decodeFromString<ClusterMessage>(encoded) as ClusterMessage.AuthResponse
        assertEquals(msg, decoded)
    }
}
