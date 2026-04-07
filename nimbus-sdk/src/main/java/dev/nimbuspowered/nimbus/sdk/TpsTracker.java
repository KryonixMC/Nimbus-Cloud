package dev.nimbuspowered.nimbus.sdk;

/**
 * Tracks server TPS (ticks per second) by being called once per game tick.
 * <p>
 * Pure Java — no Bukkit/Paper/Folia dependencies. The hosting plugin calls
 * {@link #onTick()} from its scheduler every tick. TPS is computed from
 * elapsed wall-clock time over a sample window.
 *
 * <pre>{@code
 * TpsTracker tracker = new TpsTracker();
 * // In your Bukkit scheduler (runs every tick):
 * Bukkit.getScheduler().runTaskTimer(plugin, tracker::onTick, 1L, 1L);
 * // Read TPS any time:
 * double tps = tracker.getTps();
 * }</pre>
 */
public class TpsTracker {

    private static final int SAMPLE_INTERVAL = 100; // ticks

    private long lastSampleTime;
    private int tickCount;
    private volatile double tps = 20.0;

    /**
     * Call this once per server tick.
     */
    public void onTick() {
        tickCount++;
        if (tickCount >= SAMPLE_INTERVAL) {
            long now = System.nanoTime();
            if (lastSampleTime != 0) {
                double elapsedSeconds = (now - lastSampleTime) / 1_000_000_000.0;
                tps = Math.min(20.0, tickCount / elapsedSeconds);
            }
            lastSampleTime = now;
            tickCount = 0;
        }
    }

    /** Current TPS (capped at 20.0). Defaults to 20.0 until enough samples are collected. */
    public double getTps() {
        return tps;
    }

    /** Used heap memory in MB. */
    public static long getUsedMemoryMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /** Maximum heap memory in MB. */
    public static long getMaxMemoryMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}
