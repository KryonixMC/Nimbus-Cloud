package dev.nimbuspowered.nimbus.module.auth

import dev.nimbuspowered.nimbus.config.StrictToml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

@Serializable
data class AuthConfig(
    val sessions: SessionsConfig = SessionsConfig(),
    @SerialName("login_challenge")
    val loginChallenge: LoginChallengeConfig = LoginChallengeConfig(),
    val dashboard: DashboardConfigBlock = DashboardConfigBlock(),
    val totp: TotpConfig = TotpConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val webauthn: WebAuthnConfig = WebAuthnConfig()
)

@Serializable
data class SessionsConfig(
    @SerialName("ttl_seconds")
    val ttlSeconds: Long = 604_800,        // 7 days
    @SerialName("rolling_refresh")
    val rollingRefresh: Boolean = true,
    @SerialName("max_per_user")
    val maxPerUser: Int = 10
)

@Serializable
data class LoginChallengeConfig(
    @SerialName("code_ttl_seconds")
    val codeTtlSeconds: Long = 60,
    @SerialName("code_length")
    val codeLength: Int = 6,
    @SerialName("code_alphabet")
    val codeAlphabet: String = "numeric",   // "numeric" or "alphanumeric"
    @SerialName("magic_link_ttl_seconds")
    val magicLinkTtlSeconds: Long = 60,
    @SerialName("magic_link_token_bytes")
    val magicLinkTokenBytes: Int = 32,
    @SerialName("magic_link_enabled")
    val magicLinkEnabled: Boolean = true,
    @SerialName("max_generates_per_minute")
    val maxGeneratesPerMinute: Int = 5,
    /**
     * Per-source-IP brute-force cap on `consume-challenge`. After this many
     * *failed* consume attempts within a 60s window from the same client IP,
     * further attempts are rejected with HTTP 429 until the window decays.
     * The global API limiter (120 req/min) is too coarse to defend the
     * 6-digit code's 60s TTL on its own — see audit FINDING-02 (v0.11.1).
     */
    @SerialName("max_consume_failures_per_minute")
    val maxConsumeFailuresPerMinute: Int = 10
)

@Serializable
data class DashboardConfigBlock(
    @SerialName("public_url")
    val publicUrl: String = "https://dashboard.nimbuspowered.org"
)

@Serializable
data class TotpConfig(
    val issuer: String = "Nimbus",
    @SerialName("require_for_admin")
    val requireForAdmin: Boolean = false,
    val window: Int = 1
)

@Serializable
data class SecurityConfig(
    @SerialName("token_encryption_key_file")
    val tokenEncryptionKeyFile: String = "config/modules/auth/session.key"
)

/**
 * WebAuthn / Passkey settings.
 *
 * `rpId` **must** match the dashboard's public hostname (no scheme, no port, no path).
 * `origins` is the list of allowed `https://host[:port]` the browser may be served from.
 * Empty strings auto-derive from `[dashboard] public_url`.
 */
@Serializable
data class WebAuthnConfig(
    val enabled: Boolean = true,
    @SerialName("rp_id")
    val rpId: String = "",
    @SerialName("rp_name")
    val rpName: String = "Nimbus Dashboard",
    val origins: List<String> = emptyList(),
    @SerialName("challenge_ttl_seconds")
    val challengeTtlSeconds: Long = 300,
    @SerialName("max_credentials_per_user")
    val maxCredentialsPerUser: Int = 10,
    /**
     * When true, the RP accepts assertions from the same host on any port.
     * Off by default — production deployments should register passkeys
     * against a single canonical origin. Flip on only for local dev where
     * the dashboard may float between `:3000` / `:8080` / etc.
     */
    @SerialName("allow_origin_port")
    val allowOriginPort: Boolean = false
)

/**
 * Loads or creates `config/modules/auth/auth.toml` and the companion encryption
 * key file. Returns the parsed config + the 32-byte key.
 */
class AuthConfigStore(
    private val moduleDir: Path,
    private val baseDir: Path,
    private val strict: Boolean = false
) {

    private val logger = LoggerFactory.getLogger(AuthConfigStore::class.java)
    private val configFile: Path = moduleDir.resolve("auth.toml")

    fun loadOrCreate(): AuthConfig {
        Files.createDirectories(moduleDir)
        if (!Files.exists(configFile)) {
            writeDefault()
            return AuthConfig()
        }
        return try {
            val text = Files.readString(configFile)
            StrictToml.strictDecode(
                AuthConfig.serializer(), text, "modules/auth/auth.toml", strict
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse {} — using defaults ({})", configFile, e.message)
            AuthConfig()
        }
    }

    /**
     * Ensures the encryption key file referenced by [SecurityConfig.tokenEncryptionKeyFile]
     * exists. Key is 32 bytes (AES-256). Generated with [SecureRandom], chmod 600
     * on POSIX systems. Returns the raw key material.
     */
    fun ensureKey(config: AuthConfig): ByteArray {
        val keyPath = baseDir.resolve(config.security.tokenEncryptionKeyFile)
        Files.createDirectories(keyPath.parent)
        if (Files.exists(keyPath)) {
            val bytes = Files.readAllBytes(keyPath)
            if (bytes.size == 32) return bytes
            logger.warn("Existing auth key at {} is {} bytes (expected 32) — regenerating", keyPath, bytes.size)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = keyPath.resolveSibling(keyPath.fileName.toString() + ".tmp")
        Files.write(tmp, key)
        Files.move(tmp, keyPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.setPosixFilePermissions(keyPath, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX FS (Windows) — rely on filesystem ACLs
        }
        logger.info("Generated new auth session key at {}", keyPath)
        return key
    }

    private fun writeDefault() {
        val content = """
            # Nimbus Auth module — dashboard auth + session config.
            # Edit and restart (or PUT /api/auth/config if exposed) to apply.

            [sessions]
            ttl_seconds = 604800        # 7 days
            rolling_refresh = true
            max_per_user = 10

            [login_challenge]
            code_ttl_seconds = 60
            code_length = 6
            code_alphabet = "numeric"        # or "alphanumeric"
            magic_link_ttl_seconds = 60
            magic_link_token_bytes = 32
            magic_link_enabled = true
            max_generates_per_minute = 5
            # Per-IP cap on failed consume attempts (60s window). Defends the
            # 6-digit code against brute-force inside its TTL.
            max_consume_failures_per_minute = 10

            [dashboard]
            public_url = "https://dashboard.nimbuspowered.org"

            [totp]
            issuer = "Nimbus"
            require_for_admin = false
            window = 1

            [security]
            token_encryption_key_file = "config/modules/auth/session.key"

            [webauthn]
            enabled = true
            # rp_id must match the dashboard hostname exactly (no scheme/port/path).
            # Leave empty to auto-derive from [dashboard] public_url.
            rp_id = ""
            rp_name = "Nimbus Dashboard"
            # Allowed browser origins. Leave empty to auto-derive from public_url.
            # Add localhost entries here for local development.
            origins = []
            challenge_ttl_seconds = 300
            max_credentials_per_user = 10
            # Off by default — keeps production RP binding strict.
            # Enable for local dev where the dashboard floats between ports.
            allow_origin_port = false
        """.trimIndent() + "\n"
        val tmp = configFile.resolveSibling(configFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
