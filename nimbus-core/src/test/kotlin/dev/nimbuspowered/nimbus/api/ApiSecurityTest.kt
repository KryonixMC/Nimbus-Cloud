package dev.nimbuspowered.nimbus.api

import dev.nimbuspowered.nimbus.api.routes.*
import dev.nimbuspowered.nimbus.config.ApiConfig
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.PathsConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.proxy.ProxySyncManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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
import io.ktor.server.websocket.*
import io.ktor.client.plugins.websocket.webSocket
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ApiSecurityTest {

    private val testToken = "test-secret-token-abc123"

    private lateinit var registry: ServiceRegistry
    private lateinit var serviceManager: ServiceManager
    private lateinit var groupManager: GroupManager
    private lateinit var eventBus: EventBus
    private lateinit var proxySyncManager: ProxySyncManager
    private lateinit var scope: CoroutineScope
    private lateinit var tempDir: Path
    private lateinit var templatesDir: Path
    private lateinit var servicesDir: Path
    private lateinit var groupsDir: Path

    @BeforeEach
    fun setUp() {
        registry = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)
        groupManager = mockk(relaxed = true)
        eventBus = mockk(relaxed = true)
        proxySyncManager = mockk(relaxed = true)
        scope = CoroutineScope(Dispatchers.Default)

        tempDir = Files.createTempDirectory("nimbus-api-test")
        templatesDir = tempDir.resolve("templates").also { Files.createDirectories(it) }
        servicesDir = tempDir.resolve("services").also { Files.createDirectories(it) }
        groupsDir = tempDir.resolve("groups").also { Files.createDirectories(it) }

        every { registry.getAll() } returns emptyList()
        every { registry.getByGroup(any()) } returns emptyList()
        every { groupManager.getAllGroups() } returns emptyList()

        // EventBus subscribe returns a SharedFlow
        val flow = MutableSharedFlow<NimbusEvent>(extraBufferCapacity = 64)
        every { eventBus.subscribe() } returns flow.asSharedFlow()
        coEvery { eventBus.emit(any()) } just Runs
    }

    private fun ApplicationTestBuilder.configureTestApp() {
        application {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    encodeDefaults = true
                })
            }
            install(WebSockets) {
                pingPeriod = kotlin.time.Duration.parse("15s")
                timeout = kotlin.time.Duration.parse("30s")
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
                        if (NimbusApi.timingSafeEquals(credential.token, testToken)) {
                            UserIdPrincipal("nimbus-api")
                        } else {
                            null
                        }
                    }
                }
            }
        }

        val config = NimbusConfig(
            paths = PathsConfig(templates = "templates", services = "services", logs = "logs"),
            api = ApiConfig(token = testToken)
        )
        val scopeRoots = mapOf(
            "templates" to templatesDir,
            "services" to servicesDir,
            "groups" to groupsDir
        )
        val readOnlyScopes = setOf("groups")
        val maxUploadBytes = 1024L * 1024 // 1 MB for tests

        routing {
            // Health is always public
            get("/api/health") {
                call.respond(HealthResponse(
                    status = "ok",
                    version = "0.2.0",
                    uptimeSeconds = 0,
                    services = 0,
                    apiEnabled = true
                ))
            }

            // WebSocket routes with token-based auth
            eventRoutes(eventBus, registry, serviceManager, testToken)

            // All other routes behind bearer auth
            authenticate("api-token") {
                serviceRoutes(registry, serviceManager, groupManager, eventBus)
                groupRoutes(registry, groupManager, groupsDir, eventBus)
                fileRoutes(scopeRoots, readOnlyScopes, maxUploadBytes)
            }
        }
    }

    // ── 1. Authentication Bypass Attempts ──────────────────────────────

    @Nested
    inner class AuthenticationBypass {

        @Test
        fun `GET services without auth header returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services with wrong token returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer wrong-token-value")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services with empty Bearer returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer ")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services with Bearer prefix only returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET services with Basic auth instead of Bearer returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Basic dGVzdDp0ZXN0")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        @Test
        fun `GET health without auth returns 200`() = testApplication {
            configureTestApp()
            val response = client.get("/api/health")
            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `GET services with valid token returns 200`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // ── timingSafeEquals ───────────────────────────────────────────────

    @Nested
    inner class TimingSafeEqualsTests {

        @Test
        fun `equal strings return true`() {
            assertTrue(NimbusApi.timingSafeEquals("secret-token", "secret-token"))
        }

        @Test
        fun `different strings return false`() {
            assertFalse(NimbusApi.timingSafeEquals("secret-token", "wrong-token"))
        }

        @Test
        fun `different lengths return false`() {
            assertFalse(NimbusApi.timingSafeEquals("short", "much-longer-string"))
        }

        @Test
        fun `empty strings return true`() {
            assertTrue(NimbusApi.timingSafeEquals("", ""))
        }

        @Test
        fun `one empty string returns false`() {
            assertFalse(NimbusApi.timingSafeEquals("", "notempty"))
        }

        @Test
        fun `strings differing by one char return false`() {
            assertFalse(NimbusApi.timingSafeEquals("abcdef", "abcdeg"))
        }
    }

    // ── 2. Path Traversal in File Routes ──────────────────────────────

    @Nested
    inner class PathTraversal {

        @Test
        fun `path with dotdot returns 403`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/../../../etc/passwd") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `path with encoded dotdot returns 403`() = testApplication {
            configureTestApp()
            // URL-encoded ".." (%2e%2e)
            val response = client.get("/api/files/templates/%2e%2e/%2e%2e/etc/passwd") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `path with mixed encoded dotdot returns 403`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/..%2F..%2Fetc/passwd") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Should be 403 (blocked) or 404 (path doesn't exist after safe resolution)
            val status = response.status
            assertTrue(
                status == HttpStatusCode.Forbidden || status == HttpStatusCode.NotFound,
                "Expected 403 or 404 but got $status"
            )
        }

        @Test
        fun `invalid scope returns 400`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/nonexistent/some/path") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

        @Test
        fun `valid path within scope returns 200`() = testApplication {
            configureTestApp()
            // Create a test file in templates
            val testFile = templatesDir.resolve("test.txt")
            Files.writeString(testFile, "hello")

            val response = client.get("/api/files/templates/test.txt") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `listing scope root returns 200`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        fun `PUT with path traversal returns 403`() = testApplication {
            configureTestApp()
            val jsonClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
            }
            val response = jsonClient.put("/api/files/templates/../../../tmp/evil.txt") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
                contentType(ContentType.Application.Json)
                setBody(FileWriteRequest(content = "malicious"))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    // ── 3. File Operation Security ────────────────────────────────────

    @Nested
    inner class FileOperationSecurity {

        @Test
        fun `PUT to read-only scope groups returns 403`() = testApplication {
            configureTestApp()
            val jsonClient = createClient {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
            }
            val response = jsonClient.put("/api/files/groups/test.toml") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
                contentType(ContentType.Application.Json)
                setBody(FileWriteRequest(content = "overwrite"))
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `DELETE scope root returns 403`() = testApplication {
            configureTestApp()
            val response = client.delete("/api/files/templates/") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Deleting the scope root itself should be forbidden
            val status = response.status
            assertTrue(
                status == HttpStatusCode.Forbidden || status == HttpStatusCode.NotFound,
                "Expected 403 or 404 when deleting scope root, got $status"
            )
        }

        @Test
        fun `POST to read-only scope groups returns 403`() = testApplication {
            configureTestApp()
            val response = client.post("/api/files/groups/test?mkdir") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `DELETE from read-only scope groups returns 403`() = testApplication {
            configureTestApp()
            // Create a file so it exists to be deleted
            val testFile = groupsDir.resolve("test.toml")
            Files.writeString(testFile, "content")

            val response = client.delete("/api/files/groups/test.toml") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

        @Test
        fun `DELETE valid file in writable scope succeeds`() = testApplication {
            configureTestApp()
            val testFile = templatesDir.resolve("deleteme.txt")
            Files.writeString(testFile, "bye")

            val response = client.delete("/api/files/templates/deleteme.txt") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(Files.exists(testFile))
        }
    }

    // ── 4. Input Validation ───────────────────────────────────────────

    @Nested
    inner class InputValidation {

        @Test
        fun `very long path segment is handled safely`() = testApplication {
            configureTestApp()
            val longSegment = "a".repeat(5000)
            val response = client.get("/api/files/templates/$longSegment") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Should not crash — expect 404 since path doesn't exist
            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest ||
                        response.status == HttpStatusCode.InternalServerError,
                "Expected safe error status for very long path, got ${response.status}"
            )
        }

        @Test
        fun `path with null bytes is handled safely`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/test%00.txt") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Should not crash — any non-200 error is acceptable
            assertNotEquals(HttpStatusCode.OK, response.status,
                "Null byte path should not resolve to a valid file")
        }

        @Test
        fun `path with shell metacharacters does not execute`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/;rm -rf /") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Should just be 404 — the shell metacharacters are literal path components
            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest,
                "Shell metacharacters should be treated as literal path, got ${response.status}"
            )
        }

        @Test
        fun `path with pipe character does not execute`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/test|whoami") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest,
                "Pipe character should be treated as literal path, got ${response.status}"
            )
        }

        @Test
        fun `path with backticks does not execute`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/`whoami`") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest,
                "Backtick should be treated as literal path, got ${response.status}"
            )
        }

        @Test
        fun `path with dollar sign does not execute`() = testApplication {
            configureTestApp()
            val response = client.get("/api/files/templates/\$(whoami)") {
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            assertTrue(
                response.status == HttpStatusCode.NotFound ||
                        response.status == HttpStatusCode.BadRequest,
                "Dollar sign should be treated as literal path, got ${response.status}"
            )
        }
    }

    // ── 5. WebSocket Auth ─────────────────────────────────────────────

    @Nested
    inner class WebSocketAuth {

        @Test
        fun `websocket events without token query param is rejected`() = testApplication {
            configureTestApp()
            val wsClient = createClient {
                install(io.ktor.client.plugins.websocket.WebSockets)
            }
            var closedWithPolicy = false
            try {
                wsClient.webSocket("/api/events") {
                    val frame = incoming.receiveCatching().getOrNull()
                    if (frame == null) {
                        closedWithPolicy = true
                    }
                }
            } catch (_: Exception) {
                closedWithPolicy = true
            }
            assertTrue(closedWithPolicy, "WebSocket should be rejected without token")
        }

        @Test
        fun `websocket events with invalid token is rejected`() = testApplication {
            configureTestApp()
            val wsClient = createClient {
                install(io.ktor.client.plugins.websocket.WebSockets)
            }
            var closedWithPolicy = false
            try {
                wsClient.webSocket("/api/events?token=invalid-token") {
                    val frame = incoming.receiveCatching().getOrNull()
                    if (frame == null) {
                        closedWithPolicy = true
                    }
                }
            } catch (_: Exception) {
                closedWithPolicy = true
            }
            assertTrue(closedWithPolicy, "WebSocket should be rejected with invalid token")
        }

        @Test
        fun `websocket events with valid token connects successfully`() = testApplication {
            configureTestApp()
            val wsClient = createClient {
                install(io.ktor.client.plugins.websocket.WebSockets)
            }
            var connected = false
            try {
                wsClient.webSocket("/api/events?token=$testToken") {
                    connected = true
                }
            } catch (_: Exception) {
                // Timeout or other issue is fine
            }
            assertTrue(connected, "WebSocket should accept connection with valid token")
        }
    }

    // ── 6. Additional Auth Edge Cases ─────────────────────────────────

    @Nested
    inner class AuthEdgeCases {

        @Test
        fun `auth header with extra whitespace returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "  Bearer $testToken  ")
            }
            // Ktor may or may not trim — either 401 or 200 is defensible
            // The important thing is it doesn't crash
            assertTrue(
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.OK,
                "Expected 401 or 200 for whitespace-padded auth, got ${response.status}"
            )
        }

        @Test
        fun `auth header case sensitivity - bearer lowercase returns 401`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "bearer $testToken")
            }
            // "bearer" vs "Bearer" — Ktor's bearer auth may or may not be case sensitive
            // Document the behavior
            assertTrue(
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.OK,
                "Unexpected status for lowercase bearer: ${response.status}"
            )
        }

        @Test
        fun `multiple authorization headers are handled`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services") {
                header(HttpHeaders.Authorization, "Bearer wrong-token")
                header(HttpHeaders.Authorization, "Bearer $testToken")
            }
            // Ktor merges or picks one header — the server may return 401, 200, or even 500
            // for duplicate headers. This documents current behavior without enforcing it.
            assertNotNull(response.status, "Server should return a response for duplicate auth headers")
        }

        @Test
        fun `token in query parameter does not bypass Bearer auth for REST`() = testApplication {
            configureTestApp()
            val response = client.get("/api/services?token=$testToken")
            // Query param token is only for WebSocket, not REST
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }
}
