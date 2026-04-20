package dev.nimbuspowered.nimbus.module.docker

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DockerClientTest {

    private lateinit var daemon: FakeDockerDaemon
    private lateinit var client: DockerClient

    @BeforeEach fun setup() {
        daemon = FakeDockerDaemon()
        client = DockerClient(daemon.endpoint)
    }

    @AfterEach fun teardown() { daemon.close() }

    @Test
    fun `ping returns true on 200 and false on non-200`() {
        daemon.enqueue200("OK")
        assertTrue(client.ping())

        daemon.enqueue(500, "Internal", "")
        assertFalse(client.ping())
    }

    @Test
    fun `version parses fields`() {
        daemon.enqueue200("""{"Version":"24.0.5","ApiVersion":"1.43","Os":"linux","Arch":"amd64"}""")
        val v = client.version()
        assertNotNull(v)
        assertEquals("24.0.5", v!!.version)
        assertEquals("1.43", v.apiVersion)
        assertEquals("linux", v.os)
        assertEquals("amd64", v.arch)
    }

    @Test
    fun `version returns null on non-200`() {
        daemon.enqueue(500, "Err", "boom")
        assertNull(client.version())
    }

    @Test
    fun `listContainers builds label filter and parses array`() {
        daemon.enqueue200("""[{"Id":"a1","State":"running"},{"Id":"b2","State":"exited"}]""")
        val list = client.listContainers(labels = mapOf("nimbus.managed" to "true"))
        assertEquals(2, list.size)
        assertEquals("a1", list[0]["Id"]?.jsonPrimitive?.content)
        val req = daemon.requests.last()
        assertTrue(req.path.contains("filters="))
        assertTrue(req.path.contains("all=1"))
    }

    @Test
    fun `listContainers throws on non-200`() {
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.listContainers() }
    }

    @Test
    fun `inspect returns null on 404`() {
        daemon.enqueue404()
        assertNull(client.inspect("missing-id"))
    }

    @Test
    fun `inspect parses json on 200`() {
        daemon.enqueue200("""{"Id":"abc","State":{"Running":true}}""")
        val obj = client.inspect("abc")
        assertNotNull(obj)
        assertEquals("abc", obj!!["Id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `inspect throws on unexpected status`() {
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.inspect("x") }
    }

    @Test
    fun `createContainer returns Id on 201`() {
        daemon.enqueue201("""{"Id":"deadbeef"}""")
        val spec = client.buildContainerSpec(
            image = "eclipse-temurin:21-jre",
            cmd = listOf("java", "-jar", "server.jar"),
            workDir = "/server",
            hostWorkDir = "/tmp/srv",
            env = mapOf("FOO" to "bar"),
            portMappings = mapOf(25565 to 30000),
            memoryBytes = 1_073_741_824,
            cpuLimit = 1.5,
            network = "nimbus",
            labels = mapOf("nimbus.managed" to "true")
        )
        val id = client.createContainer("Lobby-1", spec)
        assertEquals("deadbeef", id)
        val req = daemon.requests.last()
        assertTrue(req.path.contains("name=Lobby-1"))
        assertTrue(req.body.contains("eclipse-temurin:21-jre"))
        assertTrue(req.body.contains("NanoCpus"))
        assertTrue(req.body.contains("\"Memory\":1073741824"))
    }

    @Test
    fun `createContainer throws on non-201`() {
        daemon.enqueue(409, "Conflict", "{\"message\":\"dupe\"}")
        assertThrows<DockerException> { client.createContainer("Lobby-1", client.buildContainerSpec(
            "img", listOf("true"), "/w", "/w", emptyMap(), emptyMap(), 0, 0.0, "net", emptyMap()
        )) }
    }

    @Test
    fun `createContainer throws on response missing Id`() {
        daemon.enqueue201("""{"Warnings":[]}""")
        assertThrows<DockerException> { client.createContainer("x", client.buildContainerSpec(
            "img", listOf("true"), "/w", "/w", emptyMap(), emptyMap(), 0, 0.0, "net", emptyMap()
        )) }
    }

    @Test
    fun `startContainer accepts 204 and 304`() {
        daemon.enqueue204()
        client.startContainer("abc")
        daemon.enqueue(304, "Not Modified", "")
        client.startContainer("abc")
    }

    @Test
    fun `startContainer throws on 500`() {
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.startContainer("abc") }
    }

    @Test
    fun `stopContainer tolerates 204 304 404`() {
        daemon.enqueue204()
        client.stopContainer("abc")
        daemon.enqueue(404, "Not Found", "{}")
        client.stopContainer("abc")
    }

    @Test
    fun `stopContainer throws on 500`() {
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.stopContainer("abc") }
    }

    @Test
    fun `killContainer tolerates 204 and 404`() {
        daemon.enqueue204()
        client.killContainer("abc")
        daemon.enqueue404()
        client.killContainer("abc")
    }

    @Test
    fun `killContainer throws on other error`() {
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.killContainer("abc") }
    }

    @Test
    fun `removeContainer tolerates 204 and 404`() {
        daemon.enqueue204()
        client.removeContainer("abc")
        daemon.enqueue404()
        client.removeContainer("abc")
    }

    @Test
    fun `removeContainer throws on 409`() {
        daemon.enqueue(409, "Conflict", "{}")
        assertThrows<DockerException> { client.removeContainer("abc") }
    }

    @Test
    fun `stats parses memory and cpu`() {
        daemon.enqueue200("""
            {
              "memory_stats": {"usage": 536870912, "limit": 2147483648},
              "cpu_stats": {
                "cpu_usage": {"total_usage": 2000},
                "system_cpu_usage": 4000,
                "online_cpus": 2
              },
              "precpu_stats": {
                "cpu_usage": {"total_usage": 1000},
                "system_cpu_usage": 2000
              }
            }
        """.trimIndent())
        val stats = client.stats("abc")
        assertNotNull(stats)
        assertEquals(536_870_912L, stats!!.memoryBytes)
        assertEquals(2_147_483_648L, stats.memoryLimitBytes)
        // cpuDelta=1000 sysDelta=2000 onlineCpus=2 -> (0.5)*2*100 = 100
        assertEquals(100.0, stats.cpuPercent, 0.01)
    }

    @Test
    fun `stats returns null on empty body`() {
        daemon.enqueue200("   ")
        assertNull(client.stats("abc"))
    }

    @Test
    fun `stats returns null on non-200`() {
        daemon.enqueue(500, "Err", "boom")
        assertNull(client.stats("abc"))
    }

    @Test
    fun `waitForExit returns StatusCode`() {
        daemon.enqueue200("""{"StatusCode": 137}""")
        assertEquals(137, client.waitForExit("abc"))
    }

    @Test
    fun `waitForExit returns null on non-200`() {
        daemon.enqueue(500, "Err", "boom")
        assertNull(client.waitForExit("abc"))
    }

    @Test
    fun `ensureNetwork is noop when network exists`() {
        daemon.enqueue200("""[{"Name":"nimbus"}]""")
        client.ensureNetwork("nimbus")
        // Only one request - the list call
        assertEquals(1, daemon.requests.size)
    }

    @Test
    fun `ensureNetwork creates when list returns empty array`() {
        daemon.enqueue200("""[]""")
        daemon.enqueue201("""{"Id":"net1"}""")
        client.ensureNetwork("nimbus")
        assertEquals(2, daemon.requests.size)
        assertTrue(daemon.requests.last().body.contains("\"Name\":\"nimbus\""))
    }

    @Test
    fun `ensureNetwork tolerates 409 conflict`() {
        daemon.enqueue200("""[]""")
        daemon.enqueue(409, "Conflict", "{}")
        client.ensureNetwork("nimbus")
    }

    @Test
    fun `ensureNetwork throws on unexpected create status`() {
        daemon.enqueue200("""[]""")
        daemon.enqueue(500, "Err", "boom")
        assertThrows<DockerException> { client.ensureNetwork("nimbus") }
    }

    @Test
    fun `buildContainerSpec omits Memory when zero and PortBindings for each port`() {
        val spec = client.buildContainerSpec(
            image = "img",
            cmd = listOf("java"),
            workDir = "/s",
            hostWorkDir = "/tmp/s",
            env = emptyMap(),
            portMappings = mapOf(25565 to 30000, 19132 to 19133),
            memoryBytes = 0,
            cpuLimit = 0.0,
            network = "nimbus",
            labels = emptyMap()
        )
        val s = spec.toString()
        assertFalse(s.contains("\"Memory\":"))
        assertFalse(s.contains("NanoCpus"))
        assertTrue(s.contains("25565/tcp"))
        assertTrue(s.contains("19132/tcp"))
    }
}
