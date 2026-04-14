package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

/**
 * Cancels chat at the proxy for muted players.
 *
 * Running here instead of on each backend means a single cancel for the whole
 * network — no backend plugin needed, no inconsistency across server versions.
 * For signed chat (1.19.1+) this still works: {@link PlayerChatEvent} fires as
 * Velocity forwards the message, and denying the result prevents it from
 * reaching other players. The sender's client may briefly echo the message
 * locally (client-side) but no other player sees it.
 *
 * Staff with {@code nimbus.punish.bypass} skip the check — we can't rely on
 * Velocity's own permission check here because there's no subject setup yet for
 * mutes, so we read the permission via {@code player.hasPermission}.
 */
public class ChatListener {

    private final PunishmentsApiClient api;
    private final Logger logger;

    public ChatListener(PunishmentsApiClient api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.EARLY)
    public EventTask onChat(PlayerChatEvent event) {
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            if (player.hasPermission("nimbus.punish.bypass")) return;

            ServerConnection current = player.getCurrentServer().orElse(null);
            String service = current == null ? null : current.getServerInfo().getName();
            String group = service == null ? null : deriveGroupName(service);

            JsonObject record = api.checkMute(player.getUniqueId(), group, service);
            if (record == null) return;

            event.setResult(PlayerChatEvent.ChatResult.denied());
            Component msg = LegacyComponentSerializer.legacySection()
                .deserialize(MessageBuilder.formatMuteLine(record));
            player.sendMessage(msg);
        });
    }

    private static String deriveGroupName(String serverName) {
        int dash = serverName.lastIndexOf('-');
        if (dash > 0) {
            String suffix = serverName.substring(dash + 1);
            try {
                Integer.parseInt(suffix);
                return serverName.substring(0, dash);
            } catch (NumberFormatException ignored) {}
        }
        return serverName;
    }
}
