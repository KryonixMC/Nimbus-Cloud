package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingStrategyTest {

    private NimbusService svc(String name, int players) {
        return new NimbusService(name, "G", 30000, "READY", null, 1L, players, "t", 0, "0s");
    }

    @Test
    void leastPlayersPicksMinimum() {
        NimbusService a = svc("A", 5);
        NimbusService b = svc("B", 2);
        NimbusService c = svc("C", 7);
        assertSame(b, RoutingStrategy.LEAST_PLAYERS.select(List.of(a, b, c)));
    }

    @Test
    void fillFirstPicksMaximum() {
        NimbusService a = svc("A", 5);
        NimbusService b = svc("B", 2);
        NimbusService c = svc("C", 7);
        assertSame(c, RoutingStrategy.FILL_FIRST.select(List.of(a, b, c)));
    }

    @Test
    void randomReturnsOneOfTheCandidates() {
        NimbusService a = svc("A", 0);
        NimbusService b = svc("B", 0);
        NimbusService chosen = RoutingStrategy.RANDOM.select(List.of(a, b));
        assertTrue(chosen == a || chosen == b);
    }

    @Test
    void emptyListReturnsNullForAllStrategies() {
        assertNull(RoutingStrategy.LEAST_PLAYERS.select(List.of()));
        assertNull(RoutingStrategy.FILL_FIRST.select(List.of()));
        assertNull(RoutingStrategy.RANDOM.select(List.of()));
    }

    @Test
    void singleCandidateAlwaysWins() {
        NimbusService only = svc("only", 9);
        assertSame(only, RoutingStrategy.LEAST_PLAYERS.select(List.of(only)));
        assertSame(only, RoutingStrategy.FILL_FIRST.select(List.of(only)));
        assertSame(only, RoutingStrategy.RANDOM.select(List.of(only)));
    }
}
