package dev.nimbus.display;

import dev.nimbus.sdk.RoutingStrategy;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads and saves NPC definitions from config.yml under the "npcs" section.
 */
public class NpcConfig {

    private final JavaPlugin plugin;
    private final Logger logger;

    public NpcConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public List<NimbusNpc> loadNpcs() {
        List<NimbusNpc> npcs = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("npcs");
        if (section == null) return npcs;

        for (String key : section.getKeys(false)) {
            ConfigurationSection npcSection = section.getConfigurationSection(key);
            if (npcSection == null) continue;

            try {
                NimbusNpc npc = parseNpc(key, npcSection);
                if (npc != null) npcs.add(npc);
            } catch (Exception e) {
                logger.warning("Failed to load NPC '" + key + "': " + e.getMessage());
            }
        }

        return npcs;
    }

    private NimbusNpc parseNpc(String id, ConfigurationSection section) {
        String target = section.getString("target");
        if (target == null || target.isBlank()) {
            logger.warning("NPC '" + id + "' has no target defined");
            return null;
        }

        ConfigurationSection locSection = section.getConfigurationSection("location");
        if (locSection == null) {
            logger.warning("NPC '" + id + "' has no location defined");
            return null;
        }

        String worldName = locSection.getString("world", "world");
        var world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            logger.warning("NPC '" + id + "': world '" + worldName + "' not loaded");
            return null;
        }

        Location location = new Location(world,
                locSection.getDouble("x"), locSection.getDouble("y"), locSection.getDouble("z"),
                (float) locSection.getDouble("yaw", 0.0),
                (float) locSection.getDouble("pitch", 0.0));

        RoutingStrategy strategy = switch (section.getString("strategy", "least").toLowerCase()) {
            case "fill", "fill_first" -> RoutingStrategy.FILL_FIRST;
            case "random" -> RoutingStrategy.RANDOM;
            default -> RoutingStrategy.LEAST_PLAYERS;
        };

        EntityType entityType;
        String typeStr = section.getString("entity_type", "VILLAGER").toUpperCase();
        if (typeStr.equals("PLAYER")) {
            entityType = EntityType.PLAYER;
        } else {
            try {
                entityType = EntityType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                entityType = EntityType.VILLAGER;
            }
        }

        String skin = section.getString("skin", null);
        boolean lookAtPlayer = section.getBoolean("look_at_player", true);

        NpcAction leftClick;
        try { leftClick = NpcAction.valueOf(section.getString("left_click", "CONNECT").toUpperCase()); }
        catch (IllegalArgumentException e) { leftClick = NpcAction.CONNECT; }

        NpcAction rightClick;
        try { rightClick = NpcAction.valueOf(section.getString("right_click", "INVENTORY").toUpperCase()); }
        catch (IllegalArgumentException e) { rightClick = NpcAction.INVENTORY; }

        String leftClickValue = section.getString("left_click_value", null);
        String rightClickValue = section.getString("right_click_value", null);

        List<String> hologramLines = section.getStringList("hologram");
        String floatingItem = section.getString("floating_item", null);
        if ("false".equalsIgnoreCase(floatingItem)) floatingItem = null;

        // Equipment slots
        var equipment = new java.util.HashMap<String, String>();
        ConfigurationSection eqSection = section.getConfigurationSection("equipment");
        if (eqSection != null) {
            for (String slot : eqSection.getKeys(false)) {
                equipment.put(slot.toLowerCase(), eqSection.getString(slot));
            }
        }
        // Legacy: migrate old hand_item to equipment.mainhand
        String legacyHandItem = section.getString("hand_item", null);
        if (legacyHandItem != null && !equipment.containsKey("mainhand")) {
            equipment.put("mainhand", legacyHandItem);
        }

        boolean burning = section.getBoolean("burning", false);
        String pose = section.getString("pose", null);

        boolean isService = target.matches(".*-\\d+$");

        return new NimbusNpc(id, location, target, isService, strategy, entityType,
                skin, lookAtPlayer, leftClick, rightClick, leftClickValue, rightClickValue,
                hologramLines, floatingItem, equipment.isEmpty() ? Map.of() : Map.copyOf(equipment), burning, pose);
    }

    public void addNpc(NimbusNpc npc) {
        String path = "npcs." + npc.id();
        plugin.getConfig().set(path + ".target", npc.target());
        plugin.getConfig().set(path + ".strategy", npc.strategy().name().toLowerCase());
        plugin.getConfig().set(path + ".entity_type", npc.entityType() == EntityType.PLAYER ? "PLAYER" : npc.entityType().name());
        if (npc.skin() != null) plugin.getConfig().set(path + ".skin", npc.skin());
        plugin.getConfig().set(path + ".look_at_player", npc.lookAtPlayer());
        plugin.getConfig().set(path + ".left_click", npc.leftClick().name());
        plugin.getConfig().set(path + ".right_click", npc.rightClick().name());
        if (npc.leftClickValue() != null) plugin.getConfig().set(path + ".left_click_value", npc.leftClickValue());
        if (npc.rightClickValue() != null) plugin.getConfig().set(path + ".right_click_value", npc.rightClickValue());
        if (!npc.hologramLines().isEmpty()) plugin.getConfig().set(path + ".hologram", npc.hologramLines());
        if (npc.floatingItem() != null) plugin.getConfig().set(path + ".floating_item", npc.floatingItem());
        if (npc.equipment() != null && !npc.equipment().isEmpty()) {
            for (var entry : npc.equipment().entrySet()) {
                plugin.getConfig().set(path + ".equipment." + entry.getKey(), entry.getValue());
            }
        }
        plugin.getConfig().set(path + ".burning", npc.burning());
        if (npc.pose() != null) plugin.getConfig().set(path + ".pose", npc.pose());
        plugin.getConfig().set(path + ".location.world", npc.location().getWorld().getName());
        plugin.getConfig().set(path + ".location.x", npc.location().getX());
        plugin.getConfig().set(path + ".location.y", npc.location().getY());
        plugin.getConfig().set(path + ".location.z", npc.location().getZ());
        plugin.getConfig().set(path + ".location.yaw", (double) npc.location().getYaw());
        plugin.getConfig().set(path + ".location.pitch", (double) npc.location().getPitch());
        plugin.saveConfig();
    }

    public void removeNpc(String id) {
        plugin.getConfig().set("npcs." + id, null);
        plugin.saveConfig();
    }

    public void updateNpcActions(NimbusNpc npc) {
        String path = "npcs." + npc.id();
        if (plugin.getConfig().getConfigurationSection(path) == null) return;
        plugin.getConfig().set(path + ".left_click", npc.leftClick().name());
        plugin.getConfig().set(path + ".right_click", npc.rightClick().name());
        if (npc.leftClickValue() != null) plugin.getConfig().set(path + ".left_click_value", npc.leftClickValue());
        if (npc.rightClickValue() != null) plugin.getConfig().set(path + ".right_click_value", npc.rightClickValue());
        plugin.saveConfig();
    }
}
