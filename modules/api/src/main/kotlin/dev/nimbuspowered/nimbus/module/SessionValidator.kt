package dev.nimbuspowered.nimbus.module

/**
 * Bridge between the core API middleware and the auth module's session service.
 *
 * The auth module registers an implementation via [ModuleContext.registerService]
 * (`SessionValidator::class.java`) during init. Core's bearer authenticators look
 * this up to accept dashboard session tokens alongside the existing API tokens.
 *
 * Keeping the type in `modules/api` avoids a hard compile-time dep on the auth
 * module — the auth module can be disabled or replaced without breaking core.
 */
fun interface SessionValidator {
    /**
     * Validate a raw session token. Returns the [AuthPrincipal.UserSession] on
     * success, or `null` if the token is invalid, expired, or revoked.
     *
     * Implementations are expected to be suspend-safe — the caller wraps this
     * in `runBlocking` from Ktor's authentication block.
     */
    suspend fun validate(rawToken: String): AuthPrincipal.UserSession?
}
