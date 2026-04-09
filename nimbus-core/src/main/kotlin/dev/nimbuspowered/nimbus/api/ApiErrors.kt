package dev.nimbuspowered.nimbus.api

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Machine-readable API error codes returned in the `error` field of [ApiMessage].
 *
 * Clients can switch on these codes instead of parsing human-readable messages.
 */
object ApiErrors {

    // ── Auth ──────────────────────────────────────────────────────
    const val AUTH_FAILED = "AUTH_FAILED"
    const val INSUFFICIENT_SCOPE = "INSUFFICIENT_SCOPE"

    // ── Generic ────────────────────────────────────────────────────
    const val NOT_FOUND = "NOT_FOUND"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val FORBIDDEN = "FORBIDDEN"
    const val READ_ONLY = "READ_ONLY"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"

    // ── Service ────────────────────────────────────────────────────
    const val SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND"
    const val SERVICE_NOT_READY = "SERVICE_NOT_READY"
    const val SERVICE_START_FAILED = "SERVICE_START_FAILED"
    const val SERVICE_STOP_FAILED = "SERVICE_STOP_FAILED"
    const val SERVICE_RESTART_FAILED = "SERVICE_RESTART_FAILED"

    // ── Group ──────────────────────────────────────────────────────
    const val GROUP_NOT_FOUND = "GROUP_NOT_FOUND"
    const val GROUP_ALREADY_EXISTS = "GROUP_ALREADY_EXISTS"
    const val GROUP_HAS_RUNNING_INSTANCES = "GROUP_HAS_RUNNING_INSTANCES"

    // ── Command ────────────────────────────────────────────────────
    const val COMMAND_NOT_FOUND = "COMMAND_NOT_FOUND"
    const val COMMAND_NOT_REMOTE = "COMMAND_NOT_REMOTE"
    const val COMMAND_EXECUTION_FAILED = "COMMAND_EXECUTION_FAILED"

    // ── Stress Test ────────────────────────────────────────────────
    const val STRESS_ALREADY_RUNNING = "STRESS_ALREADY_RUNNING"
    const val STRESS_NOT_RUNNING = "STRESS_NOT_RUNNING"

    // ── Cluster / LB ───────────────────────────────────────────────
    const val CLUSTER_NOT_ENABLED = "CLUSTER_NOT_ENABLED"
    const val LOAD_BALANCER_NOT_ENABLED = "LOAD_BALANCER_NOT_ENABLED"
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"

    // ── Files ──────────────────────────────────────────────────────
    const val INVALID_SCOPE = "INVALID_SCOPE"
    const val PATH_NOT_FOUND = "PATH_NOT_FOUND"
    const val PATH_TRAVERSAL = "PATH_TRAVERSAL"

    // ── Config ─────────────────────────────────────────────────────
    const val NO_FIELDS_TO_UPDATE = "NO_FIELDS_TO_UPDATE"

    // ── Proxy ──────────────────────────────────────────────────────
    const val PROXY_NOT_AVAILABLE = "PROXY_NOT_AVAILABLE"

    // ── Modpack ───────────────────────────────────────────────────
    const val MODPACK_NOT_FOUND = "MODPACK_NOT_FOUND"
    const val MODPACK_INVALID = "MODPACK_INVALID"
    const val MODPACK_UPLOAD_FAILED = "MODPACK_UPLOAD_FAILED"
    const val CHUNKED_UPLOAD_NOT_FOUND = "CHUNKED_UPLOAD_NOT_FOUND"
    const val CHUNKED_UPLOAD_INVALID = "CHUNKED_UPLOAD_INVALID"
    const val CURSEFORGE_API_KEY_MISSING = "CURSEFORGE_API_KEY_MISSING"
}

/** Shortcut to create a failed [ApiMessage] with an error code. */
fun apiError(message: String, error: String) = ApiMessage(success = false, message = message, error = error)
