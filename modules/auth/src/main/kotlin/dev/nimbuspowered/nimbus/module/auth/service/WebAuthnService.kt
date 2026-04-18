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

    /** Build a fresh `RelyingParty` each call — cheap, avoids stale config. */
    private fun relyingParty(): RelyingParty {
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
            .credentialRepository(ExposedCredentialRepository())
            .origins(origins)
            // We don't verify attestation certificates — user is already
            // authenticated (session bearer) at enroll time, so authenticator
            // provenance doesn't add meaningful trust.
            .allowOriginPort(true)
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
        val count = newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.selectAll()
                .where { DashboardWebAuthnCredentials.uuid eq uuid }
                .count()
        }
        require(count < cfg.maxCredentialsPerUser) {
            "Too many passkeys registered (max ${cfg.maxCredentialsPerUser})"
        }

        val userHandle = uuidToBytes(uuid)
        val userIdentity = UserIdentity.builder()
            .name(name)
            .displayName(name)
            .id(YubiByteArray(userHandle))
            .build()
        val options = relyingParty().startRegistration(
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
        val result: RegistrationResult = relyingParty().finishRegistration(
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
        val req = relyingParty().startAssertion(
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
        val result: AssertionResult = relyingParty().finishAssertion(
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
        val mcName = newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardWebAuthnCredentials.selectAll()
                .where { DashboardWebAuthnCredentials.credentialId eq credentialId }
                .firstOrNull()?.get(DashboardWebAuthnCredentials.mcName)
        } ?: error("Credential row missing after successful assertion")
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

    private fun cleanupExpired() {
        if (pending.size < 32) return
        val now = System.currentTimeMillis()
        pending.entries.removeIf { it.value.expiresAt < now }
    }

    /** `CredentialRepository` backed by Exposed. Called by Yubico during ceremonies. */
    private inner class ExposedCredentialRepository : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): MutableSet<PublicKeyCredentialDescriptor> {
            // `username` is the MC name we passed into UserIdentity. We stored uuid,
            // not name, so we resolve by looking up credentials whose owner currently
            // matches — name-based login is only used for non-resident flows, which we
            // don't advertise. Keep this best-effort via the name→uuid hint embedded
            // in the DB `name` column (createdAt-sorted). Usernameless flows (common
            // case) skip this method entirely.
            return mutableSetOf()
        }

        override fun getUserHandleForUsername(username: String): Optional<YubiByteArray> =
            Optional.empty()

        override fun getUsernameForUserHandle(userHandle: YubiByteArray): Optional<String> {
            // Yubico needs *some* username string associated with the user handle.
            // We synthesise it from the MC UUID so the ceremony never collides;
            // the dashboard uses the UUID directly for session issuance.
            return try {
                Optional.of(bytesToUuid(userHandle.bytes))
            } catch (_: Exception) {
                Optional.empty()
            }
        }

        override fun lookup(credentialId: YubiByteArray, userHandle: YubiByteArray): Optional<RegisteredCredential> {
            val credId = credentialId.base64Url
            val uuid = try { bytesToUuid(userHandle.bytes) } catch (_: Exception) { return Optional.empty() }
            val row = runCatching {
                kotlinx.coroutines.runBlocking {
                    newSuspendedTransaction(Dispatchers.IO, db.database) {
                        DashboardWebAuthnCredentials.selectAll()
                            .where {
                                (DashboardWebAuthnCredentials.credentialId eq credId) and
                                    (DashboardWebAuthnCredentials.uuid eq uuid)
                            }
                            .firstOrNull()
                    }
                }
            }.getOrNull() ?: return Optional.empty()
            return Optional.of(
                RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(userHandle)
                    .publicKeyCose(YubiByteArray(row[DashboardWebAuthnCredentials.publicKeyCose]))
                    .signatureCount(row[DashboardWebAuthnCredentials.signCount])
                    .build()
            )
        }

        override fun lookupAll(credentialId: YubiByteArray): MutableSet<RegisteredCredential> {
            val credId = credentialId.base64Url
            val rows = runCatching {
                kotlinx.coroutines.runBlocking {
                    newSuspendedTransaction(Dispatchers.IO, db.database) {
                        DashboardWebAuthnCredentials.selectAll()
                            .where { DashboardWebAuthnCredentials.credentialId eq credId }
                            .toList()
                    }
                }
            }.getOrNull() ?: return mutableSetOf()
            return rows.mapTo(mutableSetOf()) { row ->
                RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(YubiByteArray(uuidToBytes(row[DashboardWebAuthnCredentials.uuid])))
                    .publicKeyCose(YubiByteArray(row[DashboardWebAuthnCredentials.publicKeyCose]))
                    .signatureCount(row[DashboardWebAuthnCredentials.signCount])
                    .build()
            }
        }
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
}
