package dev.kryonix.nimbus.scaling

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ScalingRuleTest {

    // --- shouldScaleUp ---

    @Test
    fun `shouldScaleUp returns reason when fill rate exceeds threshold`() {
        // 35 players across 1 instance with 40 slots = 87.5% fill, threshold 80%
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 35,
            readyInstances = 1,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("87%"))
        assertTrue(reason.contains("80%"))
    }

    @Test
    fun `shouldScaleUp returns null when fill rate is below threshold`() {
        // 10 players across 1 instance with 40 slots = 25% fill, threshold 80%
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 10,
            readyInstances = 1,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleUp returns null when fill rate equals threshold`() {
        // 32 players across 1 instance with 40 slots = 80% fill, threshold 80%
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 32,
            readyInstances = 1,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleUp returns null when already at maxInstances`() {
        // 35 players across 4 instances = high fill, but already at max
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 35,
            readyInstances = 4,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.2
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleUp returns null when readyInstances is zero`() {
        // 0 ready instances — code guards against division by zero
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 10,
            readyInstances = 0,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleUp with multiple instances calculates combined capacity`() {
        // 70 players across 2 instances with 40 slots each = 87.5%, threshold 80%
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 70,
            readyInstances = 2,
            maxInstances = 5,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNotNull(reason)
    }

    @Test
    fun `shouldScaleUp returns null with zero players`() {
        val reason = ScalingRule.shouldScaleUp(
            totalPlayers = 0,
            readyInstances = 2,
            maxInstances = 4,
            playersPerInstance = 40,
            scaleThreshold = 0.8
        )
        assertNull(reason)
    }

    // --- shouldScaleDown ---

    @Test
    fun `shouldScaleDown returns reason when idle timeout expired`() {
        val idleStart = Instant.now().minusSeconds(120)
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 60,
            serviceIdleSince = idleStart,
            currentInstances = 3,
            minInstances = 1
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("timeout"))
    }

    @Test
    fun `shouldScaleDown returns null when idleTimeout is zero (disabled)`() {
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 0,
            serviceIdleSince = Instant.now().minusSeconds(120),
            currentInstances = 3,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when at minInstances`() {
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 60,
            serviceIdleSince = Instant.now().minusSeconds(120),
            currentInstances = 1,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when service has players`() {
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 5,
            idleTimeout = 60,
            serviceIdleSince = Instant.now().minusSeconds(120),
            currentInstances = 3,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when idleSince is null`() {
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 60,
            serviceIdleSince = null,
            currentInstances = 3,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when idle duration has not exceeded timeout`() {
        // Idle for only 30 seconds, timeout is 60
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 60,
            serviceIdleSince = Instant.now().minusSeconds(30),
            currentInstances = 3,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when negative idleTimeout`() {
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = -1,
            serviceIdleSince = Instant.now().minusSeconds(120),
            currentInstances = 3,
            minInstances = 1
        )
        assertNull(reason)
    }

    @Test
    fun `shouldScaleDown returns null when below minInstances`() {
        // currentInstances (0) < minInstances (1) — should not scale down
        val reason = ScalingRule.shouldScaleDown(
            servicePlayers = 0,
            idleTimeout = 60,
            serviceIdleSince = Instant.now().minusSeconds(120),
            currentInstances = 0,
            minInstances = 1
        )
        assertNull(reason)
    }
}
