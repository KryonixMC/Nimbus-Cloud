package dev.nimbus.signs;

import dev.nimbus.sdk.Nimbus;
import dev.nimbus.sdk.NimbusDisplay;
import dev.nimbus.sdk.NimbusService;
import dev.nimbus.sdk.RoutingStrategy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Manages all Nimbus signs — loads from config, renders them with
 * display configs from the Nimbus API.
 */
public class SignManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final SignConfig config;
    private final CopyOnWriteArrayList<NimbusSign> signs = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache = new ConcurrentHashMap<>();

    public SignManager(JavaPlugin plugin, SignConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void load() {
        signs.clear();
        signs.addAll(config.loadSigns());
        refreshDisplays();
    }

    public void reload() {
        plugin.reloadConfig();
        load();
    }

    /** Fetch display configs from Nimbus API. */
    public void refreshDisplays() {
        try {
            Nimbus.client().getDisplays().thenAccept(displays -> {
                displayCache.clear();
                for (NimbusDisplay d : displays) {
                    displayCache.put(d.getName(), d);
                }
            }).exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Failed to fetch display configs", e);
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("Could not fetch display configs: " + e.getMessage());
        }
    }

    public List<NimbusSign> getSigns() { return List.copyOf(signs); }
    public int getSignCount() { return signs.size(); }

    public NimbusSign getSign(Location location) {
        return signs.stream()
                .filter(s -> s.getLocation().getBlockX() == location.getBlockX() &&
                        s.getLocation().getBlockY() == location.getBlockY() &&
                        s.getLocation().getBlockZ() == location.getBlockZ() &&
                        s.getLocation().getWorld().equals(location.getWorld()))
                .findFirst().orElse(null);
    }

    public void addSign(NimbusSign sign) {
        signs.removeIf(s -> s.getId().equals(sign.getId()));
        signs.add(sign);
        config.addSign(sign);
    }

    public boolean removeSign(Location location) {
        NimbusSign sign = getSign(location);
        if (sign == null) return false;
        signs.remove(sign);
        config.removeSign(sign.getId());
        return true;
    }

    /** Update all signs with live data. */
    public void updateAll() {
        for (NimbusSign nSign : signs) {
            plugin.getServer().getScheduler().runTask(plugin, () -> updateSign(nSign));
        }
    }

    private void updateSign(NimbusSign nSign) {
        Block block = nSign.getLocation().getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        String target = nSign.getTarget();
        // Get the group name (for service targets like "Survival-1" → "Survival")
        String groupName = nSign.isServiceTarget()
                ? target.replaceAll("-\\d+$", "")
                : target;

        NimbusDisplay display = displayCache.get(groupName);

        int players;
        int maxPlayers;
        int servers;
        String rawState;
        boolean available;

        if (nSign.isServiceTarget()) {
            NimbusService service = findService(target);
            if (service != null) {
                players = service.getPlayerCount();
                maxPlayers = 0;
                servers = 1;
                rawState = service.getCustomState() != null ? service.getCustomState() : service.getState();
                available = service.isReady();
            } else {
                players = 0;
                maxPlayers = 0;
                servers = 0;
                rawState = "STOPPED";
                available = false;
            }
        } else {
            List<NimbusService> services = Nimbus.services(target);
            List<NimbusService> routable = Nimbus.routable(target);
            players = services.stream().mapToInt(NimbusService::getPlayerCount).sum();
            maxPlayers = 0;
            servers = services.size();
            available = !routable.isEmpty();
            rawState = available ? "READY" : "STOPPED";
        }

        // Resolve state label from display config
        String state = display != null ? display.resolveState(rawState) : rawState;

        // Try to get max_players from group info in cache
        if (maxPlayers == 0) {
            try {
                var group = Nimbus.cache().getByGroup(groupName);
                // Use per-instance max from group config via display cache
                // For now just count what we have
            } catch (Exception ignored) {}
        }

        // Use display config lines if available, otherwise defaults
        String l1, l2, l3, l4;
        if (display != null) {
            l1 = display.getSignLine1();
            l2 = display.getSignLine2();
            l3 = display.getSignLine3();
            l4 = available ? display.getSignLine4Online() : display.getSignLine4Offline();
        } else {
            l1 = "&1&l★ " + target + " ★";
            l2 = "&8{players} online";
            l3 = "&7{state}";
            l4 = available ? "&2▶ Click to join!" : "&4✖ Offline";
        }

        sign.line(0, render(l1, target, players, maxPlayers, servers, state));
        sign.line(1, render(l2, target, players, maxPlayers, servers, state));
        sign.line(2, render(l3, target, players, maxPlayers, servers, state));
        sign.line(3, render(l4, target, players, maxPlayers, servers, state));
        sign.update();
    }

    private Component render(String template, String name, int players, int maxPlayers,
                              int servers, String state) {
        String text = template
                .replace("{name}", name)
                .replace("{players}", String.valueOf(players))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(servers))
                .replace("{state}", state)
                .replace("{target}", name);
        return LEGACY.deserialize(text);
    }

    private NimbusService findService(String name) {
        return Nimbus.services().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst().orElse(null);
    }
}
