package dev.nimbus.signs;

import dev.nimbus.sdk.Nimbus;
import dev.nimbus.sdk.NimbusService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

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
        if (!event.getAction().isRightClick()) return;

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
                player.sendMessage(Component.text(nSign.target() + " is not available.", NamedTextColor.RED));
                return;
            }

            player.sendMessage(
                    Component.text("Connecting to ", NamedTextColor.GREEN)
                            .append(Component.text(nSign.target(), NamedTextColor.WHITE))
                            .append(Component.text("...", NamedTextColor.GREEN))
            );
            Nimbus.client().sendPlayer(player.getName(), nSign.target())
                    .exceptionally(e -> {
                        player.sendMessage(Component.text("Failed to connect.", NamedTextColor.RED));
                        return null;
                    });
        } else {
            NimbusService best = Nimbus.bestServer(nSign.target(), nSign.strategy());
            if (best == null) {
                player.sendMessage(Component.text("No " + nSign.target() + " server available.", NamedTextColor.RED));
                return;
            }

            player.sendMessage(
                    Component.text("Connecting to ", NamedTextColor.GREEN)
                            .append(Component.text(best.getName(), NamedTextColor.WHITE))
                            .append(Component.text("...", NamedTextColor.GREEN))
            );
            Nimbus.route(player.getName(), nSign.target(), nSign.strategy())
                    .exceptionally(e -> {
                        player.sendMessage(Component.text("Failed to connect.", NamedTextColor.RED));
                        return null;
                    });
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) return;

        NimbusSign nSign = signManager.getSign(event.getBlock().getLocation());
        if (nSign == null) return;

        if (!event.getPlayer().hasPermission("nimbus.signs.remove")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }

        signManager.removeSign(event.getBlock().getLocation());
        event.getPlayer().sendMessage(
                Component.text("Sign removed: ", NamedTextColor.YELLOW)
                        .append(Component.text(nSign.target(), NamedTextColor.WHITE))
        );
    }
}
