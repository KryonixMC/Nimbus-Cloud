package dev.nimbuspowered.nimbus.api.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import org.slf4j.LoggerFactory
import java.util.Date

/**
 * Manages JWT token generation and verification for the Nimbus REST API.
 * Uses HMAC-SHA256 with the master API token as the signing key.
 */
class JwtTokenManager(secret: String) {

    private val logger = LoggerFactory.getLogger(JwtTokenManager::class.java)
    private val algorithm = Algorithm.HMAC256(secret)

    private val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .build()

    /**
     * Generates a signed JWT token.
     *
     * @param subject Identity of the token holder (e.g., "panel", "ci-bot", service name)
     * @param scopes Set of permission scopes (e.g., "services:read", "admin")
     * @param expiresInSeconds Token lifetime in seconds (default: 24 hours)
     */
    fun generateToken(
        subject: String,
        scopes: Set<String>,
        expiresInSeconds: Long = 86400
    ): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(ISSUER)
            .withSubject(subject)
            .withArrayClaim(CLAIM_SCOPES, scopes.toTypedArray())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + expiresInSeconds * 1000))
            .sign(algorithm)
    }

    /**
     * Verifies a JWT token's signature, issuer, and expiration.
     * Returns the decoded token or null if verification fails.
     */
    fun verifyToken(token: String): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            logger.debug("JWT verification failed: {}", e.message)
            null
        }
    }

    /**
     * Extracts the permission scopes from a decoded JWT.
     */
    fun extractScopes(jwt: DecodedJWT): Set<String> {
        return jwt.getClaim(CLAIM_SCOPES)
            .asList(String::class.java)
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Builds a JWTVerifier instance for Ktor's JWT auth plugin.
     */
    fun buildVerifier(): JWTVerifier = verifier

    companion object {
        const val ISSUER = "nimbus"
        const val CLAIM_SCOPES = "scopes"

        /** Quick check: does this token look like a JWT (three Base64 segments)? */
        fun looksLikeJwt(token: String): Boolean {
            return token.count { it == '.' } == 2
        }
    }
}
