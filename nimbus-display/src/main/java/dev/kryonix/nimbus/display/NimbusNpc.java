package dev.kryonix.nimbus.display;

import dev.kryonix.nimbus.sdk.RoutingStrategy;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Map;

/**
 * Configuration data for a Nimbus NPC.
 */
public record NimbusNpc(
        String id,
        Location location,
        String target,
        boolean serviceTarget,
        RoutingStrategy strategy,
        EntityType entityType,
        String skin,
        boolean lookAtPlayer,
        NpcAction leftClick,
        NpcAction rightClick,
        String leftClickValue,
        String rightClickValue,
        List<String> hologramLines,
        String floatingItem,
        Map<String, String> equipment, // slot → material: mainhand, offhand, head, chest, legs, feet
        boolean burning,
        String pose
) {
    public boolean isFakePlayer() {
        return entityType == EntityType.PLAYER;
    }

    public String getEquipment(String slot) {
        return equipment != null ? equipment.get(slot.toLowerCase()) : null;
    }
}
