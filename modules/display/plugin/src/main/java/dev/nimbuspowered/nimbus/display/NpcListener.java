package dev.nimbuspowered.nimbus.display;

import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.NimbusDisplay;
import dev.nimbuspowered.nimbus.sdk.NimbusGroup;
import dev.nimbuspowered.nimbus.sdk.NimbusService;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles NPC interactions via FancyNpcs events for ALL NPC types.
 */
public class NpcListener implements Listener {

    private static final long COOLDOWN_MS = 2000;

    private final JavaPlugin plugin;
    private final NpcManager npcManager;
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache;
    private final ConcurrentHashMap<String, NimbusGroup> groupCache;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public NpcListener(JavaPlugin plugin, NpcManager npcManager,
                       ConcurrentHashMap<String, NimbusDisplay> displayCache,
                       ConcurrentHashMap<String, NimbusGroup> groupCache) {
        this.plugin = plugin;
        this.npcManager = npcManager;
        this.displayCache = displayCache;
        this.groupCache = groupCache;
    }

    @EventHandler
    public void onNpcInteract(de.oliver.fancynpcs.api.events.NpcInteractEvent event) {
        String npcName = event.getNpc().getData().getName();
        if (!npcName.startsWith("nimbus-")) return;

        String npcId = npcName.substring("nimbus-".length());
        NimbusNpc npc = npcManager.getNpc(npcId);
        if (npc == null) return;

        Player player = event.getPlayer();
        boolean isLeftClick = event.getInteractionType() == de.oliver.fancynpcs.api.actions.ActionTrigger.LEFT_CLICK;
        NpcAction action = isLeftClick ? npc.leftClick() : npc.rightClick();

        // No cooldown for INVENTORY action (feels sluggish otherwise)
        if (action != NpcAction.INVENTORY && !checkCooldown(player)) return;

        executeAction(player, npc, action, isLeftClick ? npc.leftClickValue() : npc.rightClickValue());
    }

    private void executeAction(Player player, NimbusNpc npc, NpcAction action, String actionValue) {
        switch (action) {
            case CONNECT -> {
                if (npc.serviceTarget()) {
                    NimbusService service = Nimbus.cache().get(npc.target());
                    if (service == null || !service.isReady()) {
                        TextCompat.sendRich(player, npc.target() + " is not available.", "red");
                        return;
                    }
                    sendConnectFeedback(player, npc.target());
                    Nimbus.client().sendPlayer(player.getName(), npc.target())
                            .exceptionally(e -> {
                                TextCompat.sendRich(player, "Failed to connect.", "red");
                                return null;
                            });
                } else {
                    NimbusService best = Nimbus.bestServer(npc.target(), npc.strategy());
                    if (best == null) {
                        TextCompat.sendRich(player, "No " + npc.target() + " server available.", "red");
                        return;
                    }
                    sendConnectFeedback(player, best.getName());
                    Nimbus.route(player.getName(), npc.target(), npc.strategy())
                            .exceptionally(e -> {
                                TextCompat.sendRich(player, "Failed to connect.", "red");
                                return null;
                            });
                }
            }
            case COMMAND -> {
                if (actionValue != null && !actionValue.isBlank()) {
                    String command = actionValue.startsWith("/") ? actionValue.substring(1) : actionValue;
                    player.performCommand(command);
                }
            }
            case INVENTORY -> new NpcInventory(plugin, npc, displayCache, groupCache).open(player);
            case NONE -> {}
        }
    }

    private void sendConnectFeedback(Player player, String serverName) {
        TextCompat.sendActionBar(player, "&a&l▶ &fConnecting to &b" + serverName + "&f...");
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        } catch (Exception ignored) {} // Sound enum may differ across versions
    }

    private boolean checkCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return false;
        cooldowns.put(player.getUniqueId(), now);
        return true;
    }
}
