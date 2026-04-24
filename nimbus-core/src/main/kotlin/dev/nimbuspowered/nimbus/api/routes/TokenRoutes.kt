package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.api.auth.ApiScope
import dev.nimbuspowered.nimbus.api.auth.JwtTokenManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    val subject: String,
    val scopes: List<String>,
    val expiresInSeconds: Long = 86400
)

@Serializable
data class TokenResponse(
    val token: String,
    val subject: String,
    val scopes: List<String>,
    val expiresInSeconds: Long
)

@Serializable
data class ScopesResponse(
    val scopes: List<String>
)

fun Route.tokenRoutes(jwtTokenManager: JwtTokenManager?) {
    route("/api/tokens") {
        post {
            if (jwtTokenManager == null) {
                call.respond(HttpStatusCode.BadRequest,
                    apiError("JWT is not enabled. Set jwt_enabled = true in [api] config.", ApiError.VALIDATION_FAILED))
                return@post
            }

            val request = call.receive<TokenRequest>()

            if (request.subject.isBlank()) {
                call.respond(HttpStatusCode.BadRequest,
                    apiError("Subject must not be blank", ApiError.VALIDATION_FAILED))
                return@post
            }

            val invalidScopes = request.scopes.filter { it !in ApiScope.ALL }
            if (invalidScopes.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest,
                    apiError("Invalid scopes: ${invalidScopes.joinToString()}", ApiError.VALIDATION_FAILED))
                return@post
            }

            if (request.expiresInSeconds < 60) {
                call.respond(HttpStatusCode.BadRequest,
                    apiError("Token must be valid for at least 60 seconds", ApiError.VALIDATION_FAILED))
                return@post
            }

            val token = jwtTokenManager.generateToken(
                subject = request.subject,
                scopes = request.scopes.toSet(),
                expiresInSeconds = request.expiresInSeconds
            )

            call.respond(HttpStatusCode.Created, TokenResponse(
                token = token,
                subject = request.subject,
                scopes = request.scopes,
                expiresInSeconds = request.expiresInSeconds
            ))
        }

        get("/scopes") {
            call.respond(ScopesResponse(scopes = ApiScope.ALL.sorted()))
        }
    }
}
