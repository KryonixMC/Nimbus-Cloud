package dev.kryonix.nimbus.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Utility for smart player routing across Nimbus services.
 *
 * <pre>{@code
 * ServiceRouter router = new ServiceRouter(client);
 *
 * // Find best BedWars server (WAITING, not full, least players)
 * router.findBest("BedWars", RoutingStrategy.LEAST_PLAYERS)
 *     .thenAccept(service -> client.sendPlayer("Steve", service.getName()));
 *
 * // With custom filter
 * router.findBest("BedWars", RoutingStrategy.FILL_FIRST, s -> s.getPlayerCount() < 15)
 *     .thenAccept(service -> ...);
 *
 * // One-liner: find + send
 * router.routePlayer("Steve", "BedWars", RoutingStrategy.LEAST_PLAYERS);
 * }</pre>
 */
public class ServiceRouter {

    private final NimbusClient client;

    public ServiceRouter(NimbusClient client) {
        this.client = client;
    }

    /**
     * Find the best available service in a group.
     * Only considers services that are READY and have no custom state (routable).
     *
     * @param groupName group to search in
     * @param strategy  how to pick among candidates
     * @return the best service, or null if none available
     */
    public CompletableFuture<NimbusService> findBest(String groupName, RoutingStrategy strategy) {
        return findBest(groupName, strategy, s -> true);
    }

    /**
     * Find the best available service with an additional filter.
     *
     * @param groupName group to search in
     * @param strategy  how to pick among candidates
     * @param filter    additional filter (e.g. player count < max)
     * @return the best service, or null if none available
     */
    public CompletableFuture<NimbusService> findBest(String groupName, RoutingStrategy strategy,
                                                      Predicate<NimbusService> filter) {
        return client.getServicesByGroup(groupName).thenApply(services -> {
            List<NimbusService> candidates = services.stream()
                    .filter(NimbusService::isRoutable)
                    .filter(filter)
                    .toList();
            return strategy.select(candidates);
        });
    }

    /**
     * Find the best service across all groups matching a filter.
     *
     * @param strategy how to pick among candidates
     * @param filter   filter for candidate services
     * @return the best service, or null if none available
     */
    public CompletableFuture<NimbusService> findBestGlobal(RoutingStrategy strategy,
                                                            Predicate<NimbusService> filter) {
        return client.getServices().thenApply(services -> {
            List<NimbusService> candidates = services.stream()
                    .filter(NimbusService::isRoutable)
                    .filter(filter)
                    .toList();
            return strategy.select(candidates);
        });
    }

    /**
     * Find the best service and immediately route a player to it.
     *
     * @param playerName player to route
     * @param groupName  target group
     * @param strategy   how to pick the server
     * @return the service the player was sent to, or null if none available
     */
    public CompletableFuture<NimbusService> routePlayer(String playerName, String groupName,
                                                         RoutingStrategy strategy) {
        return routePlayer(playerName, groupName, strategy, s -> true);
    }

    /**
     * Find the best service and immediately route a player to it, with filter.
     *
     * @param playerName player to route
     * @param groupName  target group
     * @param strategy   how to pick the server
     * @param filter     additional filter
     * @return the service the player was sent to, or null if none available
     */
    public CompletableFuture<NimbusService> routePlayer(String playerName, String groupName,
                                                         RoutingStrategy strategy,
                                                         Predicate<NimbusService> filter) {
        return findBest(groupName, strategy, filter).thenCompose(service -> {
            if (service == null) {
                return CompletableFuture.completedFuture(null);
            }
            return client.sendPlayer(playerName, service.getName())
                    .thenApply(v -> service);
        });
    }
}
