package dev.kryonix.nimbus.sdk;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Strategies for selecting the best service to route a player to.
 *
 * <pre>{@code
 * NimbusService best = ServiceRouter.findBest(services, RoutingStrategy.LEAST_PLAYERS);
 * NimbusService best = ServiceRouter.findBest(services, RoutingStrategy.FILL_FIRST);
 * NimbusService best = ServiceRouter.findBest(services, RoutingStrategy.RANDOM);
 * }</pre>
 */
public enum RoutingStrategy {

    /**
     * Select the service with the fewest players.
     * Distributes players evenly across servers.
     */
    LEAST_PLAYERS {
        @Override
        public NimbusService select(List<NimbusService> candidates) {
            return candidates.stream()
                    .min(Comparator.comparingInt(NimbusService::getPlayerCount))
                    .orElse(null);
        }
    },

    /**
     * Select the service with the most players (but not full).
     * Fills servers up before opening new ones — better for game feel.
     */
    FILL_FIRST {
        @Override
        public NimbusService select(List<NimbusService> candidates) {
            return candidates.stream()
                    .max(Comparator.comparingInt(NimbusService::getPlayerCount))
                    .orElse(null);
        }
    },

    /**
     * Select a random service.
     */
    RANDOM {
        @Override
        public NimbusService select(List<NimbusService> candidates) {
            if (candidates.isEmpty()) return null;
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
    };

    /**
     * Select a service from the given candidates.
     *
     * @param candidates non-empty list of routable services
     * @return the selected service, or null if candidates is empty
     */
    public abstract NimbusService select(List<NimbusService> candidates);
}
