package dev.nimbus.display;

import dev.nimbus.sdk.RoutingStrategy;
import org.bukkit.Location;

/**
 * A configured Nimbus sign that displays live server info.
 * Lines come from the display config — this only stores target and routing info.
 */
public record NimbusSign(
        String id,
        Location location,
        String target,
        boolean serviceTarget,
        RoutingStrategy strategy
) {}
