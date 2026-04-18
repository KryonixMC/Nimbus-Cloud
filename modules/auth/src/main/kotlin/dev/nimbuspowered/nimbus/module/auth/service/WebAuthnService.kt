package dev.nimbuspowered.nimbus.module.auth.service

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.AssertionResult
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RegistrationResult
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray as YubiByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.db.DashboardWebAuthnCredentials
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Data we persist for a registered passkey.
 */
data class StoredCredential(
    val credentialIdBase64Url: String,
    val uuid: String,
    val mcName: String,
    val label: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val aaguid: String?
)

/**
 * Challenge-cache entry — kept in memory for the duration of a single
 * register/authenticate ceremony (≤ [AuthConfig.WebAuthnConfig.challengeTtlSeconds]).
 */
private sealed class PendingCeremony {
    abstract val expiresAt: Long

    data class Registration(
        val options: PublicKeyCredentialCreationOptions,
        val uuid: String,
        val name: String,
        val label: String,
        override val expiresAt: Long
    ) : PendingCeremony()

    data class Authentication(
        val request: AssertionRequest,
        override val expiresAt: Long
    ) : PendingCeremony()
}

/**
 * WebAuthn registration + authentication using the Yubico server-side ceremony helper.
 *
 * Users are keyed by MC UUID — the 16-byte UUID doubles as the credential's
 * `userHandle`, so the browser can offer discoverable credentials ("sign in as X")
 * without the user typing anything.
 */
class WebAuthnService(
    private val db: DatabaseManager,
    private val config: () -> AuthConfig,
    /** Fallback origin derived from the dashboard public URL. Used when `origins` is empty. */
    private val dashboardPublicUrl: () -> String
) {
    private val logger = LoggerFactory.getLogger(WebAuthnService::class.java)

    /** Active register/auth ceremonies, keyed by ceremony ID (opaque handle). */
    private val pending = ConcurrentHashMap<String, PendingCeremony>()

    fun isEnabled(): Boolean = config().webauthn.enabled

    /**
     * Build a fresh `RelyingParty` for one ceremony, backed by the supplied
     * in-memory credential snapshot.
     *
     * The Yubico library calls [CredentialRepository] methods synchronously
     * during `startRegistration` / `finishRegistration` / `startAssertion` /
     * `finishAssertion`. Doing blocking DB I/O from those callbacks — which
     * is what an earlier iteration did via `runBlocking` — can deadlock the
     * Ktor IO pool under load (the ceremony runs *on* an IO thread, and a
     * blocking lookup wants another IO thread from the same pool). Instead,
     * each call site pre-fetches exactly the rows that ceremony can touch
     * and hands them to this method; the repository never does I/O.
     */
    private fun relyingParty(cache: CredentialCache): RelyingParty {
        val cfg = config().webauthn
        val publicUrl = dashboardPublicUrl()
        val rpId = cfg.rpId.ifBlank { deriveRpId(publicUrl) }
        val origins = cfg.origins.takeIf { it.isNotEmpty() }?.toSet()
            ?: setOf(deriveOrigin(publicUrl))

        val identity = RelyingPartyIdentity.builder()
            .id(rpId)
            .name(cfg.rpName)
            .build()
        return RelyingParty.builder()
            .identity(identity)
            .credentialRepository(InMemoryCredentialRepository(cache))
            .origins(origins)
            // We don't verify attestation certificates — user is already
            // authenticated (session bearer) at enroll time, so authenticator
            // provenance doesn't add meaningful trust.
            //
            // `allowOriginPort` is operator-gated: off by default so production
            // RP binding is strict (https://host:443 and https://host:3000 are
            // separate origins), on for local-dev setups where the dashboard
            // may float between ports.
            .allowOriginPort(cfg.allowOriginPort)
            .allowOriginSubdomain(false)
            .build()
    }

    // ── Registration ────────────────────────────────────────────────────

    /**
     * Begin a registration ceremony. Returns the options the browser feeds into
     * `navigator.credentials.create()`, plus an opaque `ceremonyId` that must
     * be echoed back to [finishRegistration].
     */
    suspend fun startRegistration(
        uuid: String,
        name: String,
        label: String
    ): Pair<String, PublicKeyCredentialCreationOptions> {
        require(isEnabled()) { "WebAuthn disabled" }
        val cfg = config().webauthn
        // Pre-fetch every existing credential for this user, so Yubico can
        // populate `excludeCredentials` without blocking, and so the cap check
        // runs off the same snapshot.
        val existing = loadByUuid(uuid)
        require(existing.size < cfg.maxCredentialsPerUser) {
            "Too many passkeys registered (max ${cfg.maxCredentialsPerUser})"
        }

        val userHandle = uuidToBytes(uuid)
        val userIdentity = UserIdentity.builder()
            .name(name)
            .displayName(name)
            .id(YubiByteArray(userHandle))
            .build()
        val cache = CredentialCache(byUuid = mapOf(uuid to existing))
        val options = relyingParty(cache).startRegistration(
            StartRegistrationOptions.builder()
                .user(userIdentity)
                .authenticatorSelection(
                    AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.PREFERRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build()
                )
                .build()
        )
        val ceremonyId = UUID.randomUUID().toString()
        val expires = System.currentTimeMillis() + cfg.challengeTtlSeconds * 1000L
        pending[ceremonyId] = PendingCeremony.Registration(options, uuid, name, label, expires)
        cleanupExpired()
        return ceremonyId to options
    }

    /**
     * Finish a registration ceremony. [responseJson] is the WebAuthn
     * `PublicKeyCredential` JSON the browser produced.
     */
    suspend fun finishRegistration(
        ceremonyId: String,
        responseJson: String
    ): StoredCredential {
        val pc = pending.remove(ceremonyId) as? PendingCeremony.Registration
            ?: error("Unknown or expired ceremony")
        require(pc.expiresAt >= System.currentTimeMillis()) { "Ceremony expired" }

        val credential = PublicKeyCredential.parseRegistrationResponseJson(responseJson)
        // Finish-registration only needs the existing credential IDs for the
        // enrolling user (to reject collisions). Pre-fetch that slice.
        val cache = CredentialCache(byUuid = mapOf(pc.uuid to loadByUuid(pc.uuid)))
        val result: RegistrationResult = relyingParty(cache).finishRegistration(
            FinishRegistrationOptions.builder()
                .request(pc.options)
                .response(credential)
                .build()
        )
        val credentialId = result.keyId.id.base64Url
        val publicKeyCose = result.publicKeyCose.bytes
        val now = System.currentTimeMillis()
        val aaguid = result.aaguid.base64Url.takeIf { it.isNotBlank() }

        val label = pc.label.ifBlank { "Passkey" }.take(64)
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.insert {
                it[DashboardWebAuthnCredentials.credentialId] = credentialId
                it[DashboardWebAuthnCredentials.uuid] = pc.uuid
                it[DashboardWebAuthnCredentials.mcName] = pc.name
                it[DashboardWebAuthnCredentials.publicKeyCose] = publicKeyCose
                it[DashboardWebAuthnCredentials.signCount] = result.signatureCount
                it[DashboardWebAuthnCredentials.label] = label
                it[DashboardWebAuthnCredentials.createdAt] = now
                it[DashboardWebAuthnCredentials.aaguid] = aaguid
            }
        }
        logger.info("Registered passkey for {} (credId={}, label={})", pc.uuid, credentialId.take(12), label)
        return StoredCredential(credentialId, pc.uuid, pc.name, label, now, null, aaguid)
    }

    // ── Authentication ──────────────────────────────────────────────────

    /**
     * Begin an authentication ceremony. If [username] is null the ceremony is
     * "usernameless" — the browser picks a discoverable credential based on
     * the RP ID and returns the userHandle in the response.
     */
    suspend fun startAuthentication(username: String? = null): Pair<String, AssertionRequest> {
        require(isEnabled()) { "WebAuthn disabled" }
        // Username-scoped starts would need a name→credentials lookup; we
        // advertise usernameless (resident-key) flows only, so the cache is
        // empty and Yubico never touches it for this call.
        val cache = CredentialCache()
        val req = relyingParty(cache).startAssertion(
            StartAssertionOptions.builder()
                .apply {
                    if (username != null) username(username)
                    userVerification(UserVerificationRequirement.PREFERRED)
                }
                .build()
        )
        val ceremonyId = UUID.randomUUID().toString()
        val expires = System.currentTimeMillis() + config().webauthn.challengeTtlSeconds * 1000L
        pending[ceremonyId] = PendingCeremony.Authentication(req, expires)
        cleanupExpired()
        return ceremonyId to req
    }

    /**
     * Finish the authentication ceremony. Returns the MC UUID + name of the
     * authenticated user. Caller must issue a session.
     */
    suspend fun finishAuthentication(
        ceremonyId: String,
        responseJson: String
    ): Pair<String, String> {
        val pc = pending.remove(ceremonyId) as? PendingCeremony.Authentication
            ?: error("Unknown or expired ceremony")
        require(pc.expiresAt >= System.currentTimeMillis()) { "Ceremony expired" }

        val credential = PublicKeyCredential.parseAssertionResponseJson(responseJson)
        val credentialIdFromResponse = credential.id.base64Url
        // Pre-fetch the specific credential(s) Yubico will ask about during
        // `finishAssertion`. The library calls `lookup(credId, userHandle)`
        // once per assertion — giving it the row up-front avoids a blocking
        // DB hit inside the synchronous callback.
        val rows = loadByCredentialId(credentialIdFromResponse)
        if (rows.isEmpty()) error("Unknown credential id")
        val cache = CredentialCache(
            byCredentialId = mapOf(credentialIdFromResponse to rows),
            byUuid = rows.groupBy { it.uuid }.mapValues { it.value }
        )

        val result: AssertionResult = relyingParty(cache).finishAssertion(
            FinishAssertionOptions.builder()
                .request(pc.request)
                .response(credential)
                .build()
        )
        require(result.isSuccess) { "Assertion rejected" }

        val credentialId = result.credential.credentialId.base64Url
        val uuid = bytesToUuid(result.credential.userHandle.bytes)
        val now = System.currentTimeMillis()
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.update({ DashboardWebAuthnCredentials.credentialId eq credentialId }) {
                it[signCount] = result.signatureCount
                it[lastUsedAt] = now
            }
        }
        val mcName = rows.firstOrNull { it.credentialIdBase64Url == credentialId }?.mcName
            ?: error("Credential row missing after successful assertion")
        logger.info("Passkey login for {} ({}, credId={})", mcName, uuid, credentialId.take(12))
        return uuid to mcName
    }

    // ── Credential management ───────────────────────────────────────────

    suspend fun listCredentials(uuid: String): List<StoredCredential> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.selectAll()
                .where { DashboardWebAuthnCredentials.uuid eq uuid }
                .orderBy(DashboardWebAuthnCredentials.createdAt to SortOrder.DESC)
                .map {
                    StoredCredential(
                        credentialIdBase64Url = it[DashboardWebAuthnCredentials.credentialId],
                        uuid = it[DashboardWebAuthnCredentials.uuid],
                        mcName = it[DashboardWebAuthnCredentials.mcName],
                        label = it[DashboardWebAuthnCredentials.label],
                        createdAt = it[DashboardWebAuthnCredentials.createdAt],
                        lastUsedAt = it[DashboardWebAuthnCredentials.lastUsedAt],
                        aaguid = it[DashboardWebAuthnCredentials.aaguid]
                    )
                }
        }

    /** Deletes a credential if-and-only-if it belongs to [uuid]. Returns true if deleted. */
    suspend fun deleteCredential(uuid: String, credentialIdBase64Url: String): Boolean =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.deleteWhere {
                (DashboardWebAuthnCredentials.credentialId eq credentialIdBase64Url) and
                    (DashboardWebAuthnCredentials.uuid eq uuid)
            } > 0
        }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Drops expired `pending` ceremonies. Cheap to call — iterates the map at
     * most once every [CLEANUP_INTERVAL_MS], which keeps us from leaking
     * expired entries even when traffic stays below a size-based threshold.
     */
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val last = lastCleanupAt
        if (now - last < CLEANUP_INTERVAL_MS) return
        // Benign race: multiple threads may see the same stale value and all
        // run cleanup once. `removeIf` is thread-safe on ConcurrentHashMap.
        lastCleanupAt = now
        pending.entries.removeIf { it.value.expiresAt < now }
    }

    @Volatile
    private var lastCleanupAt: Long = 0L

    /**
     * Internal row shape used by the in-memory credential cache. Distinct from
     * the public [StoredCredential] DTO because the repository needs the raw
     * COSE public key + sign counter, which are implementation details.
     */
    private data class RawCredential(
        val credentialIdBase64Url: String,
        val uuid: String,
        val mcName: String,
        val publicKeyCose: ByteArray,
        val signCount: Long
    )

    /**
     * Snapshot of DB rows passed into a single ceremony. Indexing by both
     * credentialId and uuid lets the three [CredentialRepository] methods
     * we care about (`lookup`, `lookupAll`, `getCredentialIdsForUsername`)
     * serve their answers without touching the database.
     */
    private class CredentialCache(
        val byCredentialId: Map<String, List<RawCredential>> = emptyMap(),
        val byUuid: Map<String, List<RawCredential>> = emptyMap()
    )

    private suspend fun loadByUuid(uuid: String): List<RawCredential> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.selectAll()
                .where { DashboardWebAuthnCredentials.uuid eq uuid }
                .map { it.toRaw() }
        }

    private suspend fun loadByCredentialId(credentialId: String): List<RawCredential> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.selectAll()
                .where { DashboardWebAuthnCredentials.credentialId eq credentialId }
                .map { it.toRaw() }
        }

    private fun org.jetbrains.exposed.sql.ResultRow.toRaw(): RawCredential = RawCredential(
        credentialIdBase64Url = this[DashboardWebAuthnCredentials.credentialId],
        uuid = this[DashboardWebAuthnCredentials.uuid],
        mcName = this[DashboardWebAuthnCredentials.mcName],
        publicKeyCose = this[DashboardWebAuthnCredentials.publicKeyCose],
        signCount = this[DashboardWebAuthnCredentials.signCount]
    )

    /**
     * [CredentialRepository] served entirely from the pre-built [CredentialCache].
     * Never does DB I/O; callable from Yubico's synchronous callbacks without
     * risking a thread-pool deadlock.
     *
     * Non-resident / username-scoped flows are intentionally not supported —
     * the service only advertises discoverable credentials, so Yubico never
     * needs to resolve a username→handle mapping. The relevant methods return
     * empty / empty-optional and the ceremony proceeds off the resident key.
     */
    private inner class InMemoryCredentialRepository(
        private val cache: CredentialCache
    ) : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): MutableSet<PublicKeyCredentialDescriptor> =
            // Unsupported for non-resident flows. We only ship discoverable
            // credentials, so Yubico never drives a username-scoped `startAssertion`.
            mutableSetOf()

        override fun getUserHandleForUsername(username: String): Optional<YubiByteArray> =
            Optional.empty()

        override fun getUsernameForUserHandle(userHandle: YubiByteArray): Optional<String> {
            return try {
                Optional.of(bytesToUuid(userHandle.bytes))
            } catch (_: Exception) {
                Optional.empty()
            }
        }

        override fun lookup(credentialId: YubiByteArray, userHandle: YubiByteArray): Optional<RegisteredCredential> {
            val credId = credentialId.base64Url
            val uuid = try { bytesToUuid(userHandle.bytes) } catch (_: Exception) { return Optional.empty() }
            val row = cache.byCredentialId[credId]?.firstOrNull { it.uuid == uuid }
                ?: return Optional.empty()
            return Optional.of(row.toRegistered(credentialId, userHandle))
        }

        override fun lookupAll(credentialId: YubiByteArray): MutableSet<RegisteredCredential> {
            val credId = credentialId.base64Url
            val rows = cache.byCredentialId[credId] ?: return mutableSetOf()
            return rows.mapTo(mutableSetOf()) { row ->
                row.toRegistered(credentialId, YubiByteArray(uuidToBytes(row.uuid)))
            }
        }

        private fun RawCredential.toRegistered(credentialId: YubiByteArray, userHandle: YubiByteArray) =
            RegisteredCredential.builder()
                .credentialId(credentialId)
                .userHandle(userHandle)
                .publicKeyCose(YubiByteArray(publicKeyCose))
                .signatureCount(signCount)
                .build()
    }

    private fun uuidToBytes(uuid: String): ByteArray {
        val u = UUID.fromString(uuid)
        val buf = ByteBuffer.allocate(16)
        buf.putLong(u.mostSignificantBits)
        buf.putLong(u.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToUuid(bytes: ByteArray): String {
        require(bytes.size == 16) { "userHandle must be 16 bytes, got ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes)
        val msb = buf.long
        val lsb = buf.long
        return UUID(msb, lsb).toString()
    }

    private fun deriveRpId(publicUrl: String): String {
        return try {
            URI(publicUrl).host ?: "localhost"
        } catch (_: Exception) {
            "localhost"
        }
    }

    private fun deriveOrigin(publicUrl: String): String {
        return try {
            val u = URI(publicUrl)
            val scheme = u.scheme ?: "https"
            val host = u.host ?: "localhost"
            if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
        } catch (_: Exception) {
            "https://localhost"
        }
    }

    companion object {
        /** How often [cleanupExpired] actually sweeps the pending map. */
        private const val CLEANUP_INTERVAL_MS = 60_000L
    }
}
