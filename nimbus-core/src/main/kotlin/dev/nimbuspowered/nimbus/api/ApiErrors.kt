package dev.nimbuspowered.nimbus.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Machine-readable API error codes returned in the `error` field of [ApiMessage].
 * Each entry carries its canonical HTTP status so route handlers can stay terse:
 *   `call.respond(err.defaultStatus, apiError("msg", err))`
 * or via the [fail] extension:
 *   `call.fail(err, "msg")`.
 *
 * The `code` string is the stable wire format — tests lock it against accidental rename.
 */
enum class ApiError(val code: String, val defaultStatus: HttpStatusCode) {

    // ── Auth (core) ─────────────────────────────────────────────
    AUTH_FAILED("AUTH_FAILED", HttpStatusCode.Unauthorized),
    UNAUTHORIZED("UNAUTHORIZED", HttpStatusCode.Unauthorized),
    FORBIDDEN("FORBIDDEN", HttpStatusCode.Forbidden),
    READ_ONLY("READ_ONLY", HttpStatusCode.Forbidden),

    // ── Auth (module — folded in from AuthErrors) ───────────────
    AUTH_CHALLENGE_INVALID("AUTH_CHALLENGE_INVALID", HttpStatusCode.Unauthorized),
    AUTH_RATE_LIMITED("AUTH_RATE_LIMITED", HttpStatusCode.TooManyRequests),
    AUTH_DISABLED("AUTH_DISABLED", HttpStatusCode.Forbidden),
    AUTH_SESSION_INVALID("AUTH_SESSION_INVALID", HttpStatusCode.Unauthorized),
    AUTH_SESSION_EXPIRED("AUTH_SESSION_EXPIRED", HttpStatusCode.Unauthorized),
    AUTH_PLAYER_OFFLINE("AUTH_PLAYER_OFFLINE", HttpStatusCode.Conflict),
    AUTH_TOTP_REQUIRED("AUTH_TOTP_REQUIRED", HttpStatusCode.Unauthorized),
    AUTH_TOTP_INVALID("AUTH_TOTP_INVALID", HttpStatusCode.Unauthorized),
    AUTH_TOTP_ALREADY_ENABLED("AUTH_TOTP_ALREADY_ENABLED", HttpStatusCode.Conflict),
    AUTH_MAGIC_LINK_INVALID("AUTH_MAGIC_LINK_INVALID", HttpStatusCode.Unauthorized),
    AUTH_LOGIN_CHALLENGE_EXPIRED("AUTH_LOGIN_CHALLENGE_EXPIRED", HttpStatusCode.Gone),

    // ── Passkeys ────────────────────────────────────────────────
    PASSKEY_DISABLED("PASSKEY_DISABLED", HttpStatusCode.ServiceUnavailable),
    PASSKEY_LIMIT_REACHED("PASSKEY_LIMIT_REACHED", HttpStatusCode.Conflict),
    PASSKEY_REGISTER_FAILED("PASSKEY_REGISTER_FAILED", HttpStatusCode.BadRequest),
    PASSKEY_NOT_FOUND("PASSKEY_NOT_FOUND", HttpStatusCode.NotFound),
    PASSKEY_LOGIN_FAILED("PASSKEY_LOGIN_FAILED", HttpStatusCode.Unauthorized),

    // ── Generic ─────────────────────────────────────────────────
    VALIDATION_FAILED("VALIDATION_FAILED", HttpStatusCode.BadRequest),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatusCode.InternalServerError),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", HttpStatusCode.PayloadTooLarge),
    NO_FIELDS_TO_UPDATE("NO_FIELDS_TO_UPDATE", HttpStatusCode.BadRequest),

    // ── Service ─────────────────────────────────────────────────
    SERVICE_NOT_FOUND("SERVICE_NOT_FOUND", HttpStatusCode.NotFound),
    SERVICE_NOT_READY("SERVICE_NOT_READY", HttpStatusCode.ServiceUnavailable),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", HttpStatusCode.ServiceUnavailable),
    SERVICE_START_FAILED("SERVICE_START_FAILED", HttpStatusCode.InternalServerError),
    SERVICE_STOP_FAILED("SERVICE_STOP_FAILED", HttpStatusCode.InternalServerError),
    SERVICE_RESTART_FAILED("SERVICE_RESTART_FAILED", HttpStatusCode.InternalServerError),

    // ── Group ───────────────────────────────────────────────────
    GROUP_NOT_FOUND("GROUP_NOT_FOUND", HttpStatusCode.NotFound),
    GROUP_ALREADY_EXISTS("GROUP_ALREADY_EXISTS", HttpStatusCode.Conflict),
    GROUP_HAS_RUNNING_INSTANCES("GROUP_HAS_RUNNING_INSTANCES", HttpStatusCode.Conflict),

    // ── Dedicated ───────────────────────────────────────────────
    DEDICATED_NOT_FOUND("DEDICATED_NOT_FOUND", HttpStatusCode.NotFound),
    DEDICATED_ALREADY_EXISTS("DEDICATED_ALREADY_EXISTS", HttpStatusCode.Conflict),
    DEDICATED_ALREADY_RUNNING("DEDICATED_ALREADY_RUNNING", HttpStatusCode.Conflict),
    DEDICATED_DIRECTORY_NOT_FOUND("DEDICATED_DIRECTORY_NOT_FOUND", HttpStatusCode.NotFound),
    DEDICATED_PORT_IN_USE("DEDICATED_PORT_IN_USE", HttpStatusCode.Conflict),

    // ── Command ─────────────────────────────────────────────────
    COMMAND_NOT_FOUND("COMMAND_NOT_FOUND", HttpStatusCode.NotFound),
    COMMAND_NOT_REMOTE("COMMAND_NOT_REMOTE", HttpStatusCode.Forbidden),
    COMMAND_EXECUTION_FAILED("COMMAND_EXECUTION_FAILED", HttpStatusCode.InternalServerError),

    // ── Stress ──────────────────────────────────────────────────
    STRESS_ALREADY_RUNNING("STRESS_ALREADY_RUNNING", HttpStatusCode.Conflict),
    STRESS_NOT_RUNNING("STRESS_NOT_RUNNING", HttpStatusCode.Conflict),

    // ── Cluster / LB ────────────────────────────────────────────
    CLUSTER_NOT_ENABLED("CLUSTER_NOT_ENABLED", HttpStatusCode.NotFound),
    CLUSTER_TOKEN_MISSING("CLUSTER_TOKEN_MISSING", HttpStatusCode.NotFound),
    LOAD_BALANCER_NOT_ENABLED("LOAD_BALANCER_NOT_ENABLED", HttpStatusCode.NotFound),
    NODE_NOT_FOUND("NODE_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Files ───────────────────────────────────────────────────
    INVALID_SCOPE("INVALID_SCOPE", HttpStatusCode.BadRequest),
    PATH_NOT_FOUND("PATH_NOT_FOUND", HttpStatusCode.NotFound),
    PATH_TRAVERSAL("PATH_TRAVERSAL", HttpStatusCode.BadRequest),

    // ── Proxy ───────────────────────────────────────────────────
    PROXY_NOT_AVAILABLE("PROXY_NOT_AVAILABLE", HttpStatusCode.ServiceUnavailable),

    // ── Template ────────────────────────────────────────────────
    TEMPLATE_NOT_FOUND("TEMPLATE_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Modpack ─────────────────────────────────────────────────
    MODPACK_NOT_FOUND("MODPACK_NOT_FOUND", HttpStatusCode.NotFound),
    MODPACK_INVALID("MODPACK_INVALID", HttpStatusCode.BadRequest),
    MODPACK_UPLOAD_FAILED("MODPACK_UPLOAD_FAILED", HttpStatusCode.InternalServerError),
    CHUNKED_UPLOAD_NOT_FOUND("CHUNKED_UPLOAD_NOT_FOUND", HttpStatusCode.NotFound),
    CHUNKED_UPLOAD_INVALID("CHUNKED_UPLOAD_INVALID", HttpStatusCode.BadRequest),
    CURSEFORGE_API_KEY_MISSING("CURSEFORGE_API_KEY_MISSING", HttpStatusCode.FailedDependency),

    // ── Plugin ──────────────────────────────────────────────────
    PLUGIN_VERSION_NOT_FOUND("PLUGIN_VERSION_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Software ────────────────────────────────────────────────
    SOFTWARE_UNKNOWN("SOFTWARE_UNKNOWN", HttpStatusCode.NotFound),

    // ── Scaling ─────────────────────────────────────────────────
    SCALING_CONFIG_NOT_FOUND("SCALING_CONFIG_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Players ─────────────────────────────────────────────────
    PLAYER_NOT_FOUND("PLAYER_NOT_FOUND", HttpStatusCode.NotFound),
    PLAYER_NOT_ONLINE("PLAYER_NOT_ONLINE", HttpStatusCode.NotFound),

    // ── Perms ───────────────────────────────────────────────────
    PERMISSION_GROUP_NOT_FOUND("PERMISSION_GROUP_NOT_FOUND", HttpStatusCode.NotFound),
    PERMISSION_TRACK_NOT_FOUND("PERMISSION_TRACK_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Display ─────────────────────────────────────────────────
    DISPLAY_CONFIG_NOT_FOUND("DISPLAY_CONFIG_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Punishments ─────────────────────────────────────────────
    PUNISHMENT_NOT_FOUND("PUNISHMENT_NOT_FOUND", HttpStatusCode.NotFound),
    PUNISHMENT_ALREADY_REVOKED("PUNISHMENT_ALREADY_REVOKED", HttpStatusCode.Conflict),
    PUNISHMENT_TARGET_INVALID("PUNISHMENT_TARGET_INVALID", HttpStatusCode.BadRequest),
    PUNISHMENT_DURATION_INVALID("PUNISHMENT_DURATION_INVALID", HttpStatusCode.BadRequest),

    // ── Resource Packs ──────────────────────────────────────────
    RESOURCE_PACK_NOT_FOUND("RESOURCE_PACK_NOT_FOUND", HttpStatusCode.NotFound),
    RESOURCE_PACK_ALREADY_EXISTS("RESOURCE_PACK_ALREADY_EXISTS", HttpStatusCode.Conflict),
    RESOURCE_PACK_INVALID_URL("RESOURCE_PACK_INVALID_URL", HttpStatusCode.BadRequest),
    RESOURCE_PACK_UPLOAD_FAILED("RESOURCE_PACK_UPLOAD_FAILED", HttpStatusCode.InternalServerError),
    RESOURCE_PACK_ASSIGNMENT_NOT_FOUND("RESOURCE_PACK_ASSIGNMENT_NOT_FOUND", HttpStatusCode.NotFound),

    // ── Backup ──────────────────────────────────────────────────
    BACKUP_NOT_FOUND("BACKUP_NOT_FOUND", HttpStatusCode.NotFound),
    BACKUP_ARCHIVE_MISSING("BACKUP_ARCHIVE_MISSING", HttpStatusCode.NotFound),
    BACKUP_MANIFEST_MISSING("BACKUP_MANIFEST_MISSING", HttpStatusCode.NotFound),
    BACKUP_IN_PROGRESS("BACKUP_IN_PROGRESS", HttpStatusCode.Conflict),
    BACKUP_RESTORE_FAILED("BACKUP_RESTORE_FAILED", HttpStatusCode.Conflict),
    BACKUP_VERIFICATION_FAILED("BACKUP_VERIFICATION_FAILED", HttpStatusCode.UnprocessableEntity),
    BACKUP_CONFIG_INVALID("BACKUP_CONFIG_INVALID", HttpStatusCode.BadRequest);

    override fun toString(): String = code
}

/** Create a failed [ApiMessage] with a machine-readable error code. */
fun apiError(message: String, error: ApiError) =
    ApiMessage(success = false, message = message, error = error.code)

/** Respond with [error]'s default HTTP status and a JSON body carrying the error code. */
suspend fun ApplicationCall.fail(error: ApiError, message: String) =
    respond(error.defaultStatus, apiError(message, error))
