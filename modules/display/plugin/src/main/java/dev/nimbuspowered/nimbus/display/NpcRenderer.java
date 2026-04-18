package dev.nimbuspowered.nimbus.display;

import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import dev.nimbuspowered.nimbus.sdk.compat.VersionHelper;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Handles all visual rendering for NPCs.
 * <ul>
 *   <li>All NPC types: FancyNpcs (handles skins, look-at, body rotation, packets, entity types)</li>
 *   <li>Holograms: real invisible ArmorStands</li>
 *   <li>Floating items: real dropped Item entities (natural client-side bobbing + spin)</li>
 * </ul>
 * <p>
 * Cross-version compatible: uses {@link TextCompat} for hologram text,
 * handles PersistentDataContainer availability (1.14+), and supports Folia scheduling.
 */
public class NpcRenderer {

    private static final double HOLOGRAM_LINE_HEIGHT = 0.3;
    private static final double HOLOGRAM_BASE_OFFSET = 0.15;

    private final JavaPlugin plugin;
    private final boolean fancyNpcsAvailable;
    private final boolean hasPersistentData;
    private final ConcurrentHashMap<String, dev.nimbuspowered.nimbus.sdk.NimbusDisplay> displayCache;

    private final SkinCache skinCache;
    private final ConcurrentHashMap<String, NpcState> states = new ConcurrentHashMap<>();

    static class NpcState {
        de.oliver.fancynpcs.api.Npc fancyNpc;
        final List<UUID> hologramEntities = new ArrayList<>();
        UUID floatingItemEntity;
    }

    public NpcRenderer(JavaPlugin plugin, ConcurrentHashMap<String, dev.nimbuspowered.nimbus.sdk.NimbusDisplay> displayCache) {
        this.plugin = plugin;
        this.displayCache = displayCache;
        this.skinCache = new SkinCache(plugin);
        this.fancyNpcsAvailable = Bukkit.getPluginManager().getPlugin("FancyNpcs") != null;
        this.hasPersistentData = classExists("org.bukkit.persistence.PersistentDataContainer");

        if (fancyNpcsAvailable) {
            plugin.getLogger().info("FancyNpcs detected — all NPC types enabled");
        } else {
            plugin.getLogger().warning("FancyNpcs not found — NPCs will be disabled. Install FancyNpcs plugin.");
        }
    }

    public NpcState getState(String npcId) { return states.get(npcId); }
    public boolean isFancyNpcsAvailable() { return fancyNpcsAvailable; }

    // ── Cleanup ───────────────────────────────────────────────────────

    private void cleanupHolograms(NimbusNpc npc) {
        World world = npc.location().getWorld();
        if (world == null) return;
        String npcId = npc.id();
        for (Entity nearby : world.getNearbyEntities(npc.location(), 10, 15, 10)) {
            if (nearby instanceof Player) continue;
            if (isNimbusHologram(nearby, npcId)) nearby.remove();
        }
    }

    // ── Spawn / Despawn ───────────────────────────────────────────────

    public void spawn(NimbusNpc npc) {
        cleanupHolograms(npc);
        NpcState state = new NpcState();
        states.put(npc.id(), state);

        if (fancyNpcsAvailable) {
            spawnFancyNpc(npc, state);
        } else {
            plugin.getLogger().warning("Cannot spawn NPC '" + npc.id() + "': FancyNpcs not installed");
        }

        spawnHolograms(npc, state);

        if (npc.floatingItem() != null) {
            spawnFloatingItem(npc, state);
        }
    }

    public void despawn(NimbusNpc npc) {
        NpcState state = states.remove(npc.id());
        if (state == null) return;

        if (state.fancyNpc != null && fancyNpcsAvailable) {
            try {
                state.fancyNpc.removeForAll();
                de.oliver.fancynpcs.api.FancyNpcsPlugin.get().getNpcManager().removeNpc(state.fancyNpc);
            } catch (Exception e) {
                plugin.getLogger().fine("FancyNpc removal failed: " + e.getMessage());
            }
        }

        cleanupHolograms(npc);
    }

    // ── FancyNpcs (all entity types) ──────────────────────────────────

    private void spawnFancyNpc(NimbusNpc npc, NpcState state) {
        try {
            var fancyPlugin = de.oliver.fancynpcs.api.FancyNpcsPlugin.get();
            var npcData = new de.oliver.fancynpcs.api.NpcData(
                    "nimbus-" + npc.id(),
                    UUID.nameUUIDFromBytes(("NimbusNPC:" + npc.id()).getBytes()),
                    npc.location()
            );

            npcData.setType(npc.entityType());

            if (npc.isFakePlayer() && npc.skin() != null) {
                // Try cached skin texture data first to avoid Mojang API calls
                SkinCache.CachedSkin cached = skinCache.resolve(npc.skin());
                if (cached != null && !cached.textureValue().isEmpty()) {
                    try {
                        var skinData = new de.oliver.fancynpcs.api.skins.SkinData(
                                npc.skin(),
                                de.oliver.fancynpcs.api.skins.SkinData.SkinVariant.AUTO,
                                cached.textureValue(),
                                cached.textureSignature());
                        npcData.setSkinData(skinData);
                    } catch (NoSuchMethodError | NoClassDefFoundError e) {
                        npcData.setSkin(npc.skin());
                    }
                } else {
                    npcData.setSkin(npc.skin());
                }
            }

            npcData.setDisplayName(" ");
            npcData.setTurnToPlayer(npc.lookAtPlayer());
            npcData.setTurnToPlayerDistance(15);
            npcData.setShowInTab(false);
            npcData.setCollidable(false);
            npcData.setVisibilityDistance(200);

            if (npc.equipment() != null && !npc.equipment().isEmpty()) {
                var eq = new java.util.HashMap<de.oliver.fancynpcs.api.utils.NpcEquipmentSlot, ItemStack>();
                for (var entry : npc.equipment().entrySet()) {
                    var slot = parseEquipmentSlot(entry.getKey());
                    Material mat = Material.matchMaterial(entry.getValue());
                    if (slot != null && mat != null) eq.put(slot, new ItemStack(mat));
                }
                if (!eq.isEmpty()) npcData.setEquipment(eq);
            }

            var fancyNpc = fancyPlugin.getNpcAdapter().apply(npcData);
            fancyNpc.setSaveToFile(false);
            fancyNpc.create();
            fancyPlugin.getNpcManager().registerNpc(fancyNpc);

            fancyNpc.spawnForAll();

            // After spawn: apply equipment + burning + force update
            SchedulerCompat.runAtLocationLater(plugin, npc.location(), () -> {
                try {
                    if (npc.equipment() != null && !npc.equipment().isEmpty()) {
                        var eq = new java.util.HashMap<>(fancyNpc.getData().getEquipment());
                        for (var entry : npc.equipment().entrySet()) {
                            var slot = parseEquipmentSlot(entry.getKey());
                            Material mat = Material.matchMaterial(entry.getValue());
                            if (slot != null && mat != null) eq.put(slot, new ItemStack(mat));
                        }
                        fancyNpc.getData().setEquipment(eq);
                    }

                    var attrManager = fancyPlugin.getAttributeManager();
                    if (npc.burning()) {
                        var onFire = attrManager.getAttributeByName(npc.entityType(), "on_fire");
                        if (onFire != null) onFire.apply(fancyNpc, "true");
                    }
                    if (npc.pose() != null) {
                        var poseAttr = attrManager.getAttributeByName(npc.entityType(), "pose");
                        if (poseAttr != null) poseAttr.apply(fancyNpc, npc.pose());
                    }

                    fancyNpc.updateForAll();
                } catch (Exception e) {
                    plugin.getLogger().warning("Post-spawn update failed for NPC " + npc.id() + ": " + e.getMessage());
                }
            }, 10L);

            SchedulerCompat.runAtLocationLater(plugin, npc.location(), () -> {
                try {
                    fancyNpc.updateForAll();
                } catch (Exception ignored) {}
            }, 100L);

            state.fancyNpc = fancyNpc;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn NPC: " + npc.id(), e);
        }
    }

    // ── Holograms (real ArmorStands) ──────────────────────────────────

    private void spawnHolograms(NimbusNpc npc, NpcState state) {
        List<String> lines = npc.hologramLines();
        if (lines == null || lines.isEmpty()) return;
        Location loc = npc.location();
        World world = loc.getWorld();
        if (world == null) return;

        double entityHeight = getEntityHeight(npc.entityType());
        double baseY = loc.getY() + entityHeight + HOLOGRAM_BASE_OFFSET;

        for (int i = 0; i < lines.size(); i++) {
            double y = baseY + (lines.size() - 1 - i) * HOLOGRAM_LINE_HEIGHT;

            Location hologramLoc = new Location(world, loc.getX(), y, loc.getZ());
            ArmorStand stand = world.spawn(hologramLoc, ArmorStand.class, as -> {
                as.setVisible(false);
                as.setSmall(true);
                as.setMarker(true);
                as.setGravity(false);
                as.setCustomNameVisible(true);
                TextCompat.setCustomName(as, "...");
                as.setSilent(true);
                as.setInvulnerable(true);
                as.setCollidable(false);
                // Tag hologram for cleanup — use PersistentDataContainer on 1.14+, metadata fallback on 1.8
                tagHologram(as, npc.id());
            });
            state.hologramEntities.add(stand.getUniqueId());
        }
    }

    public void updateHolograms(NimbusNpc npc, String[] lines) {
        NpcState state = states.get(npc.id());
        if (state == null) return;
        World world = npc.location().getWorld();
        if (world == null) return;

        for (int i = 0; i < state.hologramEntities.size() && i < lines.length; i++) {
            Entity entity = world.getEntity(state.hologramEntities.get(i));
            if (entity instanceof ArmorStand) {
                TextCompat.setCustomName(entity, lines[i]);
            }
        }
    }

    // ── Floating Item (real dropped Item — client renders bobbing + spin) ──

    private void spawnFloatingItem(NimbusNpc npc, NpcState state) {
        Location loc = npc.location();
        World world = loc.getWorld();
        if (world == null) return;

        double entityHeight = getEntityHeight(npc.entityType());
        int hologramCount = npc.hologramLines() != null ? npc.hologramLines().size() : 0;
        double y = loc.getY() + entityHeight + HOLOGRAM_BASE_OFFSET
                + hologramCount * HOLOGRAM_LINE_HEIGHT + 0.3;

        String floatingVal = npc.floatingItem();
        Material mat;
        if ("true".equalsIgnoreCase(floatingVal)) {
            String groupName = npc.serviceTarget() ? npc.target().replaceAll("-\\d+$", "") : npc.target();
            var display = displayCache.get(groupName);
            String materialName = display != null ? display.getNpcFloatingItem() : "GRASS_BLOCK";
            mat = Material.matchMaterial(materialName);
        } else {
            mat = Material.matchMaterial(floatingVal);
        }
        if (mat == null) mat = Material.GRASS_BLOCK;

        Location itemLoc = new Location(world, loc.getX(), y, loc.getZ());
        Item item = world.dropItem(itemLoc, new ItemStack(mat), dropped -> {
            dropped.setGravity(false);
            dropped.setPickupDelay(Integer.MAX_VALUE);
            // setUnlimitedLifetime/setCanMobPickup are Paper 1.19+ — use safely
            try { dropped.setUnlimitedLifetime(true); } catch (NoSuchMethodError ignored) {}
            try { dropped.setCanMobPickup(false); } catch (NoSuchMethodError ignored) {}
            try { dropped.setCanPlayerPickup(false); } catch (NoSuchMethodError ignored) {}
            dropped.setInvulnerable(true);
            dropped.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            tagHologram(dropped, npc.id() + "-item");
        });
        item.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        state.floatingItemEntity = item.getUniqueId();
    }

    // ── Persistent tagging (cross-version) ───────────────────────────

    private void tagHologram(Entity entity, String tag) {
        if (hasPersistentData) {
            try {
                var key = new NamespacedKey(plugin, "nimbus-hologram");
                entity.getPersistentDataContainer().set(key,
                        org.bukkit.persistence.PersistentDataType.STRING, tag);
            } catch (Exception e) {
                // Fallback: use scoreboard tags
                entity.addScoreboardTag("nimbus-holo-" + tag);
            }
        } else {
            // 1.8–1.13: use scoreboard tags (available since 1.9) or custom name
            try {
                entity.addScoreboardTag("nimbus-holo-" + tag);
            } catch (NoSuchMethodError ignored) {
                // 1.8.x: no scoreboard tags — rely on proximity cleanup
            }
        }
    }

    private boolean isNimbusHologram(Entity entity, String npcId) {
        if (hasPersistentData) {
            try {
                var key = new NamespacedKey(plugin, "nimbus-hologram");
                String tag = entity.getPersistentDataContainer().get(key,
                        org.bukkit.persistence.PersistentDataType.STRING);
                return tag != null && tag.startsWith(npcId);
            } catch (Exception ignored) {}
        }
        // Fallback: check scoreboard tags
        try {
            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith("nimbus-holo-" + npcId)) return true;
            }
        } catch (NoSuchMethodError ignored) {}
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static de.oliver.fancynpcs.api.utils.NpcEquipmentSlot parseEquipmentSlot(String name) {
        return switch (name.toLowerCase()) {
            case "mainhand", "hand" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.MAINHAND;
            case "offhand" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.OFFHAND;
            case "head", "helmet" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.HEAD;
            case "chest", "chestplate" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.CHEST;
            case "legs", "leggings" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.LEGS;
            case "feet", "boots" -> de.oliver.fancynpcs.api.utils.NpcEquipmentSlot.FEET;
            default -> null;
        };
    }

    private static double getEntityHeight(EntityType type) {
        return switch (type) {
            case PLAYER -> 1.8;
            case VILLAGER, WANDERING_TRADER -> 1.95;
            case ZOMBIE, SKELETON, PILLAGER -> 1.95;
            case ARMOR_STAND -> 1.975;
            case IRON_GOLEM -> 2.7;
            case ENDERMAN -> 2.9;
            default -> 1.8;
        };
    }

    private static boolean classExists(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }
}
