package dev.nimbuspowered.nimbus.module.auth

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.AuthLevel
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.PluginDeployment
import dev.nimbuspowered.nimbus.module.PluginTarget
import dev.nimbuspowered.nimbus.module.SessionValidator
import dev.nimbuspowered.nimbus.module.auth.commands.DashboardCommand
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8000_Sessions
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8001_Challenges
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8002_Totp
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8003_RecoveryCodes
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8004_WebAuthnCredentials
import dev.nimbuspowered.nimbus.module.auth.migrations.AuthV8005_TotpLastUsedStep
import dev.nimbuspowered.nimbus.module.auth.routes.PlayerLookup
import dev.nimbuspowered.nimbus.module.auth.routes.authPublicDeliveryRoutes
import dev.nimbuspowered.nimbus.module.auth.routes.authRoutes
import dev.nimbuspowered.nimbus.module.auth.routes.authServiceRoutes
import dev.nimbuspowered.nimbus.module.auth.routes.passkeyRoutes
import dev.nimbuspowered.nimbus.module.auth.routes.profileRoutes
import dev.nimbuspowered.nimbus.module.auth.service.LoginChallengeService
import dev.nimbuspowered.nimbus.module.auth.service.PendingTotpStore
import dev.nimbuspowered.nimbus.module.auth.service.PermissionResolver
import dev.nimbuspowered.nimbus.module.auth.service.SessionService
import dev.nimbuspowered.nimbus.module.auth.service.TotpService
import dev.nimbuspowered.nimbus.module.auth.service.WebAuthnService
import dev.nimbuspowered.nimbus.module.auth.service.WsTicketStore
import dev.nimbuspowered.nimbus.module.service
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Dashboard authentication + session management module.
 *
 * Phase 3 additions:
 *   - `generate-code` and `request-magic-link` moved behind SERVICE auth
 *     (backend plugins hit them with NIMBUS_API_TOKEN, same as every other
 *     internal SDK → controller call)
 *   - New `GET /api/auth/sessions` + `POST /api/auth/logout-all` (service-auth)
 *     for the in-game `/dashboard sessions` and `/dashboard logout-all` commands
 *   - New `POST /api/auth/deliver-magic-link` (public, rate-limited) for the
 *     dashboard-initiated "type your MC name, click the link in-game" flow —
 *     fires an `AUTH_MAGIC_LINK_DELIVERY` module event that the SDK plugin
 *     renders as a clickable Adventure component
 *   - `[dashboard] public_url` is now pulled from the core `nimbus.toml`
 *     (with fallback to the module's own `auth.toml`) so magic-link URLs
 *     stay in sync with the dashboard deployment
 *   - `AuthMessages` operator-customisable chat templates
 */
class AuthModule : NimbusModule {
    override val id = "auth"
    override val name = "Auth"
    override val version: String get() = NimbusVersion.version
    override val description = "Dashboard authentication and role-based access control"

    private val logger = LoggerFactory.getLogger(AuthModule::class.java)

    @Volatile
    private lateinit var authConfig: AuthConfig

    @Volatile
    private lateinit var authMessages: AuthMessages

    private lateinit var configStore: AuthConfigStore
    private lateinit var messagesStore: AuthMessagesStore
    private lateinit var challengeService: LoginChallengeService
    private lateinit var sessionService: SessionService
    private lateinit var permissionResolver: PermissionResolver
    private lateinit var totpService: TotpService
    private lateinit var pendingTotpStore: PendingTotpStore
    private lateinit var webAuthnService: WebAuthnService
    private lateinit var wsTicketStore: WsTicketStore
    private lateinit var context: ModuleContext

    override suspend fun init(context: ModuleContext) {
        this.context = context
        val db = context.service<DatabaseManager>()
            ?: error("Auth module requires DatabaseManager")

        configStore = AuthConfigStore(
            moduleDir = context.moduleConfigDir(id),
            baseDir = context.baseDir
        )
        authConfig = configStore.loadOrCreate()
        val encryptionKey = configStore.ensureKey(authConfig)

        messagesStore = AuthMessagesStore(context.moduleConfigDir(id))
        authMessages = messagesStore.loadOrCreate()

        context.registerMigrations(listOf(
            AuthV8000_Sessions,
            AuthV8001_Challenges,
            AuthV8002_Totp,
            AuthV8003_RecoveryCodes,
            AuthV8004_WebAuthnCredentials,
            AuthV8005_TotpLastUsedStep
        ))

        // Auto-deploy the Velocity companion plugin. Every Nimbus network
        // routes players through a Velocity proxy, so the proxy-side plugin
        // alone can handle /dashboard commands AND deliver dashboard-initiated
        // magic-link chat components (Velocity owns the Player connection).
        // No backend plugin required — see AuthModule phase-3 refactor notes.
        context.registerPluginDeployment(PluginDeployment(
            resourcePath = "plugins/nimbus-auth-velocity.jar",
            fileName = "nimbus-auth-velocity.jar",
            displayName = "NimbusAuth",
            target = PluginTarget.VELOCITY
        ))

        val configSupplier = { authConfig }
        challengeService = LoginChallengeService(db, configSupplier)
        sessionService = SessionService(db, configSupplier)
        permissionResolver = PermissionResolver(context)
        totpService = TotpService(db, configSupplier, encryptionKey)
        pendingTotpStore = PendingTotpStore()
        webAuthnService = WebAuthnService(db, configSupplier) {
            context.service<NimbusConfig>()?.dashboard?.publicUrl?.takeIf { it.isNotBlank() }
                ?: authConfig.dashboard.publicUrl
        }
        wsTicketStore = WsTicketStore()

        // Prefer the core `[dashboard] public_url` so operators only have to
        // set the URL in one place. Falls back to the module's own config
        // for operators who explicitly set it there.
        val publicUrlSupplier: () -> String = {
            val core = context.service<NimbusConfig>()?.dashboard?.publicUrl
            core?.takeIf { it.isNotBlank() } ?: authConfig.dashboard.publicUrl
        }

        val eventBus = context.service<EventBus>()

        // Player-lookup adapter: resolves a name to (uuid, service) via the
        // Players module's PlayerTracker. Returns null (→ 404 PLAYER_OFFLINE)
        // if the Players module isn't loaded or the player isn't online.
        val playerLookupSupplier: () -> PlayerLookup? = {
            val tracker = context.getService(resolvePlayerTrackerClass())
            if (tracker == null) null else PlayerLookup { name ->
                val found = invokePlayerTracker(tracker, name)
                found
            }
        }

        // Service-auth block: `/api/auth/generate-code`, `/request-magic-link`,
        // `/sessions`, `/logout-all` — called by Bridge/SDK with the service
        // API token that they already receive via NIMBUS_API_TOKEN.
        context.registerRoutes(
            block = { authServiceRoutes(challengeService, sessionService, configSupplier, publicUrlSupplier) },
            auth = AuthLevel.SERVICE
        )

        // Public block: consume-challenge, totp-verify, logout, me — handle
        // their own bearer-session auth because they run before/without a
        // service token.
        context.registerRoutes(
            block = {
                authRoutes(
                    challengeService,
                    sessionService,
                    permissionResolver,
                    totpService,
                    pendingTotpStore,
                    wsTicketStore,
                    configSupplier
                )
            },
            auth = AuthLevel.NONE
        )

        // Profile routes — user-scoped TOTP enroll/confirm/disable. All
        // endpoints require a valid dashboard session (validated inline).
        context.registerRoutes(
            block = { profileRoutes(sessionService, totpService) },
            auth = AuthLevel.NONE
        )

        // Passkey / WebAuthn routes — register/finish require bearer session,
        // login/start+finish are public. All validation is inline.
        context.registerRoutes(
            block = {
                passkeyRoutes(
                    webAuthnService,
                    sessionService,
                    permissionResolver,
                    totpService,
                    pendingTotpStore
                )
            },
            auth = AuthLevel.NONE
        )

        // Public delivery endpoint — dashboard calls this with just a MC name.
        // Kept public so it works before any session exists. Global rate limiter
        // applies (120/min). The challenge service itself caps 5 magic-link
        // issuances per uuid per minute, so dashboard-initiated floods are
        // already bounded.
        context.registerRoutes(
            block = {
                authPublicDeliveryRoutes(
                    challengeService = challengeService,
                    configSupplier = configSupplier,
                    publicUrlSupplier = publicUrlSupplier,
                    playerLookupSupplier = playerLookupSupplier,
                    eventBus = eventBus
                )
            },
            auth = AuthLevel.NONE
        )

        // Register the Bridge-facing `/nimbus dashboard …` command. Replaces
        // the old standalone `/dashboard` in the Velocity plugin so auth
        // follows the same pattern as punish/perms/scaling/etc.
        context.registerCommand(DashboardCommand(
            challengeService = challengeService,
            sessionService = sessionService,
            publicUrlSupplier = publicUrlSupplier,
            configSupplier = configSupplier
        ))

        context.registerService(LoginChallengeService::class.java, challengeService)
        context.registerService(SessionService::class.java, sessionService)
        context.registerService(TotpService::class.java, totpService)
        context.registerService(SessionValidator::class.java, SessionValidator { raw ->
            // WebSocket / SSE callers exchange their bearer for a short-lived
            // ticket before connecting, so the raw session token never ends
            // up in a URL query string. Redeem tickets transparently here —
            // regular bearers fall through to the normal session lookup.
            val trimmed = raw.trim()
            val effective = if (wsTicketStore.looksLikeTicket(trimmed)) {
                wsTicketStore.consume(trimmed) ?: return@SessionValidator null
            } else {
                trimmed
            }
            sessionService.validate(effective)
        })

        logger.info("Auth module initialised (magic_link_enabled={}, public_url={})",
            authConfig.loginChallenge.magicLinkEnabled, publicUrlSupplier())
    }

    override suspend fun enable() {
        if (!permissionResolver.isPermsAvailable()) {
            logger.warn("Perms module is not loaded — dashboard RBAC unavailable. " +
                "API tokens still work as admin; MC-account sessions will have no permissions.")
            return
        }

        val eventBus = context.service<EventBus>() ?: run {
            logger.warn("EventBus not available — permission refresh on mutation disabled.")
            return
        }

        context.scope.launch {
            eventBus.subscribe().collect { event ->
                if (event !is NimbusEvent.ModuleEvent || event.moduleId != "perms") return@collect
                try {
                    handlePermsEvent(event)
                } catch (e: Exception) {
                    logger.warn("Failed to handle perms event {}: {}", event.type, e.message)
                }
            }
        }
    }

    private suspend fun handlePermsEvent(event: NimbusEvent.ModuleEvent) {
        when (event.type) {
            "PLAYER_PERMISSIONS_UPDATED",
            "PLAYER_PROMOTED",
            "PLAYER_DEMOTED" -> {
                val uuid = event.data["uuid"] ?: return
                val fresh = permissionResolver.resolve(uuid)
                val updated = sessionService.refreshPermissionsFor(uuid, fresh)
                if (updated > 0) {
                    logger.debug("Refreshed permissions_snapshot for {} active session(s) of {}", updated, uuid)
                }
            }
            "PERMISSION_GROUP_CREATED",
            "PERMISSION_GROUP_UPDATED",
            "PERMISSION_GROUP_DELETED",
            "PERMISSION_TRACK_CREATED",
            "PERMISSION_TRACK_DELETED" -> {
                sessionService.refreshAllActive { uuid -> permissionResolver.resolve(uuid) }
            }
        }
    }

    override fun disable() {
        // Services are stateless in Phase 1; nothing to tear down.
    }

    /** Test / introspection accessor. */
    fun currentConfig(): AuthConfig = authConfig

    /** Current message templates — exposed for callers that want to push
     *  fresh text after reloading `messages.toml`. */
    fun currentMessages(): AuthMessages = authMessages

    // ── Reflective PlayerTracker lookup ──────────────────────────────
    //
    // The auth module doesn't have a compile-time dependency on the players
    // module — we grab PlayerTracker reflectively so the auth JAR still loads
    // cleanly when players/ isn't installed. The Class<*> lookup happens once
    // per request and is cheap.

    private fun resolvePlayerTrackerClass(): Class<Any> {
        @Suppress("UNCHECKED_CAST")
        return (runCatching {
            Class.forName("dev.nimbuspowered.nimbus.module.players.PlayerTracker")
        }.getOrNull() ?: Any::class.java) as Class<Any>
    }

    /** Calls `tracker.getPlayerByName(name)` via reflection, unpacks uuid + currentService. */
    private fun invokePlayerTracker(tracker: Any, name: String): Pair<String, String>? {
        return runCatching {
            val method = tracker.javaClass.getMethod("getPlayerByName", String::class.java)
            val result = method.invoke(tracker, name) ?: return null
            val uuid = result.javaClass.getMethod("getUuid").invoke(result) as? String ?: return null
            val service = result.javaClass.getMethod("getCurrentService").invoke(result) as? String ?: return null
            uuid to service
        }.getOrNull()
    }
}
