package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.nimbuspowered.nimbus.sdk.NimbusEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Applies PUNISHMENT_ISSUED events to currently-connected players in real time.
 *
 * Behaviour per type:
 *   BAN / TEMPBAN / IPBAN — full disconnect if NETWORK-scoped, or reroute to a
 *     lobby if GROUP/SERVICE-scoped and the player is on the matching backend.
 *   KICK — full disconnect with the kick message.
 *   WARN — leave the session intact, send the kickMessage as a chat line so
 *     the player sees the warning immediately.
 *   MUTE / TEMPMUTE — nothing on the proxy. The backend plugin owns its own
 *     mute cache (3 s TTL) so a chat within seconds of the mute being issued
 *     still picks up the new state without us having to push updates.
 *
 * Every event also invalidates the LoginListener check cache so a reconnect
 * attempt sees the new punishment with no 5 s lag.
 */
public class LiveKickHandler {

    private final ProxyServer server;
    private final Logger logger;
    private final LoginListener loginListener;
    private final PunishmentsApiClient api;

    public LiveKickHandler(
            ProxyServer server,
            Logger logger,
            LoginListener loginListener,
            PunishmentsApiClient api
    ) {
        this.server = server;
        this.logger = logger;
        this.loginListener = loginListener;
        this.api = api;
    }

    public void handle(NimbusEvent evt) {
        String type = evt.get("type");
        String uuidStr = evt.get("targetUuid");
        if (type == null || uuidStr == null) return;

        UUID uuid;
        try { uuid = UUID.fromString(uuidStr); }
        catch (IllegalArgumentException e) {
            logger.debug("PUNISHMENT_ISSUED with malformed UUID: {}", uuidStr);
            return;
        }

        // Purge the proxy-side login/connect caches so follow-up checks see the new state
        loginListener.invalidate(uuid);
        api.invalidate(uuid);

        switch (type) {
            case "WARN":
                sendWarnMessage(uuid, evt);
                break;
            case "MUTE":
            case "TEMPMUTE":
                // Backend plugin handles chat enforcement — nothing to do on proxy
                break;
            case "BAN":
            case "TEMPBAN":
            case "IPBAN":
            case "KICK":
                disconnectFor(uuid, type, evt);
                break;
            default:
                // Unknown type — future-proofing, no-op
                break;
        }
    }

    private void sendWarnMessage(UUID uuid, NimbusEvent evt) {
        server.getPlayer(uuid).ifPresent(player -> {
            String rendered = evt.get("kickMessage");
            Component msg = rendered != null && !rendered.isBlank()
                ? LegacyComponentSerializer.legacySection().deserialize(rendered)
                : Component.text("You have been warned.", NamedTextColor.YELLOW);
            player.sendMessage(msg);
            logger.info("Warned {}: {}", player.getUsername(),
                evt.get("reason") != null ? evt.get("reason") : "no reason");
        });
    }


    private void disconnectFor(UUID uuid, String type, NimbusEvent evt) {
        server.getPlayer(uuid).ifPresent(player -> {
            String scope = evt.get("scope");
            String scopeTarget = evt.get("scopeTarget");

            JsonObject record = new JsonObject();
            record.addProperty("type", type);
            if (evt.get("reason") != null) record.addProperty("reason", evt.get("reason"));
            if (evt.get("issuer") != null) record.addProperty("issuerName", evt.get("issuer"));
            if (evt.get("expiresAt") != null && !evt.get("expiresAt").isBlank()) {
                record.addProperty("expiresAt", evt.get("expiresAt"));
            }
            if (scope != null) record.addProperty("scope", scope);
            if (scopeTarget != null) record.addProperty("scopeTarget", scopeTarget);
            if (evt.get("kickMessage") != null && !evt.get("kickMessage").isBlank()) {
                record.addProperty("kickMessage", evt.get("kickMessage"));
            }

            Component msg = MessageBuilder.kickMessage(record);

            if (scope == null || "NETWORK".equals(scope) || "KICK".equals(type)) {
                player.disconnect(msg);
                logger.info("Disconnected {} ({}): {}", player.getUsername(), uuid, type);
                return;
            }

            // Scoped ban: only boot the player if they're on the targeted backend
            ServerConnection conn = player.getCurrentServer().orElse(null);
            if (conn == null) return;
            String currentServer = conn.getServerInfo().getName();
            String currentGroup = deriveGroupName(currentServer);
            boolean affected =
                ("GROUP".equals(scope) && currentGroup.equals(scopeTarget)) ||
                ("SERVICE".equals(scope) && currentServer.equals(scopeTarget));
            if (!affected) return;

            var lobby = server.getAllServers().stream()
                .filter(s -> s.getServerInfo().getName().toLowerCase().startsWith("lobby"))
                .filter(s -> !s.getServerInfo().getName().equals(currentServer))
                .min(java.util.Comparator.comparingInt(s -> s.getPlayersConnected().size()));
            if (lobby.isPresent()) {
                player.createConnectionRequest(lobby.get()).fireAndForget();
                player.sendMessage(msg);
                logger.info("Kicked {} from {} ({}) → {}",
                    player.getUsername(), currentServer, type, lobby.get().getServerInfo().getName());
            } else {
                player.disconnect(msg.append(Component.newline())
                    .append(Component.text("No lobby available — disconnected.", NamedTextColor.GRAY)));
            }
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
