package dev.nimbuspowered.nimbus.module.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Lightweight 5-field cron scheduler backed by coroutines.
 *
 * Supported field syntax:
 *  - `*`    any value
 *  - `N`    exact value
 *  - `*\/N` every N (step)
 *  - `N-M`  inclusive range
 *  - `N,M`  comma-separated list (may be combined with ranges)
 *
 * Field order: minute(0-59) hour(0-23) dom(1-31) month(1-12) dow(0-6, 0=Sunday)
 */
class SimpleCronScheduler(private val scope: CoroutineScope) {

    private val logger = LoggerFactory.getLogger(SimpleCronScheduler::class.java)
    private val jobs = mutableListOf<Job>()

    data class CronExpression(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>
    )

    /** Parse a 5-field cron expression string into a [CronExpression]. */
    fun parse(expr: String): CronExpression {
        val fields = expr.trim().split(Regex("\\s+"))
        require(fields.size == 5) { "Cron expression must have 5 fields: '$expr'" }

        return CronExpression(
            minutes = parseField(fields[0], 0, 59),
            hours = parseField(fields[1], 0, 23),
            daysOfMonth = parseField(fields[2], 1, 31),
            months = parseField(fields[3], 1, 12),
            daysOfWeek = parseField(fields[4], 0, 6)
        )
    }

    /** Returns true if the given [time] matches this cron expression. */
    fun CronExpression.matches(time: LocalDateTime): Boolean {
        return time.minute in minutes &&
                time.hour in hours &&
                time.dayOfMonth in daysOfMonth &&
                time.monthValue in months &&
                // LocalDateTime.dayOfWeek: MONDAY=1 … SUNDAY=7; cron: 0=Sunday
                (time.dayOfWeek.value % 7) in daysOfWeek
    }

    /**
     * Schedule a coroutine to run whenever [expression] matches the current minute.
     * Wakes every 60 seconds and checks if the current time matches.
     *
     * @return The launched [Job], which can be cancelled individually.
     */
    fun schedule(expression: String, callback: suspend () -> Unit): Job {
        val cron = try {
            parse(expression)
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid cron expression '{}': {}", expression, e.message)
            return scope.launch { } // no-op job
        }

        val job = scope.launch {
            var lastFiredMinute = -1
            while (isActive) {
                val now = LocalDateTime.now()
                val currentMinute = now.minute + now.hour * 60 + now.dayOfMonth * 1440

                if (cron.matches(now) && lastFiredMinute != currentMinute) {
                    lastFiredMinute = currentMinute
                    try {
                        callback()
                    } catch (e: Exception) {
                        logger.error("Cron callback error for expression '{}'", expression, e)
                    }
                }

                // Sleep until the start of the next minute
                val secondsUntilNextMinute = 60 - LocalDateTime.now().second
                delay(secondsUntilNextMinute * 1000L)
            }
        }

        synchronized(jobs) { jobs.add(job) }
        return job
    }

    /** Cancel all scheduled jobs. */
    fun cancelAll() {
        synchronized(jobs) {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
    }

    // ── Field Parsing ─────────────────────────────────────

    private fun parseField(field: String, min: Int, max: Int): Set<Int> {
        val result = mutableSetOf<Int>()

        for (part in field.split(",")) {
            when {
                part == "*" -> result.addAll(min..max)
                part.startsWith("*/") -> {
                    val step = part.removePrefix("*/").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid step in field: '$part'")
                    result.addAll((min..max).filter { (it - min) % step == 0 })
                }
                part.contains("-") -> {
                    val (lo, hi) = part.split("-").map {
                        it.toIntOrNull() ?: throw IllegalArgumentException("Invalid range in field: '$part'")
                    }
                    result.addAll(lo..hi)
                }
                else -> {
                    val v = part.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid value in field: '$part'")
                    result.add(v)
                }
            }
        }

        return result.filter { it in min..max }.toSet()
    }
}
