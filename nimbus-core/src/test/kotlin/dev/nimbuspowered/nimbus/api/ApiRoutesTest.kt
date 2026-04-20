package dev.nimbuspowered.nimbus.api

import dev.nimbuspowered.nimbus.api.routes.groupRoutes
import dev.nimbuspowered.nimbus.api.routes.networkRoutes
import dev.nimbuspowered.nimbus.api.routes.serviceRoutes
import dev.nimbuspowered.nimbus.api.routes.systemRoutes
import dev.nimbuspowered.nimbus.config.*
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.group.ServerGroup
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServerListPing
import dev.nimbuspowered.nimbus.service.ServiceState
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

class ApiRoutesTest {

    private val testToken = "test-token-12345"
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private lateinit var registry: ServiceRegistry
    private lateinit var serviceManager: ServiceManager
    private lateinit var groupManager: GroupManager
    private lateinit var eventBus: EventBus
    private lateinit var testScope: TestScope

    private val testConfig = NimbusConfig(
        network = NetworkConfig(name = "TestNetwork"),
        api = ApiConfig(token = testToken)
    )

    private val startedAt = Instant.now()

    @BeforeEach
    fun setUp() {
        registry = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)
        groupManager = mockk(relaxed = true)
        testScope = TestScope()
        eventBus = EventBus(testScope)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun setupTestApplication(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) {
                json(json)
            }
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, ApiMessage(false, cause.message ?: "Bad request"))
                }
                exception<Throwable> { call, cause ->
                    call.respond(HttpStatusCode.InternalServerError, ApiMessage(false, "Internal server error"))
                }
            }
            install(Authentication) {
                bearer("api-token") {
                    authenticate { credential ->
                        if (credential.token == testToken) UserIdPrincipal("nimbus-api") else null
                    }
                }
            }
            routing {
                // Health is public
                get("/api/health") {
                    val uptime = java.time.Duration.between(startedAt, Instant.now()).seconds
                    call.respond(HealthResponse(
                        status = "ok",
                        version = "0.2.0",
                        uptimeSeconds = uptime,
                        services = registry.getAll().size,
                        apiEnabled = true
                    ))
                }

                authenticate("api-token") {
                    serviceRoutes(registry, serviceManager, groupManager, eventBus)
                    groupRoutes(registry, groupManager, Path.of("/tmp/nimbus/groups"), eventBus)
                    networkRoutes(testConfig, registry, groupManager, serviceManager, startedAt)
                    systemRoutes(testConfig, groupManager, Path.of("/tmp/nimbus/groups"), serviceManager, eventBus, testScope, startedAt)
                }
            }
        }

        block()
    }

    private fun HttpRequestBuilder.withAuth() {
        header(HttpHeaders.Authorization, "Bearer $testToken")
    }

    private fun createTestService(
        name: String,
        group: String = "Lobby",
        port: Int = 30001,
        state: ServiceState = ServiceState.READY
    ): Service {
        val svc = Service(
            name = name,
            groupName = group,
            port = port,
            initialState = ServiceState.PREPARING,
            workingDirectory = Path.of("/tmp/nimbus/$name"),
            startedAt = Instant.now()
        )
        // Transition to desired state
        when (state) {
            ServiceState.STARTING -> svc.transitionTo(ServiceState.STARTING)
            ServiceState.READY -> {
                svc.transitionTo(ServiceState.STARTING)
                svc.transitionTo(ServiceState.READY)
            }
            ServiceState.STOPPING -> {
                svc.transitionTo(ServiceState.STARTING)
                svc.transitionTo(ServiceState.READY)
                svc.transitionTo(ServiceState.STOPPING)
            }
            ServiceState.STOPPED -> {
                svc.transitionTo(ServiceState.STARTING)
                svc.transitionTo(ServiceState.READY)
                svc.transitionTo(ServiceState.STOPPING)
                svc.transitionTo(ServiceState.STOPPED)
            }
            else -> {}
        }
        return svc
    }

    private fun createTestGroup(name: String = "Lobby"): ServerGroup {
        return ServerGroup(
            GroupConfig(
                group = GroupDefinition(
                    name = name,
                    template = "lobby",
                    software = ServerSoftware.PAPER,
                    version = "1.21.4",
                    resources = ResourcesConfig("1G", 50),
                    scaling = ScalingConfig(1, 4, 40, 0.8, 0),
                    lifecycle = LifecycleConfig(false, true, 5),
                    jvm = JvmConfig()
                )
            )
        )
    }

    // ── Health Endpoint ────────────────────────────────────────────

    @Nested
    inner class HealthEndpoint {

        @Test
        fun `GET health returns 200 with HealthResponse`() = setupTestApplication {
            every { registry.getAll() } returns listOf(
                createTestService("Lobby-1"),
                createTestService("Lobby-2")
            )

            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
            assertEquals("ok", body.status)
            assertEquals("0.2.0", body.version)
            assertEquals(2, body.services)
            assertTrue(body.apiEnabled)
            assertTrue(body.uptimeSeconds >= 0)
        }

        @Test
        fun `GET health does not require auth`() = setupTestApplication {
            every { registry.getAll() } returns emptyList()

            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ── Service Endpoints ──────────────────────────────────────────

    @Nested
    inner class ServiceEndpoints {

        @Test
        fun `GET services returns 200 with service list`() = setupTestApplication {
            val services = listOf(
                createTestService("Lobby-1", "Lobby", 30001),
                createTestService("BedWars-1", "BedWars", 30002)
            )
            every { registry.getAll() } returns services

            val response = client.get("/api/services") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ServiceListResponse>(response.bodyAsText())
            assertEquals(2, body.total)
            assertEquals(2, body.services.size)
            assertEquals("Lobby-1", body.services[0].name)
            assertEquals("BedWars-1", body.services[1].name)
        }

        @Test
        fun `GET services without auth returns 401`() = setupTestApplication {
            val response = client.get("/api/services")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services with wrong token returns 401`() = setupTestApplication {
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer wrong-token")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services filtered by group`() = setupTestApplication {
            val lobbies = listOf(createTestService("Lobby-1", "Lobby"))
            every { registry.getByGroup("Lobby") } returns lobbies

            val response = client.get("/api/services?group=Lobby") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ServiceListResponse>(response.bodyAsText())
            assertEquals(1, body.total)
            assertEquals("Lobby-1", body.services[0].name)
        }

        @Test
        fun `GET service by name returns 200`() = setupTestApplication {
            val svc = createTestService("Lobby-1")
            every { registry.get("Lobby-1") } returns svc

            val response = client.get("/api/services/Lobby-1") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ServiceResponse>(response.bodyAsText())
            assertEquals("Lobby-1", body.name)
            assertEquals("Lobby", body.groupName)
            assertEquals(30001, body.port)
            assertEquals("READY", body.state)
        }

        @Test
        fun `GET service not found returns 404`() = setupTestApplication {
            every { registry.get("NoSuch") } returns null

            val response = client.get("/api/services/NoSuch") { withAuth() }
            assertEquals(HttpStatusCode.NotFound, response.status)

            val body = json.decodeFromString<ApiMessage>(response.bodyAsText())
            assertFalse(body.success)
            assertTrue(body.message.contains("not found"))
        }

        @Test
        fun `POST service start returns 201 on success`() = setupTestApplication {
            val group = createTestGroup("Lobby")
            every { groupManager.getGroup("Lobby") } returns group

            val svc = createTestService("Lobby-1")
            coEvery { serviceManager.startService("Lobby") } returns svc

            val response = client.post("/api/services/Lobby/start") { withAuth() }
            assertEquals(HttpStatusCode.Created, response.status)

            val body = json.decodeFromString<ApiMessage>(response.bodyAsText())
            assertTrue(body.success)
        }

        @Test
        fun `POST service start returns 404 for unknown group`() = setupTestApplication {
            every { groupManager.getGroup("NoGroup") } returns null

            val response = client.post("/api/services/NoGroup/start") { withAuth() }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `POST service start returns 409 when at max instances`() = setupTestApplication {
            val group = createTestGroup("Lobby")
            every { groupManager.getGroup("Lobby") } returns group
            coEvery { serviceManager.startService("Lobby") } returns null

            val response = client.post("/api/services/Lobby/start") { withAuth() }
            assertEquals(HttpStatusCode.Conflict, response.status)
        }

        @Test
        fun `POST service stop returns 200 on success`() = setupTestApplication {
            val svc = createTestService("Lobby-1")
            every { registry.get("Lobby-1") } returns svc
            coEvery { serviceManager.stopService("Lobby-1") } returns true

            val response = client.post("/api/services/Lobby-1/stop") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ApiMessage>(response.bodyAsText())
            assertTrue(body.success)
        }

        @Test
        fun `POST service stop returns 404 for unknown service`() = setupTestApplication {
            every { registry.get("NoSuch") } returns null

            val response = client.post("/api/services/NoSuch/stop") { withAuth() }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        fun `POST service stop returns 500 when stop fails`() = setupTestApplication {
            val svc = createTestService("Lobby-1")
            every { registry.get("Lobby-1") } returns svc
            coEvery { serviceManager.stopService("Lobby-1") } returns false

            val response = client.post("/api/services/Lobby-1/stop") { withAuth() }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        @Test
        fun `POST service restart returns 200 on success`() = setupTestApplication {
            val svc = createTestService("Lobby-1")
            every { registry.get("Lobby-1") } returns svc
            val newSvc = createTestService("Lobby-1", port = 30002)
            coEvery { serviceManager.restartService("Lobby-1") } returns newSvc

            val response = client.post("/api/services/Lobby-1/restart") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ApiMessage>(response.bodyAsText())
            assertTrue(body.success)
        }

        @Test
        fun `POST service restart returns 404 for unknown service`() = setupTestApplication {
            every { registry.get("NoSuch") } returns null

            val response = client.post("/api/services/NoSuch/restart") { withAuth() }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    // ── Group Endpoints ────────────────────────────────────────────

    @Nested
    inner class GroupEndpoints {

        @Test
        fun `GET groups returns 200 with group list`() = setupTestApplication {
            val groups = listOf(createTestGroup("Lobby"), createTestGroup("BedWars"))
            every { groupManager.getAllGroups() } returns groups
            every { registry.countByGroup(any()) } returns 1

            val response = client.get("/api/groups") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<GroupListResponse>(response.bodyAsText())
            assertEquals(2, body.total)
            assertEquals(2, body.groups.size)
        }

        @Test
        fun `GET groups returns empty list when no groups`() = setupTestApplication {
            every { groupManager.getAllGroups() } returns emptyList()

            val response = client.get("/api/groups") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<GroupListResponse>(response.bodyAsText())
            assertEquals(0, body.total)
            assertTrue(body.groups.isEmpty())
        }

        @Test
        fun `GET group by name returns 200`() = setupTestApplication {
            val group = createTestGroup("Lobby")
            every { groupManager.getGroup("Lobby") } returns group
            every { registry.countByGroup("Lobby") } returns 2

            val response = client.get("/api/groups/Lobby") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<GroupResponse>(response.bodyAsText())
            assertEquals("Lobby", body.name)
            assertEquals("PAPER", body.software)
            assertEquals("1.21.4", body.version)
            assertEquals(2, body.activeInstances)
        }

        @Test
        fun `GET group not found returns 404`() = setupTestApplication {
            every { groupManager.getGroup("NoGroup") } returns null

            val response = client.get("/api/groups/NoGroup") { withAuth() }
            assertEquals(HttpStatusCode.NotFound, response.status)

            val body = json.decodeFromString<ApiMessage>(response.bodyAsText())
            assertFalse(body.success)
        }

        @Test
        fun `GET groups without auth returns 401`() = setupTestApplication {
            val response = client.get("/api/groups")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ── Status Endpoint ────────────────────────────────────────────

    @Nested
    inner class StatusEndpoint {

        @Test
        fun `GET status returns 200 with StatusResponse`() = setupTestApplication {
            val services = listOf(
                createTestService("Lobby-1", "Lobby", 30001),
                createTestService("Lobby-2", "Lobby", 30002)
            )
            every { registry.getAll() } returns services

            val group = createTestGroup("Lobby")
            every { groupManager.getAllGroups() } returns listOf(group)
            every { registry.getByGroup("Lobby") } returns services

            val response = client.get("/api/status") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<StatusResponse>(response.bodyAsText())
            assertEquals("TestNetwork", body.networkName)
            assertTrue(body.online)
            assertTrue(body.uptimeSeconds >= 0)
            assertEquals(2, body.totalServices)
            assertEquals(1, body.groups.size)
            assertEquals("Lobby", body.groups[0].name)
            assertEquals(2, body.groups[0].instances)
        }

        @Test
        fun `GET status with no services shows offline`() = setupTestApplication {
            every { registry.getAll() } returns emptyList()
            every { groupManager.getAllGroups() } returns emptyList()

            val response = client.get("/api/status") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<StatusResponse>(response.bodyAsText())
            assertFalse(body.online)
            assertEquals(0, body.totalServices)
            assertEquals(0, body.totalPlayers)
        }

        @Test
        fun `GET status without auth returns 401`() = setupTestApplication {
            val response = client.get("/api/status")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ── Reload Endpoint ────────────────────────────────────────────

    @Nested
    inner class ReloadEndpoint {

        @Test
        fun `POST reload returns 200 with ReloadResponse`() = setupTestApplication {
            // ConfigLoader.reloadGroupConfigs is a static method — since systemRoutes
            // calls it directly, we use mockkObject to mock it
            mockkObject(ConfigLoader)
            val configs = listOf(
                GroupConfig(group = GroupDefinition(name = "Lobby", template = "lobby")),
                GroupConfig(group = GroupDefinition(name = "BedWars", template = "bedwars"))
            )
            every { ConfigLoader.reloadGroupConfigs(any()) } returns configs
            every { groupManager.reloadGroups(configs) } just Runs

            val response = client.post("/api/reload") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ReloadResponse>(response.bodyAsText())
            assertTrue(body.success)
            assertEquals(2, body.groupsLoaded)

            unmockkObject(ConfigLoader)
        }

        @Test
        fun `POST reload without auth returns 401`() = setupTestApplication {
            val response = client.post("/api/reload")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `POST reload returns failure when exception occurs`() = setupTestApplication {
            mockkObject(ConfigLoader)
            every { ConfigLoader.reloadGroupConfigs(any()) } throws RuntimeException("Config parse error")

            val response = client.post("/api/reload") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ReloadResponse>(response.bodyAsText())
            assertFalse(body.success)
            assertEquals(0, body.groupsLoaded)
            assertTrue(body.message.contains("Reload failed"))

            unmockkObject(ConfigLoader)
        }
    }

    // ── Players Endpoint ───────────────────────────────────────────

    @Nested
    inner class PlayersEndpoint {

        @Test
        fun `GET players returns 200 with empty list when no ready services`() = setupTestApplication {
            every { registry.getAll() } returns emptyList()

            val response = client.get("/api/players") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<PlayersResponse>(response.bodyAsText())
            assertEquals(0, body.total)
            assertTrue(body.players.isEmpty())
        }

        @Test
        fun `GET players returns 200 with player list from ready services`() = setupTestApplication {
            val svc = createTestService("Lobby-1", "Lobby", 30001, ServiceState.READY)
            every { registry.getAll() } returns listOf(svc)

            mockkObject(ServerListPing)
            every { ServerListPing.ping(any(), eq(30001), any()) } returns ServerListPing.PingResult(
                onlinePlayers = 2,
                maxPlayers = 50,
                playerNames = listOf("Steve", "Alex"),
                motd = "A Minecraft Server",
                version = "1.21.4"
            )

            val response = client.get("/api/players") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<PlayersResponse>(response.bodyAsText())
            assertEquals(2, body.total)
            assertEquals("Steve", body.players[0].name)
            assertEquals("Lobby-1", body.players[0].service)
            assertEquals("Alex", body.players[1].name)

            unmockkObject(ServerListPing)
        }

        @Test
        fun `GET players handles ping failure gracefully`() = setupTestApplication {
            val svc = createTestService("Lobby-1", "Lobby", 30001, ServiceState.READY)
            every { registry.getAll() } returns listOf(svc)

            mockkObject(ServerListPing)
            every { ServerListPing.ping(any(), eq(30001), any()) } returns null

            val response = client.get("/api/players") { withAuth() }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<PlayersResponse>(response.bodyAsText())
            assertEquals(0, body.total)

            unmockkObject(ServerListPing)
        }

        @Test
        fun `GET players without auth returns 401`() = setupTestApplication {
            val response = client.get("/api/players")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    // ── Shutdown Endpoint ──────────────────────────────────────────
    // Intentionally not tested: POST /api/shutdown calls System.exit(0) in a
    // background coroutine, which would terminate the test JVM. The endpoint's
    // auth gate is covered by the generic auth tests above.
}
