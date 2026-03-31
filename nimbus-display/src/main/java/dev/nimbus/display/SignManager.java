package dev.nimbus.display;

import dev.nimbus.sdk.Nimbus;
import dev.nimbus.sdk.NimbusDisplay;
import dev.nimbus.sdk.NimbusGroup;
import dev.nimbus.sdk.NimbusService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages all Nimbus signs — loads from config, renders them with
 * display configs from the Nimbus API.
 */
public class SignManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final SignConfig config;

    // Signs indexed by block position string for O(1) lookup
    private final ConcurrentHashMap<String, NimbusSign> signs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache;
    private final ConcurrentHashMap<String, NimbusGroup> groupCache;

    public SignManager(JavaPlugin plugin, SignConfig config,
                       ConcurrentHashMap<String, NimbusDisplay> displayCache,
                       ConcurrentHashMap<String, NimbusGroup> groupCache) {
        this.plugin = plugin;
        this.config = config;
        this.displayCache = displayCache;
        this.groupCache = groupCache;
    }

    public void load() {
        signs.clear();
        for (NimbusSign sign : config.loadSigns()) {
            signs.put(locKey(sign.location()), sign);
        }
        refreshDisplays();
        refreshGroups();
    }

    public void reload() {
        plugin.reloadConfig();
        load();
    }

    /** Fetch display configs from Nimbus API (atomic swap, no flickering). */
    public void refreshDisplays() {
        try {
            Nimbus.client().getDisplays().thenAccept(displays -> {
                Map<String, NimbusDisplay> fresh = new HashMap<>();
                for (NimbusDisplay d : displays) {
                    fresh.put(d.getName(), d);
                }
                // Atomic swap — no window where cache is empty
                displayCache.clear();
                displayCache.putAll(fresh);
            }).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Failed to fetch display configs", e);
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Could not fetch display configs: " + e.getMessage());
        }
    }

    /** Fetch group configs for maxPlayers. */
    public void refreshGroups() {
        try {
            Nimbus.client().getGroups().thenAccept(groups -> {
                Map<String, NimbusGroup> fresh = new HashMap<>();
                for (NimbusGroup g : groups) {
                    fresh.put(g.getName(), g);
                }
                groupCache.clear();
                groupCache.putAll(fresh);
            }).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Failed to fetch group configs", e);
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Could not fetch group configs: " + e.getMessage());
        }
    }

    public List<NimbusSign> getSigns() { return List.copyOf(signs.values()); }
    public int getSignCount() { return signs.size(); }

    public NimbusSign getSign(Location location) {
        return signs.get(locKey(location));
    }

    /** Check if a display config exists for the given group name. */
    public boolean hasDisplay(String groupName) {
        return displayCache.containsKey(groupName);
    }

    public void addSign(NimbusSign sign) {
        signs.put(locKey(sign.location()), sign);
        config.addSign(sign);
    }

    public boolean removeSign(Location location) {
        NimbusSign sign = signs.remove(locKey(location));
        if (sign == null) return false;
        config.removeSign(sign.id());
        return true;
    }

    /** Update all signs with live data. Runs on the main thread. */
    public void updateAll() {
        Iterator<Map.Entry<String, NimbusSign>> it = signs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, NimbusSign> entry = it.next();
            NimbusSign nSign = entry.getValue();
            Block block = nSign.location().getBlock();

            // Cleanup: sign block was destroyed (explosion, piston, WorldEdit, etc.)
            if (!(block.getState() instanceof Sign sign)) {
                it.remove();
                config.removeSign(nSign.id());
                plugin.getLogger().info("Removed destroyed sign: " + nSign.id());
                continue;
            }

            updateSign(nSign, sign);
        }
    }

    private void updateSign(NimbusSign nSign, Sign sign) {
        String target = nSign.target();
        String groupName = nSign.serviceTarget()
                ? target.replaceAll("-\\d+$", "")
                : target;

        NimbusDisplay display = displayCache.get(groupName);
        if (display == null) {
            // No display config — show fallback
            sign.line(0, LEGACY.deserialize("&c&l✖ " + target));
            sign.line(1, LEGACY.deserialize("&7No display config"));
            sign.line(2, Component.empty());
            sign.line(3, Component.empty());
            sign.update();
            return;
        }

        int players;
        int maxPlayers;
        int servers;
        String rawState;
        boolean available;

        if (nSign.serviceTarget()) {
            NimbusService service = findService(target);
            if (service != null) {
                players = service.getPlayerCount();
                servers = 1;
                rawState = service.getCustomState() != null ? service.getCustomState() : service.getState();
                available = service.isReady();
            } else {
                players = 0;
                servers = 0;
                rawState = "STOPPED";
                available = false;
            }
        } else {
            List<NimbusService> services = Nimbus.services(target);
            List<NimbusService> routable = Nimbus.routable(target);
            players = services.stream().mapToInt(NimbusService::getPlayerCount).sum();
            servers = services.size();
            available = !routable.isEmpty();
            rawState = available ? "READY" : "STOPPED";
        }

        // maxPlayers from group cache
        NimbusGroup group = groupCache.get(groupName);
        maxPlayers = group != null ? group.getMaxPlayers() : 0;

        String state = display.resolveState(rawState);

        String l1 = display.getSignLine1();
        String l2 = display.getSignLine2();
        String l3 = display.getSignLine3();
        String l4 = available ? display.getSignLine4Online() : display.getSignLine4Offline();

        sign.line(0, render(l1, target, players, maxPlayers, servers, state));
        sign.line(1, render(l2, target, players, maxPlayers, servers, state));
        sign.line(2, render(l3, target, players, maxPlayers, servers, state));
        sign.line(3, render(l4, target, players, maxPlayers, servers, state));
        sign.update();
    }

    private Component render(String template, String name, int players, int maxPlayers,
                              int servers, String state) {
        if (template == null) return Component.empty();
        String text = template
                .replace("{name}", name)
                .replace("{target}", name)
                .replace("{players}", String.valueOf(players))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(servers))
                .replace("{state}", state);
        return LEGACY.deserialize(text);
    }

    private NimbusService findService(String name) {
        return Nimbus.cache().get(name);
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
