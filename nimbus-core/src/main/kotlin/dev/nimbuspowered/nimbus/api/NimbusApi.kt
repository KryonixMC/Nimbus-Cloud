package dev.nimbuspowered.nimbus.api

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.api.auth.ApiScope
import dev.nimbuspowered.nimbus.api.auth.JwtTokenManager
import dev.nimbuspowered.nimbus.api.routes.*
import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.ApiConfig
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbuspowered.nimbus.module.ModuleContextImpl
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class NimbusApi(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager,
    private val proxySyncManager: dev.nimbuspowered.nimbus.proxy.ProxySyncManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val baseDir: Path,
    private val groupsDir: Path,
    private val configPath: Path,
    private val nodeManager: NodeManager? = null,
    private val loadBalancer: TcpLoadBalancer? = null,
    private val templatesDir: Path = baseDir.resolve("templates"),
    private val stressTestManager: dev.nimbuspowered.nimbus.stress.StressTestManager? = null,
    private val moduleContext: ModuleContextImpl? = null,
    private val moduleManager: dev.nimbuspowered.nimbus.module.ModuleManager? = null,
    private val dispatcher: dev.nimbuspowered.nimbus.console.CommandDispatcher? = null,
    private val databaseManager: dev.nimbuspowered.nimbus.database.DatabaseManager? = null
) {
    private val logger = LoggerFactory.getLogger(NimbusApi::class.java)

    val jwtTokenManager: JwtTokenManager? = if (config.api.jwtEnabled && config.api.token.isNotBlank()) {
        JwtTokenManager(config.api.token)
    } else null

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
            logger.warn("REST API token is empty — auto-generated token: {}...", generated.take(8))
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
                requestKey { call ->
                    call.request.local.remoteAddress
                }
            }
            register(RateLimitName("stress")) {
                rateLimiter(limit = 5, refillPeriod = 60.seconds)
                requestKey { call ->
                    call.request.local.remoteAddress
                }
            }
        }

        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, apiError(cause.message ?: "Bad request", ApiErrors.INVALID_INPUT))
            }
            exception<Throwable> { call, cause ->
                logger.error("Unhandled API error", cause)
                call.respond(HttpStatusCode.InternalServerError, apiError("Internal server error", ApiErrors.INTERNAL_ERROR))
            }
        }

        if (apiConfig.token.isNotBlank()) {
            val serviceToken = deriveServiceToken(apiConfig.token)
            install(Authentication) {
                // Full admin access — only for the master API token or JWT with admin scope
                bearer("api-token") {
                    authenticate { credential ->
                        // Check JWT first if it looks like one
                        if (jwtTokenManager != null && JwtTokenManager.looksLikeJwt(credential.token)) {
                            val jwt = jwtTokenManager.verifyToken(credential.token)
                            if (jwt != null) {
                                val scopes = jwtTokenManager.extractScopes(jwt)
                                if (ApiScope.ADMIN in scopes) {
                                    return@authenticate UserIdPrincipal("jwt:${jwt.subject}")
                                }
                            }
                            return@authenticate null
                        }
                        if (timingSafeEquals(credential.token, apiConfig.token)) {
                            UserIdPrincipal("nimbus-admin")
                        } else {
                            null
                        }
                    }
                }
                // Service-level access — accepts both master token, derived service token, or JWT with service scopes
                bearer("service-token") {
                    authenticate { credential ->
                        // Check JWT first if it looks like one
                        if (jwtTokenManager != null && JwtTokenManager.looksLikeJwt(credential.token)) {
                            val jwt = jwtTokenManager.verifyToken(credential.token)
                            if (jwt != null) {
                                val scopes = jwtTokenManager.extractScopes(jwt)
                                if (ApiScope.ADMIN in scopes || scopes.any { it in ApiScope.SERVICE_SCOPES }) {
                                    return@authenticate UserIdPrincipal("jwt:${jwt.subject}")
                                }
                            }
                            return@authenticate null
                        }
                        when {
                            timingSafeEquals(credential.token, apiConfig.token) -> UserIdPrincipal("nimbus-admin")
                            timingSafeEquals(credential.token, serviceToken) -> UserIdPrincipal("nimbus-service")
                            else -> null
                        }
                    }
                }
            }
            if (jwtTokenManager != null) {
                logger.info("JWT authentication enabled (HMAC-SHA256)")
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

            // Service-level routes — accessible with both master and service tokens
            val serviceRouteBlock: Route.() -> Unit = {
                serviceRoutes(registry, serviceManager, groupManager, eventBus)
                proxySyncRoutes(proxySyncManager, eventBus)
                groupRoutes(registry, groupManager, groupsDir, eventBus)
                networkRoutes(config, registry, groupManager, serviceManager, startedAt)
                maintenanceRoutes(proxySyncManager, eventBus)
                metricsRoutes(registry, groupManager, nodeManager, loadBalancer, proxySyncManager, startedAt)
                // Command proxy routes (for Bridge dynamic commands)
                if (dispatcher != null) commandRoutes(dispatcher)
                // Module service-level routes
                moduleContext?.serviceRoutes?.forEach { block -> block() }
            }

            // Admin-only routes — only accessible with the master API token
            val adminRouteBlock: Route.() -> Unit = {
                systemRoutes(config, groupManager, groupsDir, serviceManager, eventBus, scope, startedAt)
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
                if (moduleManager != null) {
                    moduleRoutes(moduleManager)
                }
                if (databaseManager != null && config.audit.enabled) {
                    auditRoutes(databaseManager)
                }
                tokenRoutes(jwtTokenManager)
                // Module admin-level routes
                moduleContext?.adminRoutes?.forEach { block -> block() }
            }

            // Module public routes (no auth)
            moduleContext?.publicRoutes?.forEach { block -> block() }

            // Routes with their own auth (query param token, not Bearer)
            val serviceToken = if (token.isNotBlank()) deriveServiceToken(token) else ""
            eventRoutes(eventBus, registry, serviceManager, token, serviceToken)
            templateRoutes(templatesDir, config.cluster.token)

            if (token.isNotBlank()) {
                authenticate("service-token") {
                    serviceRouteBlock()
                }
                authenticate("api-token") {
                    adminRouteBlock()
                }
            } else {
                serviceRouteBlock()
                adminRouteBlock()
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

        /**
         * Derives a restricted service token from the master API token using HMAC-SHA256.
         * Game servers receive this token instead of the master token, limiting their API access
         * to service-level endpoints only (no admin operations like config changes, file access,
         * stress tests, or cluster management).
         */
        fun deriveServiceToken(masterToken: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(masterToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal("nimbus-service-token".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
