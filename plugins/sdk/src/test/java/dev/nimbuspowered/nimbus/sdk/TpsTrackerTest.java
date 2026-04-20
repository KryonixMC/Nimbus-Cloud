package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TpsTrackerTest {

    @Test
    void defaultsTo20TpsBeforeAnyTicks() {
        TpsTracker t = new TpsTracker();
        assertEquals(20.0, t.getTps());
    }

    @Test
    void stayAt20AfterFirstSampleWindow() {
        TpsTracker t = new TpsTracker();
        // First 100 ticks: lastSampleTime=0 → tps stays at default 20.0, window resets.
        for (int i = 0; i < 100; i++) t.onTick();
        assertEquals(20.0, t.getTps());
    }

    @Test
    void computesTpsAfterTwoSampleWindows() throws InterruptedException {
        TpsTracker t = new TpsTracker();
        for (int i = 0; i < 100; i++) t.onTick(); // primes lastSampleTime
        Thread.sleep(20); // elapsed > 0 for the second window
        for (int i = 0; i < 100; i++) t.onTick();
        // Second window: 100 ticks in ~20ms → computed TPS would be ~5000, capped at 20.0
        assertTrue(t.getTps() > 0);
        assertTrue(t.getTps() <= 20.0);
    }

    @Test
    void memoryReportersReturnSaneValues() {
        long used = TpsTracker.getUsedMemoryMb();
        long max = TpsTracker.getMaxMemoryMb();
        assertTrue(used >= 0);
        assertTrue(max > 0);
        assertTrue(used <= max * 2, "used=" + used + " max=" + max);
    }
}
