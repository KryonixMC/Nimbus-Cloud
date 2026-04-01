package dev.kryonix.nimbus.sdk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks player counts per group in real-time.
 * <p>
 * Ideal for updating signs, NPCs, scoreboards, or holograms with live player counts.
 * Polls the API at a configurable interval and fires callbacks when counts change.
 *
 * <pre>{@code
 * PlayerTracker tracker = new PlayerTracker(client, 2); // poll every 2 seconds
 * tracker.start();
 *
 * // React to player count changes per group
 * tracker.onPlayerCountChange("BedWars", (group, count) -> {
 *     updateHologram(bedwarsNpc, count + " playing");
 * });
 *
 * // React to total player count changes
 * tracker.onTotalPlayersChange(total -> {
 *     updateScoreboard("Online: " + total);
 * });
 *
 * // Get current counts without waiting for callback
 * int bedwarsPlayers = tracker.getPlayerCount("BedWars");
 * int total = tracker.getTotalPlayers();
 * }</pre>
 */
public class PlayerTracker implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(PlayerTracker.class.getName());

    private final NimbusClient client;
    private final long pollIntervalSeconds;
    private final ConcurrentHashMap<String, Integer> groupPlayerCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> servicePlayerCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<BiConsumer<String, Integer>>> groupListeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<java.util.function.IntConsumer> totalListeners = new CopyOnWriteArrayList<>();
    private volatile int totalPlayers = 0;
    private ScheduledExecutorService scheduler;

    /**
     * @param client              Nimbus API client
     * @param pollIntervalSeconds how often to poll (default: 3)
     */
    public PlayerTracker(NimbusClient client, long pollIntervalSeconds) {
        this.client = client;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public PlayerTracker(NimbusClient client) {
        this(client, 3);
    }

    /**
     * Start tracking player counts.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nimbus-player-tracker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────

    /**
     * Called when the player count for a group changes.
     *
     * @param groupName group to watch
     * @param listener  receives (groupName, newCount)
     */
    public void onPlayerCountChange(String groupName, BiConsumer<String, Integer> listener) {
        groupListeners.computeIfAbsent(groupName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Called when the total player count changes.
     *
     * @param listener receives the new total count
     */
    public void onTotalPlayersChange(java.util.function.IntConsumer listener) {
        totalListeners.add(listener);
    }

    // ── Getters ───────────────────────────────────────────────────────

    /** Current player count for a group. */
    public int getPlayerCount(String groupName) {
        return groupPlayerCounts.getOrDefault(groupName, 0);
    }

    /** Current player count for a specific service. */
    public int getServicePlayerCount(String serviceName) {
        return servicePlayerCounts.getOrDefault(serviceName, 0);
    }

    /** Total player count across all services. */
    public int getTotalPlayers() {
        return totalPlayers;
    }

    /** All group names with their player counts. */
    public Map<String, Integer> getAllGroupCounts() {
        return Map.copyOf(groupPlayerCounts);
    }

    // ── Polling ───────────────────────────────────────────────────────

    private void poll() {
        try {
            client.getServices().thenAccept(services -> {
                ConcurrentHashMap<String, Integer> newGroupCounts = new ConcurrentHashMap<>();
                int newTotal = 0;

                for (NimbusService service : services) {
                    int players = service.getPlayerCount();
                    newTotal += players;
                    servicePlayerCounts.put(service.getName(), players);
                    newGroupCounts.merge(service.getGroupName(), players, Integer::sum);
                }

                // Check for group changes
                for (Map.Entry<String, Integer> entry : newGroupCounts.entrySet()) {
                    String group = entry.getKey();
                    int newCount = entry.getValue();
                    Integer oldCount = groupPlayerCounts.put(group, newCount);

                    if (oldCount == null || oldCount != newCount) {
                        List<BiConsumer<String, Integer>> listeners = groupListeners.get(group);
                        if (listeners != null) {
                            for (BiConsumer<String, Integer> listener : listeners) {
                                try {
                                    listener.accept(group, newCount);
                                } catch (Exception e) {
                                    logger.log(Level.WARNING, "Error in player count listener", e);
                                }
                            }
                        }
                    }
                }

                // Remove groups that no longer have services
                groupPlayerCounts.keySet().removeIf(g -> !newGroupCounts.containsKey(g));

                // Check total change
                if (newTotal != totalPlayers) {
                    totalPlayers = newTotal;
                    for (java.util.function.IntConsumer listener : totalListeners) {
                        try {
                            listener.accept(newTotal);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error in total players listener", e);
                        }
                    }
                }
            }).exceptionally(e -> {
                logger.log(Level.FINE, "Failed to poll player counts", e);
                return null;
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error polling player counts", e);
        }
    }
}
