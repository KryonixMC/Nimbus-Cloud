package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UnknownServerVersionExceptionTest {

    @Test
    fun `message lists requested version and software`() {
        val ex = UnknownServerVersionException(
            ServerSoftware.PAPER,
            "1.99.9",
            listOf("1.21.6", "1.21.5", "1.21.4")
        )
        assertTrue(ex.message!!.contains("PAPER"))
        assertTrue(ex.message!!.contains("1.99.9"))
        assertTrue(ex.message!!.contains("1.21.6"))
    }

    @Test
    fun `message truncates known versions past 10 and reports remainder`() {
        val many = (1..25).map { "1.21.$it" }
        val ex = UnknownServerVersionException(ServerSoftware.PURPUR, "bogus", many)
        val msg = ex.message!!
        assertTrue(msg.contains("+15 more"))
        // First ten should be present, #15 past the window should not
        assertTrue(msg.contains("1.21.1"))
        assertFalse(msg.contains("1.21.15"))
    }

    @Test
    fun `message handles empty known-versions list`() {
        val ex = UnknownServerVersionException(ServerSoftware.LEAF, "1.0.0", emptyList())
        assertTrue(ex.message!!.contains("upstream API returned no versions"))
    }

    @Test
    fun `exception carries structured fields for callers`() {
        val ex = UnknownServerVersionException(
            ServerSoftware.PUFFERFISH,
            "1.99",
            listOf("1.21", "1.20")
        )
        assertEquals(ServerSoftware.PUFFERFISH, ex.software)
        assertEquals("1.99", ex.requestedVersion)
        assertEquals(listOf("1.21", "1.20"), ex.knownVersions)
    }
}
