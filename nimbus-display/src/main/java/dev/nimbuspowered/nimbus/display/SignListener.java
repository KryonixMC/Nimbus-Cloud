package dev.nimbuspowered.nimbus.display;

import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.NimbusService;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import org.bukkit.Sound;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles sign interactions:
 * - Right-click → connect to server (with cooldown)
 * - Break → remove if Nimbus sign
 */
public class SignListener implements Listener {

    private static final long COOLDOWN_MS = 2000;

    private final SignManager signManager;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public SignListener(SignManager signManager) {
        this.signManager = signManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign)) return;

        NimbusSign nSign = signManager.getSign(block.getLocation());
        if (nSign == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();

        // Cooldown check
        long now = System.currentTimeMillis();
        Long lastClick = cooldowns.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), now);

        if (nSign.serviceTarget()) {
            NimbusService service = Nimbus.cache().get(nSign.target());

            if (service == null || !service.isReady()) {
                TextCompat.sendRich(player, nSign.target() + " is not available.", "red");
                return;
            }

            sendConnectFeedback(player, nSign.target());
            Nimbus.client().sendPlayer(player.getName(), nSign.target())
                    .exceptionally(e -> {
                        TextCompat.sendRich(player, "Failed to connect.", "red");
                        return null;
                    });
        } else {
            NimbusService best = Nimbus.bestServer(nSign.target(), nSign.strategy());
            if (best == null) {
                TextCompat.sendRich(player, "No " + nSign.target() + " server available.", "red");
                return;
            }

            sendConnectFeedback(player, best.getName());
            Nimbus.route(player.getName(), nSign.target(), nSign.strategy())
                    .exceptionally(e -> {
                        TextCompat.sendRich(player, "Failed to connect.", "red");
                        return null;
                    });
        }
    }

    private void sendConnectFeedback(Player player, String serverName) {
        TextCompat.sendActionBar(player, "&a&l▶ &fConnecting to &b" + serverName + "&f...");
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        } catch (Exception ignored) {} // Sound enum may differ across versions
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) return;

        NimbusSign nSign = signManager.getSign(event.getBlock().getLocation());
        if (nSign == null) return;

        if (!event.getPlayer().hasPermission("nimbus.display.sign")) {
            event.setCancelled(true);
            TextCompat.sendRich(event.getPlayer(), "No permission.", "red");
            return;
        }

        signManager.removeSign(event.getBlock().getLocation());
        TextCompat.sendComposite(event.getPlayer(), new String[][]{
                {"Sign removed: ", "yellow"}, {nSign.target(), "white"}
        });
    }
}
