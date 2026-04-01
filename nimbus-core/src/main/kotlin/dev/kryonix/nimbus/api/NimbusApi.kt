package dev.kryonix.nimbus.api

import dev.kryonix.nimbus.NimbusVersion
import dev.kryonix.nimbus.api.routes.*
import dev.kryonix.nimbus.cluster.NodeManager
import dev.kryonix.nimbus.config.ApiConfig
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.loadbalancer.TcpLoadBalancer
import dev.kryonix.nimbus.permissions.PermissionManager
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class NimbusApi(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager,
    private val permissionManager: PermissionManager,
    private val displayManager: dev.kryonix.nimbus.display.DisplayManager,
    private val proxySyncManager: dev.kryonix.nimbus.proxy.ProxySyncManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val baseDir: Path,
    private val groupsDir: Path,
    private val configPath: Path,
    private val nodeManager: NodeManager? = null,
    private val loadBalancer: TcpLoadBalancer? = null,
    private val templatesDir: Path = baseDir.resolve("templates"),
    private val stressTestManager: dev.kryonix.nimbus.stress.StressTestManager? = null
) {
    private val logger = LoggerFactory.getLogger(NimbusApi::class.java)

    val startedAt: Instant = Instant.now()

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = server != null

    val currentBind: String get() = config.api.bind
    val currentPort: Int get() = config.api.port

    fun start() {
        startWithConfig(config.api)
    }

    fun startWithConfig(apiConfig: ApiConfig) {
        if (server != null) {
            logger.warn("REST API is already running")
            return
        }

        val effectiveConfig = if (apiConfig.token.isBlank()) {
            val generated = generateToken()
            logger.warn("REST API token is empty — auto-generated token: {}", generated)
            logger.warn("Set [api] token in nimbus.toml to use a persistent token.")
            apiConfig.copy(token = generated)
        } else {
            apiConfig
        }

        try {
            server = embeddedServer(CIO, port = effectiveConfig.port, host = effectiveConfig.bind) {
                configurePlugins(effectiveConfig)
                configureRoutes(effectiveConfig.token)
            }

            server?.start(wait = false)
            logger.info("REST API started on http://{}:{}", effectiveConfig.bind, effectiveConfig.port)

            scope.launch {
                eventBus.emit(NimbusEvent.ApiStarted(effectiveConfig.bind, effectiveConfig.port))
            }
        } catch (e: Exception) {
            logger.error("Failed to start REST API: {}", e.message)
            server = null
            scope.launch {
                eventBus.emit(NimbusEvent.ApiError("Failed to start: ${e.message}"))
            }
        }
    }

    fun stop() {
        if (server == null) {
            logger.warn("REST API is not running")
            return
        }

        server?.stop(1000, 5000)
        server = null
        logger.info("REST API stopped")

        scope.launch {
            eventBus.emit(NimbusEvent.ApiStopped("manual stop"))
        }
    }

    fun token(): String = config.api.token

    private fun Application.configurePlugins(apiConfig: ApiConfig) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }

        install(WebSockets) {
            pingPeriod = kotlin.time.Duration.parse("15s")
            timeout = kotlin.time.Duration.parse("30s")
            maxFrameSize = 65536 // 64 KB
        }

        install(CORS) {
            val origins = apiConfig.allowedOrigins
            if (origins.isEmpty() || origins.singleOrNull() == "*") {
                anyHost()
                if (origins.isEmpty()) {
                    logger.warn("No CORS origins configured — API accepts requests from any origin. Set [api] allowed_origins in nimbus.toml for production.")
                }
            } else {
                origins.forEach { allowHost(it, schemes = listOf("http", "https")) }
            }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Patch)
        }

        install(RateLimit) {
            global {
                rateLimiter(limit = 120, refillPeriod = 60.seconds)
            }
            register(RateLimitName("stress")) {
                rateLimiter(limit = 5, refillPeriod = 60.seconds)
            }
        }

        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ApiMessage(false, cause.message ?: "Bad request"))
            }
            exception<Throwable> { call, cause ->
                logger.error("Unhandled API error", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiMessage(false, "Internal server error"))
            }
        }

        if (apiConfig.token.isNotBlank()) {
            install(Authentication) {
                bearer("api-token") {
                    authenticate { credential ->
                        if (timingSafeEquals(credential.token, apiConfig.token)) {
                            UserIdPrincipal("nimbus-api")
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun Application.configureRoutes(token: String) {
        routing {
            // Health endpoint is always public
            get("/api/health") {
                val uptime = Duration.between(startedAt, Instant.now()).seconds
                call.respond(HealthResponse(
                    status = "ok",
                    version = VERSION,
                    uptimeSeconds = uptime,
                    services = registry.getAll().size,
                    apiEnabled = true
                ))
            }

            // All other routes require auth if token is set
            val scopeRoots = mapOf(
                "templates" to baseDir.resolve(config.paths.templates).toAbsolutePath(),
                "services" to baseDir.resolve(config.paths.services).toAbsolutePath(),
                "groups" to groupsDir.toAbsolutePath()
            )
            val readOnlyScopes = setOf("groups")
            val maxUploadBytes = 100L * 1024 * 1024 // 100 MB

            val routeBlock: Route.() -> Unit = {
                metricsRoutes(registry, groupManager, nodeManager, loadBalancer, proxySyncManager, startedAt)
                serviceRoutes(registry, serviceManager, groupManager, eventBus)
                groupRoutes(registry, groupManager, groupsDir, eventBus)
                permissionRoutes(permissionManager, eventBus)
                networkRoutes(config, registry, groupManager, serviceManager, startedAt)
                systemRoutes(config, groupManager, groupsDir, serviceManager, eventBus, scope, startedAt)
                displayRoutes(displayManager)
                proxySyncRoutes(proxySyncManager, eventBus)
                maintenanceRoutes(proxySyncManager, eventBus)
                if (stressTestManager != null) {
                    rateLimit(RateLimitName("stress")) {
                        stressRoutes(stressTestManager)
                    }
                }
                fileRoutes(scopeRoots, readOnlyScopes, maxUploadBytes)
                configRoutes(config, configPath)
                if (nodeManager != null || loadBalancer != null) {
                    clusterRoutes(nodeManager, loadBalancer, registry)
                }
            }

            // Routes with their own auth (query param token, not Bearer)
            eventRoutes(eventBus, registry, serviceManager, token)
            templateRoutes(templatesDir, config.cluster.token)

            if (token.isNotBlank()) {
                authenticate("api-token") {
                    routeBlock()
                }
            } else {
                routeBlock()
            }
        }
    }

    companion object {
        val VERSION = NimbusVersion.version

        /**
         * Timing-safe token comparison to prevent timing attacks.
         */
        fun timingSafeEquals(a: String, b: String): Boolean {
            val aBytes = a.toByteArray(Charsets.UTF_8)
            val bBytes = b.toByteArray(Charsets.UTF_8)
            return MessageDigest.isEqual(aBytes, bBytes)
        }

        private fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
