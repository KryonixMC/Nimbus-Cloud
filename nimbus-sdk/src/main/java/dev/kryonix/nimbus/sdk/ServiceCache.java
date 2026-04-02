package dev.kryonix.nimbus.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Reactive cache of all Nimbus services, kept in sync via WebSocket events.
 * <p>
 * Instead of polling the API for every query, the cache fetches the initial
 * state once and then applies real-time updates from the event stream.
 *
 * <pre>{@code
 * ServiceCache cache = new ServiceCache(client);
 * cache.start();
 *
 * // Always current, no HTTP call:
 * List<NimbusService> lobbies = cache.getByGroup("Lobby");
 * int totalPlayers = cache.getTotalPlayers();
 *
 * // React to changes:
 * cache.onChange("BedWars", services -> updateSigns(services));
 *
 * // Smart routing directly from cache (no API call):
 * NimbusService best = cache.findBest("BedWars", RoutingStrategy.LEAST_PLAYERS);
 * }</pre>
 */
public class ServiceCache implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(ServiceCache.class.getName());

    private final NimbusClient client;
    private final ConcurrentHashMap<String, NimbusService> services = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<List<NimbusService>>>> groupListeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<List<NimbusService>>> globalListeners = new CopyOnWriteArrayList<>();
    private NimbusEventStream eventStream;
    private boolean ownsEventStream = false;
    private volatile boolean running = false;

    public ServiceCache(NimbusClient client) {
        this.client = client;
    }

    public ServiceCache(NimbusClient client, NimbusEventStream sharedEventStream) {
        this.client = client;
        this.eventStream = sharedEventStream;
    }

    /**
     * Start the cache: fetch initial state and connect the event stream.
     *
     * @return future that completes when the initial state is loaded
     */
    public CompletableFuture<Void> start() {
        running = true;

        // Create own event stream only if none was provided via constructor
        if (eventStream == null) {
            eventStream = client.createEventStream();
            ownsEventStream = true;
        }

        // Register handlers on the event stream
        eventStream.onEvent("SERVICE_READY", this::handleServiceReady);
        eventStream.onEvent("SERVICE_STOPPED", this::handleServiceRemoved);
        eventStream.onEvent("SERVICE_CRASHED", this::handleServiceRemoved);
        eventStream.onEvent("SERVICE_CUSTOM_STATE_CHANGED", this::handleCustomStateChanged);
        eventStream.onEvent("SERVICE_STARTING", this::handleServiceStarting);

        // Fetch initial state, then connect event stream if we own it
        if (ownsEventStream) {
            return refresh().thenRun(() -> eventStream.connect());
        } else {
            return refresh();
        }
    }

    /**
     * Re-fetch all services from the API (full refresh).
     */
    public CompletableFuture<Void> refresh() {
        return client.getServices().thenAccept(list -> {
            services.clear();
            for (NimbusService service : list) {
                services.put(service.getName(), service);
            }
            notifyAll(null);
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "Failed to refresh service cache", e);
            return null;
        });
    }

    @Override
    public void close() {
        running = false;
        if (ownsEventStream && eventStream != null) {
            eventStream.close();
        }
    }

    // ── Queries (all from cache, no HTTP) ─────────────────────────────

    /** Get all cached services. */
    public List<NimbusService> getAll() {
        return List.copyOf(services.values());
    }

    /** Get a specific service by name. */
    public NimbusService get(String name) {
        return services.get(name);
    }

    /** Get all services in a group. */
    public List<NimbusService> getByGroup(String groupName) {
        return services.values().stream()
                .filter(s -> groupName.equals(s.getGroupName()))
                .toList();
    }

    /** Get all routable services in a group (READY + no custom state). */
    public List<NimbusService> getRoutable(String groupName) {
        return services.values().stream()
                .filter(s -> groupName.equals(s.getGroupName()))
                .filter(NimbusService::isRoutable)
                .toList();
    }

    /** Get total player count across all services. */
    public int getTotalPlayers() {
        return services.values().stream().mapToInt(NimbusService::getPlayerCount).sum();
    }

    /** Get player count for a specific group. */
    public int getPlayerCount(String groupName) {
        return services.values().stream()
                .filter(s -> groupName.equals(s.getGroupName()))
                .mapToInt(NimbusService::getPlayerCount)
                .sum();
    }

    /** Get count of services in a group. */
    public int getServiceCount(String groupName) {
        return (int) services.values().stream()
                .filter(s -> groupName.equals(s.getGroupName()))
                .count();
    }

    /** Get all unique group names. */
    public List<String> getGroupNames() {
        return services.values().stream()
                .map(NimbusService::getGroupName)
                .distinct()
                .toList();
    }

    /**
     * Find the best service from the cache (no API call).
     *
     * @param groupName group to search
     * @param strategy  routing strategy
     * @return best service, or null if none available
     */
    public NimbusService findBest(String groupName, RoutingStrategy strategy) {
        List<NimbusService> candidates = getRoutable(groupName);
        return strategy.select(candidates);
    }

    /** Total number of cached services. */
    public int size() {
        return services.size();
    }

    // ── Change Listeners ──────────────────────────────────────────────

    /**
     * Register a listener for changes to a specific group.
     * Called whenever a service in that group is added, removed, or changed.
     *
     * @param groupName group to watch
     * @param listener  receives the updated list of services in that group
     */
    public void onChange(String groupName, Consumer<List<NimbusService>> listener) {
        groupListeners.computeIfAbsent(groupName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Register a listener for any service change.
     *
     * @param listener receives the full service list on any change
     */
    public void onAnyChange(Consumer<List<NimbusService>> listener) {
        globalListeners.add(listener);
    }

    // ── Event Handlers ────────────────────────────────────────────────

    private void handleServiceReady(NimbusEvent event) {
        String serviceName = event.get("service");
        if (serviceName == null) return;

        // Fetch fresh data for this service
        client.getService(serviceName).thenAccept(service -> {
            services.put(serviceName, service);
            notifyAll(service.getGroupName());
        }).exceptionally(e -> {
            logger.log(Level.FINE, "Failed to fetch service " + serviceName, e);
            return null;
        });
    }

    private void handleServiceStarting(NimbusEvent event) {
        String serviceName = event.get("service");
        String groupName = event.get("group");
        if (serviceName == null) return;

        // Fetch fresh data
        client.getService(serviceName).thenAccept(service -> {
            services.put(serviceName, service);
            notifyAll(groupName);
        }).exceptionally(e -> null);
    }

    private void handleServiceRemoved(NimbusEvent event) {
        String serviceName = event.get("service");
        if (serviceName == null) return;

        NimbusService removed = services.remove(serviceName);
        if (removed != null) {
            notifyAll(removed.getGroupName());
        }
    }

    private void handleCustomStateChanged(NimbusEvent event) {
        String serviceName = event.get("service");
        if (serviceName == null) return;

        // Fetch fresh data to update player count + state
        client.getService(serviceName).thenAccept(service -> {
            services.put(serviceName, service);
            notifyAll(service.getGroupName());
        }).exceptionally(e -> null);
    }

    private void notifyAll(String changedGroup) {
        // Notify group-specific listeners
        if (changedGroup != null) {
            List<Consumer<List<NimbusService>>> listeners = groupListeners.get(changedGroup);
            if (listeners != null) {
                List<NimbusService> groupServices = getByGroup(changedGroup);
                for (Consumer<List<NimbusService>> listener : listeners) {
                    try {
                        listener.accept(groupServices);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in group change listener", e);
                    }
                }
            }
        }

        // Notify global listeners
        List<NimbusService> all = getAll();
        for (Consumer<List<NimbusService>> listener : globalListeners) {
            try {
                listener.accept(all);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in global change listener", e);
            }
        }
    }
}
