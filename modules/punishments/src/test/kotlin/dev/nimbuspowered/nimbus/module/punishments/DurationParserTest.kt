package dev.nimbuspowered.nimbus.module.punishments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class DurationParserTest {

    @Test
    fun `permanent keywords return null`() {
        assertNull(DurationParser.parse("perm"))
        assertNull(DurationParser.parse("permanent"))
        assertNull(DurationParser.parse("never"))
        assertNull(DurationParser.parse("0"))
        assertNull(DurationParser.parse("PERM"))
    }

    @Test
    fun `single-unit suffixes parse correctly`() {
        assertEquals(Duration.ofSeconds(30), DurationParser.parse("30s"))
        assertEquals(Duration.ofMinutes(15), DurationParser.parse("15m"))
        assertEquals(Duration.ofHours(2), DurationParser.parse("2h"))
        assertEquals(Duration.ofDays(7), DurationParser.parse("7d"))
        assertEquals(Duration.ofDays(14), DurationParser.parse("2w"))
        assertEquals(Duration.ofDays(60), DurationParser.parse("2mo"))
        assertEquals(Duration.ofDays(365), DurationParser.parse("1y"))
    }

    @Test
    fun `combined durations sum correctly`() {
        assertEquals(Duration.ofHours(36), DurationParser.parse("1d12h"))
        assertEquals(Duration.ofMinutes(150), DurationParser.parse("2h30m"))
    }

    @Test
    fun `empty input throws`() {
        assertThrows(IllegalArgumentException::class.java) { DurationParser.parse("") }
        assertThrows(IllegalArgumentException::class.java) { DurationParser.parse("   ") }
    }

    @Test
    fun `unparseable input throws`() {
        assertThrows(IllegalArgumentException::class.java) { DurationParser.parse("forever") }
        assertThrows(IllegalArgumentException::class.java) { DurationParser.parse("xyz") }
    }

    @Test
    fun `format returns permanent for null`() {
        assertEquals("permanent", DurationParser.format(null))
    }

    @Test
    fun `format combines days hours minutes`() {
        assertEquals("1d 2h 30m", DurationParser.format(Duration.ofDays(1).plusHours(2).plusMinutes(30)))
        assertEquals("30m", DurationParser.format(Duration.ofMinutes(30)))
        assertEquals("45s", DurationParser.format(Duration.ofSeconds(45)))
    }
}
