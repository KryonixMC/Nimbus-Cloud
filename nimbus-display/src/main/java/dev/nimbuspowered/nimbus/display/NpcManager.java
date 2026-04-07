package dev.nimbuspowered.nimbus.display;

import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.NimbusDisplay;
import dev.nimbuspowered.nimbus.sdk.NimbusGroup;
import dev.nimbuspowered.nimbus.sdk.NimbusService;
import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import dev.nimbuspowered.nimbus.sdk.compat.VersionHelper;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class NpcManager {

    private final JavaPlugin plugin;
    private final NpcConfig config;
    private final NpcRenderer renderer;
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache;
    private final ConcurrentHashMap<String, NimbusGroup> groupCache;
    private final ConcurrentHashMap<String, NimbusNpc> npcs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> hologramFrameIndex = new ConcurrentHashMap<>();

    public NpcManager(JavaPlugin plugin, NpcConfig config,
                      ConcurrentHashMap<String, NimbusDisplay> displayCache,
                      ConcurrentHashMap<String, NimbusGroup> groupCache) {
        this.plugin = plugin;
        this.config = config;
        this.displayCache = displayCache;
        this.groupCache = groupCache;
        this.renderer = new NpcRenderer(plugin, displayCache);
    }

    public void load() {
        npcs.clear();
        for (NimbusNpc npc : config.loadNpcs()) {
            npcs.put(npc.id(), npc);
        }

        if (npcs.isEmpty()) return;

        // All NPCs go through FancyNpcs — delay spawn to let FancyNpcs fully initialize
        plugin.getLogger().fine("Scheduling " + npcs.size() + " NPC(s) for delayed spawn...");
        for (NimbusNpc npc : npcs.values()) {
            // Folia: schedule on each NPC's location region; Bukkit: main thread
            SchedulerCompat.runAtLocationLater(plugin, npc.location(), () -> {
                try {
                    renderer.spawn(npc);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to spawn NPC '" + npc.id() + "'", e);
                }
            }, 200L); // 10 seconds
        }
    }

    public void reload() {
        for (NimbusNpc npc : npcs.values()) {
            try { renderer.despawn(npc); } catch (Exception ignored) {}
        }
        npcs.clear();
        plugin.reloadConfig();

        for (NimbusNpc npc : config.loadNpcs()) {
            npcs.put(npc.id(), npc);
        }
        for (NimbusNpc npc : npcs.values()) {
            SchedulerCompat.runAtLocation(plugin, npc.location(), () -> {
                try { renderer.spawn(npc); } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to spawn NPC '" + npc.id() + "'", e);
                }
            });
        }
    }

    public void despawnAll() {
        for (NimbusNpc npc : npcs.values()) {
            try { renderer.despawn(npc); } catch (Exception ignored) {}
        }
    }

    /** Update hologram text for all NPCs. On Folia, each NPC is updated on its location's region thread. */
    public void updateAll() {
        for (NimbusNpc npc : npcs.values()) {
            if (VersionHelper.isFolia()) {
                SchedulerCompat.runAtLocation(plugin, npc.location(), () -> {
                    try { updateHologram(npc); } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to update NPC '" + npc.id() + "'", e);
                    }
                });
            } else {
                try { updateHologram(npc); } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to update NPC '" + npc.id() + "'", e);
                }
            }
        }
    }

    private void updateHologram(NimbusNpc npc) {
        List<String> templates = npc.hologramLines();
        if (templates == null || templates.isEmpty()) return;

        String target = npc.target();
        String groupName = npc.serviceTarget() ? target.replaceAll("-\\d+$", "") : target;

        NimbusDisplay display = displayCache.get(groupName);
        NimbusGroup group = groupCache.get(groupName);
        int maxPlayers = group != null ? group.getMaxPlayers() : 0;

        int players, servers;
        String rawState;

        if (npc.serviceTarget()) {
            NimbusService service = Nimbus.cache().get(target);
            if (service != null) {
                players = service.getPlayerCount();
                servers = 1;
                rawState = service.getCustomState() != null ? service.getCustomState() : service.getState();
            } else {
                players = 0; servers = 0; rawState = "STOPPED";
            }
        } else {
            List<NimbusService> services = Nimbus.services(target);
            List<NimbusService> routable = Nimbus.routable(target);
            players = services.stream().mapToInt(NimbusService::getPlayerCount).sum();
            servers = services.size();
            rawState = !routable.isEmpty() ? "READY" : "STOPPED";
        }

        String state = display != null ? display.resolveState(rawState) : rawState;

        // Advance frame index for this NPC
        int frameIdx = hologramFrameIndex.merge(npc.id(), 0, (old, v) -> old + 1);

        // Render hologram lines — supports "||" frame separator for animation
        // e.g. "&b&lBedWars||&e&lBedWars" cycles between blue and yellow
        String[] lines = new String[templates.size()];
        for (int i = 0; i < templates.size(); i++) {
            String template = templates.get(i);
            if (template.contains("||")) {
                String[] frames = template.split("\\|\\|");
                template = frames[frameIdx % frames.length];
            }
            lines[i] = render(template, target, players, maxPlayers, servers, state);
        }

        renderer.updateHolograms(npc, lines);
    }

    private String render(String template, String name, int players, int maxPlayers,
                           int servers, String state) {
        if (template == null) return "";
        return template
                .replace("{name}", name)
                .replace("{target}", name)
                .replace("{players}", String.valueOf(players))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(servers))
                .replace("{state}", state);
    }

    // ── Accessors ─────────────────────────────────────────────────────

    public NimbusNpc getNpc(String id) { return npcs.get(id); }
    public List<NimbusNpc> getNpcs() { return List.copyOf(npcs.values()); }
    public int getNpcCount() { return npcs.size(); }
    public boolean hasDisplay(String groupName) { return displayCache.containsKey(groupName); }
    public NpcRenderer getRenderer() { return renderer; }

    public NimbusNpc getNearestNpc(Location loc, double radius) {
        NimbusNpc nearest = null;
        double nearestDist = radius * radius;
        for (NimbusNpc npc : npcs.values()) {
            if (!npc.location().getWorld().equals(loc.getWorld())) continue;
            double dist = npc.location().distanceSquared(loc);
            if (dist < nearestDist) { nearestDist = dist; nearest = npc; }
        }
        return nearest;
    }

    // ── Mutation ──────────────────────────────────────────────────────

    public void addNpc(NimbusNpc npc) {
        npcs.put(npc.id(), npc);
        config.addNpc(npc);
        SchedulerCompat.runAtLocation(plugin, npc.location(), () -> {
            try { renderer.spawn(npc); } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to spawn NPC '" + npc.id() + "'", e);
            }
        });
    }

    public void removeNpc(String id) {
        NimbusNpc npc = npcs.remove(id);
        if (npc != null) {
            SchedulerCompat.runAtLocation(plugin, npc.location(), () -> {
                try { renderer.despawn(npc); } catch (Exception ignored) {}
            });
        }
        config.removeNpc(id);
    }

    /** Update any property on an NPC, respawn it, and save to config. */
    public void updateNpc(String id, NimbusNpc updated) {
        NimbusNpc old = npcs.get(id);
        if (old == null) return;

        SchedulerCompat.runAtLocation(plugin, old.location(), () -> {
            try { renderer.despawn(old); } catch (Exception ignored) {}
        });

        npcs.put(id, updated);
        config.removeNpc(id);
        config.addNpc(updated);

        SchedulerCompat.runAtLocation(plugin, updated.location(), () -> {
            try { renderer.spawn(updated); } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to respawn NPC '" + id + "'", e);
            }
        });
    }
}
