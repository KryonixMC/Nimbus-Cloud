package dev.nimbuspowered.nimbus.module.anomaly

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stateless statistical functions for anomaly detection.
 * All methods are pure: no side effects, no I/O.
 */
object AnomalyDetector {

    data class ZScoreResult(
        val zscore: Double,
        val mean: Double,
        val stdDev: Double
    )

    /**
     * Compute the Z-score for the latest value in [values].
     * The last element is treated as the observation to score; all elements
     * (including the last) are used to compute the population statistics.
     *
     * Returns null if:
     * - fewer than 3 samples are provided, or
     * - the standard deviation is zero (all values identical).
     */
    fun computeZScore(values: List<Double>): ZScoreResult? {
        if (values.size < 3) return null

        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val stdDev = sqrt(variance)

        if (stdDev == 0.0) return null

        val latest = values.last()
        val zscore = (latest - mean) / stdDev

        return ZScoreResult(zscore = zscore, mean = mean, stdDev = stdDev)
    }

    /**
     * Compute the Z-score of [serviceValue] relative to [peerValues] (values from
     * peer services in the same group, *excluding* the service being evaluated).
     *
     * Returns null if:
     * - fewer than 2 peers are available, or
     * - the peer standard deviation is zero (all peers have identical values).
     */
    fun computePeerZScore(serviceValue: Double, peerValues: List<Double>): ZScoreResult? {
        if (peerValues.size < 2) return null

        val mean = peerValues.average()
        val variance = peerValues.sumOf { (it - mean) * (it - mean) } / peerValues.size
        val stdDev = sqrt(variance)

        if (stdDev == 0.0) return null

        val zscore = (serviceValue - mean) / stdDev

        return ZScoreResult(zscore = zscore, mean = mean, stdDev = stdDev)
    }
}
