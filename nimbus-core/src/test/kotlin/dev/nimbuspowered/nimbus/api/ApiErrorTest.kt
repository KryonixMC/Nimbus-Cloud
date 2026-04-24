package dev.nimbuspowered.nimbus.api

import io.ktor.http.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiErrorTest {

    @Test
    fun `apiError produces a failed ApiMessage with matching code`() {
        val msg = apiError("service missing", ApiError.SERVICE_NOT_FOUND)
        assertFalse(msg.success)
        assertEquals("service missing", msg.message)
        assertEquals("SERVICE_NOT_FOUND", msg.error)
    }

    @Test
    fun `wire codes are stable strings`() {
        // These values are part of the stable API contract from 1.0 onwards.
        // Do not rename enum entries without a deprecation cycle.
        assertEquals("AUTH_FAILED", ApiError.AUTH_FAILED.code)
        assertEquals("UNAUTHORIZED", ApiError.UNAUTHORIZED.code)
        assertEquals("FORBIDDEN", ApiError.FORBIDDEN.code)
        assertEquals("VALIDATION_FAILED", ApiError.VALIDATION_FAILED.code)
        assertEquals("SERVICE_NOT_FOUND", ApiError.SERVICE_NOT_FOUND.code)
        assertEquals("GROUP_NOT_FOUND", ApiError.GROUP_NOT_FOUND.code)
        assertEquals("DEDICATED_ALREADY_RUNNING", ApiError.DEDICATED_ALREADY_RUNNING.code)
        assertEquals("PATH_TRAVERSAL", ApiError.PATH_TRAVERSAL.code)
        assertEquals("PUNISHMENT_NOT_FOUND", ApiError.PUNISHMENT_NOT_FOUND.code)
        assertEquals("RESOURCE_PACK_NOT_FOUND", ApiError.RESOURCE_PACK_NOT_FOUND.code)
        assertEquals("LOAD_BALANCER_NOT_ENABLED", ApiError.LOAD_BALANCER_NOT_ENABLED.code)
        assertEquals("INTERNAL_ERROR", ApiError.INTERNAL_ERROR.code)
    }

    @Test
    fun `default HTTP status codes are correct`() {
        assertEquals(HttpStatusCode.Unauthorized, ApiError.AUTH_FAILED.defaultStatus)
        assertEquals(HttpStatusCode.Unauthorized, ApiError.UNAUTHORIZED.defaultStatus)
        assertEquals(HttpStatusCode.Forbidden, ApiError.FORBIDDEN.defaultStatus)
        assertEquals(HttpStatusCode.BadRequest, ApiError.VALIDATION_FAILED.defaultStatus)
        assertEquals(HttpStatusCode.NotFound, ApiError.SERVICE_NOT_FOUND.defaultStatus)
        assertEquals(HttpStatusCode.Conflict, ApiError.DEDICATED_ALREADY_RUNNING.defaultStatus)
        assertEquals(HttpStatusCode.InternalServerError, ApiError.INTERNAL_ERROR.defaultStatus)
    }

    @Test
    fun `toString returns the wire code`() {
        assertEquals("SERVICE_NOT_FOUND", ApiError.SERVICE_NOT_FOUND.toString())
        assertEquals("VALIDATION_FAILED", ApiError.VALIDATION_FAILED.toString())
    }

    @Test
    fun `passkey error codes are stable strings`() {
        assertEquals("PASSKEY_DISABLED", ApiError.PASSKEY_DISABLED.code)
        assertEquals("PASSKEY_LIMIT_REACHED", ApiError.PASSKEY_LIMIT_REACHED.code)
        assertEquals("PASSKEY_REGISTER_FAILED", ApiError.PASSKEY_REGISTER_FAILED.code)
        assertEquals("PASSKEY_NOT_FOUND", ApiError.PASSKEY_NOT_FOUND.code)
        assertEquals("PASSKEY_LOGIN_FAILED", ApiError.PASSKEY_LOGIN_FAILED.code)
    }

    @Test
    fun `passkey HTTP status codes are correct`() {
        assertEquals(HttpStatusCode.ServiceUnavailable, ApiError.PASSKEY_DISABLED.defaultStatus)
        assertEquals(HttpStatusCode.Conflict, ApiError.PASSKEY_LIMIT_REACHED.defaultStatus)
        assertEquals(HttpStatusCode.BadRequest, ApiError.PASSKEY_REGISTER_FAILED.defaultStatus)
        assertEquals(HttpStatusCode.NotFound, ApiError.PASSKEY_NOT_FOUND.defaultStatus)
        assertEquals(HttpStatusCode.Unauthorized, ApiError.PASSKEY_LOGIN_FAILED.defaultStatus)
    }
}
