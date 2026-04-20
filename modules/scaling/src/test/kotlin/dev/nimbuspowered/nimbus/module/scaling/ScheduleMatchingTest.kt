package dev.nimbuspowered.nimbus.module.scaling

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleMatchingTest {

    private val mgr = SmartScalingManager(
        db = mockk(relaxed = true),
        configManager = mockk(relaxed = true),
        groupManager = mockk(relaxed = true),
        registry = mockk(relaxed = true),
        eventBus = mockk(relaxed = true)
    )

    private fun zdt(day: DayOfWeek, time: LocalTime): ZonedDateTime {
        // 2026-04-20 is a Monday
        val monday = LocalDateTime.of(2026, 4, 20, 0, 0)
        val offset = (day.value - DayOfWeek.MONDAY.value + 7) % 7
        return monday.plusDays(offset.toLong()).with(time).atZone(ZoneId.of("UTC"))
    }

    private val eveningPeak = ScheduleRule(
        name = "evening-peak",
        days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        from = LocalTime.of(17, 0),
        to = LocalTime.of(23, 0),
        minInstances = 3
    )

    private val overnight = ScheduleRule(
        name = "overnight",
        days = setOf(DayOfWeek.FRIDAY),
        from = LocalTime.of(22, 0),
        to = LocalTime.of(2, 0),
        minInstances = 5
    )

    @Test
    fun `active rule matches day and time window`() {
        val hit = mgr.findActiveRule(listOf(eveningPeak), zdt(DayOfWeek.MONDAY, LocalTime.of(20, 0)))
        assertEquals("evening-peak", hit?.name)
    }

    @Test
    fun `active rule does not match outside window`() {
        val miss = mgr.findActiveRule(listOf(eveningPeak), zdt(DayOfWeek.MONDAY, LocalTime.of(16, 0)))
        assertNull(miss)
    }

    @Test
    fun `active rule does not match wrong day`() {
        val miss = mgr.findActiveRule(listOf(eveningPeak), zdt(DayOfWeek.WEDNESDAY, LocalTime.of(20, 0)))
        assertNull(miss)
    }

    @Test
    fun `overnight range matches before midnight`() {
        val hit = mgr.findActiveRule(listOf(overnight), zdt(DayOfWeek.FRIDAY, LocalTime.of(23, 0)))
        assertEquals("overnight", hit?.name)
    }

    @Test
    fun `overnight range matches after midnight`() {
        val hit = mgr.findActiveRule(listOf(overnight), zdt(DayOfWeek.FRIDAY, LocalTime.of(1, 0)))
        assertEquals("overnight", hit?.name)
    }

    @Test
    fun `warmup rule fires within lead time before rule start`() {
        val warmup = mgr.findWarmupRule(listOf(eveningPeak), zdt(DayOfWeek.MONDAY, LocalTime.of(16, 55)), 10)
        assertEquals("evening-peak", warmup?.name)
    }

    @Test
    fun `warmup rule does not fire when already active`() {
        val warmup = mgr.findWarmupRule(listOf(eveningPeak), zdt(DayOfWeek.MONDAY, LocalTime.of(18, 0)), 10)
        assertNull(warmup)
    }

    @Test
    fun `warmup rule does not fire outside lead window`() {
        val warmup = mgr.findWarmupRule(listOf(eveningPeak), zdt(DayOfWeek.MONDAY, LocalTime.of(14, 0)), 10)
        assertNull(warmup)
    }

    @Test
    fun `active rule picks higher min instances when overlapping`() {
        val low = ScheduleRule("low", setOf(DayOfWeek.MONDAY), LocalTime.of(10, 0), LocalTime.of(22, 0), 2)
        val high = ScheduleRule("high", setOf(DayOfWeek.MONDAY), LocalTime.of(18, 0), LocalTime.of(21, 0), 6)
        val hit = mgr.findActiveRule(listOf(low, high), zdt(DayOfWeek.MONDAY, LocalTime.of(20, 0)))
        assertEquals("high", hit?.name)
    }
}
