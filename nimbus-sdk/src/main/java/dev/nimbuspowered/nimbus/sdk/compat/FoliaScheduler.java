package dev.nimbuspowered.nimbus.sdk.compat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Folia-specific scheduler implementations.
 * This class is ONLY loaded on Folia servers — never reference it directly,
 * always go through {@link SchedulerCompat}.
 */
class FoliaScheduler {

    static void runTask(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    static void runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (delayTicks <= 0) delayTicks = 1; // Folia requires delay >= 1
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
    }

    static void runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (delay <= 0) delay = 1;
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period);
    }

    static void runTaskAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    static void runTaskLaterAsync(Plugin plugin, Runnable task, long delayTicks) {
        long delayMs = Math.max(delayTicks * 50, 1);
        Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs, TimeUnit.MILLISECONDS);
    }

    static void runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        long delayMs = Math.max(delay * 50, 1);
        long periodMs = Math.max(period * 50, 1);
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    static void runForEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (delayTicks <= 0) delayTicks = 1;
        entity.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks);
    }

    // ── Location/Region-bound ────────────────────────────────────────

    static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
    }

    static void runAtLocationLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        if (delayTicks <= 0) delayTicks = 1;
        Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> task.run(), delayTicks);
    }
}
