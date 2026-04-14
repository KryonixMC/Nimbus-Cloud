package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

/**
 * Blocks access to a specific backend when the player has a GROUP- or SERVICE-scoped
 * ban targeting it. The player stays on the network — they can still return to a
 * non-banned lobby / server.
 *
 * NETWORK-scoped bans are already handled by {@link LoginListener} at login time.
 * The controller's {@code /check/{uuid}?group=&service=} endpoint returns any
 * NETWORK, matching GROUP, or matching SERVICE ban, so we cover all three here too
 * as a belt-and-suspenders in case a NETWORK ban slipped through the login check.
 */
public class ConnectListener {

    private final PunishmentsApiClient api;
    private final Logger logger;

    public ConnectListener(PunishmentsApiClient api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onConnect(ServerPreConnectEvent event) {
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            RegisteredServer target = event.getOriginalServer();
            String serverName = target.getServerInfo().getName();
            String groupName = deriveGroupName(serverName);
            String ip = player.getRemoteAddress().getAddress().getHostAddress();

            JsonObject record = api.checkConnect(player.getUniqueId(), ip, groupName, serverName);
            if (record == null) return;

            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(MessageBuilder.kickMessage(record));
            logger.info("Blocked {} from joining {} ({}): {} — {}",
                player.getUsername(), serverName, groupName,
                record.has("type") ? record.get("type").getAsString() : "?",
                record.has("reason") && !record.get("reason").isJsonNull()
                    ? record.get("reason").getAsString() : "no reason");
        });
    }

    /** "Lobby-3" → "Lobby"; falls through to the full name if there's no `-<n>` suffix. */
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
