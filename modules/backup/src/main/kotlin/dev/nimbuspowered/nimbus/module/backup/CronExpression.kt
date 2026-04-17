package dev.nimbuspowered.nimbus.module.backup

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Minimal 5-field cron evaluator: `minute hour day-of-month month day-of-week`.
 *
 * Supported syntax per field:
 *   - Wildcard `*`
 *   - Single number `5`
 *   - Range `1-5`
 *   - List `1,3,5`
 *   - Step `* / 5` or `0-30/5`
 *
 * Day-of-week: 0 or 7 = Sunday, 1 = Monday, ..., 6 = Saturday. We map Sunday=7 to 0.
 *
 * No seconds field, no @reboot/@daily aliases — if you need them, extend here.
 */
class CronExpression(private val expression: String) {

    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val daysOfMonth: Set<Int>
    private val months: Set<Int>
    private val daysOfWeek: Set<Int>  // 0..6, Monday..Sunday? We use 0=Sunday like POSIX.

    init {
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron expression must have 5 fields, got ${parts.size}: '$expression'" }
        minutes = parseField(parts[0], 0, 59)
        hours = parseField(parts[1], 0, 23)
        daysOfMonth = parseField(parts[2], 1, 31)
        months = parseField(parts[3], 1, 12)
        // Normalize 7 → 0 for Sunday
        daysOfWeek = parseField(parts[4], 0, 7).map { if (it == 7) 0 else it }.toSet()
    }

    /** Returns true if the given LocalDateTime (minute-resolution) matches this cron. */
    fun matches(now: LocalDateTime): Boolean {
        val minute = now.minute
        val hour = now.hour
        val dom = now.dayOfMonth
        val month = now.monthValue
        // DayOfWeek.MONDAY=1..SUNDAY=7; POSIX cron has Sunday=0. Normalize:
        val posixDow = now.dayOfWeek.value % 7
        return minute in minutes && hour in hours && month in months &&
                dom in daysOfMonth && posixDow in daysOfWeek
    }

    /**
     * Forward scan from `from` up to `limitMinutes` minutes and return the next matching
     * minute, or null if none found in the window. Used purely for observability
     * (`backup schedule list`).
     */
    fun nextAfter(from: LocalDateTime, limitMinutes: Long = 60L * 24 * 31): LocalDateTime? {
        var cur = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val end = cur.plusMinutes(limitMinutes)
        while (cur.isBefore(end)) {
            if (matches(cur)) return cur
            cur = cur.plusMinutes(1)
        }
        return null
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int> {
        val result = mutableSetOf<Int>()
        for (token in field.split(",")) {
            val (rangePart, stepStr) = if (token.contains("/"))
                token.split("/", limit = 2).let { it[0] to it[1] }
            else token to null
            val step = stepStr?.toIntOrNull() ?: 1

            val (lo, hi) = when {
                rangePart == "*" -> min to max
                rangePart.contains("-") -> {
                    val parts = rangePart.split("-", limit = 2)
                    parts[0].toInt() to parts[1].toInt()
                }
                else -> {
                    val v = rangePart.toInt()
                    if (stepStr != null) v to max else v to v
                }
            }
            // Fail loudly on out-of-range fields — silently producing an
            // empty set means the schedule never fires, with no error to
            // point the operator at the bad expression.
            require(lo in min..max) {
                "Cron field value $lo out of range [$min, $max] in token '$token'"
            }
            require(hi in min..max) {
                "Cron field value $hi out of range [$min, $max] in token '$token'"
            }
            require(lo <= hi) {
                "Cron range start $lo must be <= end $hi in token '$token'"
            }
            require(step >= 1) {
                "Cron step must be >= 1 in token '$token'"
            }
            var i = lo
            while (i <= hi) {
                result.add(i)
                i += step
            }
        }
        return result
    }

    override fun toString(): String = expression
}
