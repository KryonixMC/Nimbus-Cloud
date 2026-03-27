package dev.nimbus.api

import dev.nimbus.api.routes.*
import dev.nimbus.config.ApiConfig
import dev.nimbus.config.NimbusConfig
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class NimbusApi(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val groupsDir: Path
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

        if (apiConfig.token.isBlank()) {
            logger.warn("REST API token is empty — API will be accessible without authentication!")
        }

        try {
            server = embeddedServer(CIO, port = apiConfig.port, host = apiConfig.bind) {
                configurePlugins(apiConfig.token)
                configureRoutes(apiConfig.token)
            }

            server?.start(wait = false)
            logger.info("REST API started on http://{}:{}", apiConfig.bind, apiConfig.port)

            scope.launch {
                eventBus.emit(NimbusEvent.ApiStarted(apiConfig.bind, apiConfig.port))
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

    private fun Application.configurePlugins(token: String) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }

        install(WebSockets) {
            pingPeriod = kotlin.time.Duration.parse("15s")
            timeout = kotlin.time.Duration.parse("30s")
            maxFrameSize = Long.MAX_VALUE
        }

        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)
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

        if (token.isNotBlank()) {
            install(Authentication) {
                bearer("api-token") {
                    authenticate { credential ->
                        if (credential.token == token) {
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
                    version = "0.2.0",
                    uptimeSeconds = uptime,
                    services = registry.getAll().size,
                    apiEnabled = true
                ))
            }

            // All other routes require auth if token is set
            val routeBlock: Route.() -> Unit = {
                serviceRoutes(registry, serviceManager, groupManager, eventBus)
                groupRoutes(registry, groupManager, groupsDir)
                networkRoutes(config, registry, groupManager, serviceManager, startedAt)
                systemRoutes(config, groupManager, groupsDir, serviceManager, startedAt)
                eventRoutes(eventBus, registry, serviceManager)
            }

            if (token.isNotBlank()) {
                authenticate("api-token") {
                    routeBlock()
                }
            } else {
                routeBlock()
            }
        }
    }
}
