package dev.nimbuspowered.nimbus.punishments.backend;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.logging.Logger;

/**
 * Cancels chat for muted players on the backend.
 *
 * <p>{@link AsyncPlayerChatEvent} fires on an async thread before the message
 * is forwarded to other players, and (critically) before the signed-chat
 * acknowledgement is sent back to the client — so calling
 * {@code setCancelled(true)} here cleanly drops the message without the
 * protocol-state disconnect you get when you try to cancel at the proxy.
 *
 * <p>Paper still fires this event for compat even though it's marked
 * deprecated; that's good enough for mute enforcement. If we ever need to
 * rewrite for Paper's signed {@code AsyncChatEvent} we can branch on
 * {@code VersionHelper}.
 */
public class ChatListener implements Listener {

    private final NimbusPunishmentsBackendPlugin plugin;
    private final MuteApiClient api;
    private final Logger logger;

    public ChatListener(NimbusPunishmentsBackendPlugin plugin, MuteApiClient api) {
        this.plugin = plugin;
        this.api = api;
        this.logger = plugin.getLogger();
    }

    @SuppressWarnings("deprecation") // AsyncPlayerChatEvent still fires on Paper 1.19+
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("nimbus.punish.bypass")) return;

        JsonObject record = api.checkMute(player.getUniqueId());
        if (record == null) return;

        event.setCancelled(true);

        String rendered = record.has("kickMessage") && !record.get("kickMessage").isJsonNull()
                ? record.get("kickMessage").getAsString()
                : fallbackMuteLine(record);
        player.sendMessage(rendered);
    }

    /** Only used if the controller omits the rendered kickMessage (legacy fallback). */
    private static String fallbackMuteLine(JsonObject record) {
        String reason = record.has("reason") && !record.get("reason").isJsonNull()
                ? record.get("reason").getAsString() : "No reason";
        Long remaining = record.has("remainingSeconds") && !record.get("remainingSeconds").isJsonNull()
                ? record.get("remainingSeconds").getAsLong() : null;
        if (remaining == null || remaining <= 0) return "\u00a7cYou are muted: \u00a7f" + reason;
        return "\u00a7cYou are muted (" + remaining + "s left): \u00a7f" + reason;
    }
}
