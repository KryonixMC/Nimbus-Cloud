package dev.nimbuspowered.nimbus.sdk;

import java.util.concurrent.CompletableFuture;

/**
 * Convenience wrapper for plugins running on a Nimbus-managed server.
 * <p>
 * Automatically discovers its own service identity via JVM system properties
 * that Nimbus Core injects at startup ({@code -Dnimbus.service.name}, etc.).
 *
 * <pre>{@code
 * // In your Paper plugin onEnable():
 * NimbusSelfService self = NimbusSelfService.fromSystemProperties();
 * self.setCustomState("WAITING");
 *
 * // When game starts:
 * self.setCustomState("INGAME");
 *
 * // When game ends and server is ready for new players:
 * self.clearCustomState();
 * }</pre>
 */
public class NimbusSelfService {

    private final NimbusClient client;
    private final String serviceName;
    private final String groupName;
    private final int port;

    public NimbusSelfService(NimbusClient client, String serviceName, String groupName, int port) {
        this.client = client;
        this.serviceName = serviceName;
        this.groupName = groupName;
        this.port = port;
    }

    /**
     * Create a NimbusSelfService from JVM system properties injected by Nimbus Core.
     *
     * @throws IllegalStateException if not running in a Nimbus-managed service
     */
    public static NimbusSelfService fromSystemProperties() {
        String name = System.getProperty("nimbus.service.name");
        String group = System.getProperty("nimbus.service.group");
        String portStr = System.getProperty("nimbus.service.port", "0");
        String apiUrl = System.getProperty("nimbus.api.url", "http://127.0.0.1:8080");
        // Token: prefer env var (hidden from ps), fall back to system property for backwards compat
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        String token = (envToken != null && !envToken.isEmpty())
                ? envToken
                : System.getProperty("nimbus.api.token", "");

        if (name == null) {
            throw new IllegalStateException(
                    "Not running in a Nimbus-managed service. " +
                    "System property 'nimbus.service.name' is not set."
            );
        }

        NimbusClient client = new NimbusClient(apiUrl, token);
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            port = 0;
        }
        return new NimbusSelfService(client, name, group, port);
    }

    /**
     * Check if this JVM is running inside a Nimbus-managed service.
     */
    public static boolean isNimbusManaged() {
        return System.getProperty("nimbus.service.name") != null;
    }

    /** The underlying NimbusClient for making additional API calls. */
    public NimbusClient getClient() { return client; }

    /** This service's name (e.g. "BedWars-1"). */
    public String getServiceName() { return serviceName; }

    /** This service's group name (e.g. "BedWars"). */
    public String getGroupName() { return groupName; }

    /** This service's port. */
    public int getPort() { return port; }

    // ── Custom State ──────────────────────────────────────────────────

    /**
     * Set a custom state for this service.
     * Common values: "WAITING", "INGAME", "ENDING"
     * <p>
     * Services with a custom state are excluded from auto-scaling capacity
     * calculations and won't be chosen as targets for new player routing.
     */
    public CompletableFuture<Void> setCustomState(String state) {
        return client.setCustomState(serviceName, state);
    }

    /**
     * Clear the custom state (server becomes routable again).
     */
    public CompletableFuture<Void> clearCustomState() {
        return client.clearCustomState(serviceName);
    }

    /**
     * Get the current custom state.
     */
    public CompletableFuture<String> getCustomState() {
        return client.getCustomState(serviceName);
    }

    // ── Player Count ──────────────────────────────────────────────────

    /**
     * Report the current player count to the controller.
     * Called automatically by the SDK plugin on player join/quit.
     */
    public CompletableFuture<Void> reportPlayerCount(int playerCount) {
        return client.reportPlayerCount(serviceName, playerCount);
    }

    // ── Health Reporting ──────────────────────────────────────────────

    /**
     * Report TPS and memory usage to the controller.
     * Called automatically by the SDK at regular intervals.
     */
    public CompletableFuture<Void> reportHealth(double tps, long memoryUsedMb, long memoryMaxMb) {
        return client.reportHealth(serviceName, tps, memoryUsedMb, memoryMaxMb);
    }

    // ── Info ──────────────────────────────────────────────────────────

    /**
     * Get full service info from the API.
     */
    public CompletableFuture<NimbusService> getInfo() {
        return client.getService(serviceName);
    }

    /**
     * Execute a command on this service's console.
     */
    public CompletableFuture<Void> executeCommand(String command) {
        return client.executeCommand(serviceName, command);
    }

    /**
     * Create a WebSocket event stream for listening to cloud events.
     */
    public NimbusEventStream createEventStream() {
        return client.createEventStream();
    }

    // ── Messaging ─────────────────────────────────────────────────────

    /**
     * Send a message to another service.
     *
     * @param targetService the service to send to (e.g. "Lobby-1")
     * @param channel       message channel (e.g. "game_ended")
     * @param data          message payload
     */
    public CompletableFuture<Void> sendMessage(String targetService, String channel,
                                                java.util.Map<String, String> data) {
        return client.sendMessage(targetService, serviceName, channel, data);
    }

    /**
     * Send a message to another service (no payload).
     */
    public CompletableFuture<Void> sendMessage(String targetService, String channel) {
        return sendMessage(targetService, channel, java.util.Map.of());
    }

    // ── Routing ───────────────────────────────────────────────────────

    /**
     * Create a {@link ServiceRouter} for smart player routing.
     */
    public ServiceRouter createRouter() {
        return new ServiceRouter(client);
    }

    /**
     * Create a {@link ServiceCache} for reactive service tracking.
     */
    public ServiceCache createCache() {
        return new ServiceCache(client);
    }

    /**
     * Create a {@link PlayerTracker} for real-time player count tracking.
     */
    public PlayerTracker createPlayerTracker() {
        return new PlayerTracker(client);
    }

    /**
     * Create a {@link PlayerTracker} with a custom poll interval.
     */
    public PlayerTracker createPlayerTracker(long pollIntervalSeconds) {
        return new PlayerTracker(client, pollIntervalSeconds);
    }
}
