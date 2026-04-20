package dev.nimbuspowered.nimbus.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiErrorsTest {

    @Test
    fun `apiError produces a failed ApiMessage with matching code`() {
        val msg = apiError("service missing", ApiErrors.SERVICE_NOT_FOUND)
        assertFalse(msg.success)
        assertEquals("service missing", msg.message)
        assertEquals("SERVICE_NOT_FOUND", msg.error)
    }

    @Test
    fun `error codes are stable strings`() {
        // These are public API — tests act as a change-detector for renames.
        assertEquals("AUTH_FAILED", ApiErrors.AUTH_FAILED)
        assertEquals("UNAUTHORIZED", ApiErrors.UNAUTHORIZED)
        assertEquals("NOT_FOUND", ApiErrors.NOT_FOUND)
        assertEquals("VALIDATION_FAILED", ApiErrors.VALIDATION_FAILED)
        assertEquals("SERVICE_NOT_FOUND", ApiErrors.SERVICE_NOT_FOUND)
        assertEquals("GROUP_NOT_FOUND", ApiErrors.GROUP_NOT_FOUND)
        assertEquals("DEDICATED_ALREADY_RUNNING", ApiErrors.DEDICATED_ALREADY_RUNNING)
        assertEquals("PATH_TRAVERSAL", ApiErrors.PATH_TRAVERSAL)
        assertEquals("PUNISHMENT_NOT_FOUND", ApiErrors.PUNISHMENT_NOT_FOUND)
        assertEquals("RESOURCE_PACK_NOT_FOUND", ApiErrors.RESOURCE_PACK_NOT_FOUND)
    }
}
