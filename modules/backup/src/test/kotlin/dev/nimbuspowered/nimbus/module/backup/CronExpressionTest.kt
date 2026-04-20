package dev.nimbuspowered.nimbus.module.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class CronExpressionTest {

    @Test
    fun `daily at 3am matches only at 03-00`() {
        val cron = CronExpression("0 3 * * *")
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 3, 0)))
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 19, 3, 1)))
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 19, 4, 0)))
    }

    @Test
    fun `step expression every 15 minutes`() {
        val cron = CronExpression("*/15 * * * *")
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 0)))
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 15)))
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 30)))
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 45)))
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 7)))
    }

    @Test
    fun `sunday weekly matches Sunday only (POSIX 0 = Sunday)`() {
        val cron = CronExpression("0 0 * * 0")
        // 2026-04-19 is a Sunday
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 0, 0)))
        // 2026-04-20 is Monday
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 20, 0, 0)))
    }

    @Test
    fun `sunday specified as 7 is normalized to 0`() {
        val cron = CronExpression("0 0 * * 7")
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 0, 0)))
    }

    @Test
    fun `list of minutes`() {
        val cron = CronExpression("0,30 * * * *")
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 0)))
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 30)))
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 15)))
    }

    @Test
    fun `range expression`() {
        val cron = CronExpression("0 9-17 * * 1-5")
        // Monday 10:00
        assertTrue(cron.matches(LocalDateTime.of(2026, 4, 20, 10, 0)))
        // Sunday 10:00
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 19, 10, 0)))
        // Monday 08:00 — out of range
        assertFalse(cron.matches(LocalDateTime.of(2026, 4, 20, 8, 0)))
    }

    @Test
    fun `wrong field count throws`() {
        assertThrows<IllegalArgumentException> { CronExpression("0 3 * *") }
        assertThrows<IllegalArgumentException> { CronExpression("0 3 * * * *") }
    }

    @Test
    fun `out-of-range field throws`() {
        assertThrows<IllegalArgumentException> { CronExpression("60 3 * * *") }
        assertThrows<IllegalArgumentException> { CronExpression("0 25 * * *") }
    }

    @Test
    fun `nextAfter finds next match within window`() {
        val cron = CronExpression("0 3 * * *")
        val next = cron.nextAfter(LocalDateTime.of(2026, 4, 19, 4, 0))
        assertNotNull(next)
        assertEquals(LocalDateTime.of(2026, 4, 20, 3, 0), next)
    }
}
