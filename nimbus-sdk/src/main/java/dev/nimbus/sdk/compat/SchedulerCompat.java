package dev.nimbus.sdk.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Scheduler abstraction that works on both Bukkit and Folia.
 * <p>
 * On standard Bukkit/Paper: delegates to {@code Bukkit.getScheduler()}.
 * On Folia: delegates to the global region scheduler (sync) or async scheduler.
 * <p>
 * Usage: replace {@code Bukkit.getScheduler().runTask(...)} with
 * {@code SchedulerCompat.runTask(...)}.
 */
public final class SchedulerCompat {

    private SchedulerCompat() {}

    // ── Sync (main thread / global region) ───────────────────────────

    public static void runTask(Plugin plugin, Runnable task) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTask(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTaskLater(plugin, task, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public static void runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTaskTimer(plugin, task, delay, period);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
    }

    // ── Async ────────────────────────────────────────────────────────

    public static void runTaskAsync(Plugin plugin, Runnable task) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTaskAsync(plugin, task);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public static void runTaskLaterAsync(Plugin plugin, Runnable task, long delayTicks) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTaskLaterAsync(plugin, task, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    public static void runTaskTimerAsync(Plugin plugin, Runnable task, long delay, long period) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runTaskTimerAsync(plugin, task, delay, period);
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
    }

    // ── Entity-bound (Folia region-aware) ────────────────────────────

    /** Run a task on the entity's owning region thread (Folia) or main thread (Bukkit). */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runForEntity(plugin, entity, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Run a delayed task on the entity's owning region thread (Folia) or main thread (Bukkit). */
    public static void runForEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (VersionHelper.isFolia()) {
            FoliaScheduler.runForEntityLater(plugin, entity, task, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }
}
