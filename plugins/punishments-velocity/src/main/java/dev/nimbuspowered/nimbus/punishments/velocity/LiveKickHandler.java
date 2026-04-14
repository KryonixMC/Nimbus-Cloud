package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.nimbuspowered.nimbus.sdk.NimbusEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Receives {@code PUNISHMENT_ISSUED} events from the controller's event stream
 * and disconnects the target if they're online on this proxy.
 *
 * Scope matters: a NETWORK ban disconnects outright, a GROUP/SERVICE ban only
 * boots the player from matching backends (sends them to a lobby instead).
 * Chat-only mutes and warnings leave the session intact but invalidate the
 * mute cache so the next chat message is immediately blocked.
 */
public class LiveKickHandler {

    private final ProxyServer server;
    private final Logger logger;
    private final LoginListener loginListener;

    public LiveKickHandler(ProxyServer server, Logger logger, LoginListener loginListener) {
        this.server = server;
        this.logger = logger;
        this.loginListener = loginListener;
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

        // Always purge the cache so subsequent check calls see the new punishment
        loginListener.invalidate(uuid);

        // Only BAN / TEMPBAN / IPBAN / KICK cause immediate disconnect
        if (!type.endsWith("BAN") && !type.equals("KICK")) return;

        server.getPlayer(uuid).ifPresent(player -> {
            String scope = evt.get("scope");
            String scopeTarget = evt.get("scopeTarget");

            // Build a JsonObject compatible with MessageBuilder.kickMessage
            JsonObject record = new JsonObject();
            record.addProperty("type", type);
            if (evt.get("reason") != null) record.addProperty("reason", evt.get("reason"));
            if (evt.get("issuer") != null) record.addProperty("issuerName", evt.get("issuer"));
            if (evt.get("expiresAt") != null && !evt.get("expiresAt").isBlank()) {
                record.addProperty("expiresAt", evt.get("expiresAt"));
            }
            if (scope != null) record.addProperty("scope", scope);
            if (scopeTarget != null) record.addProperty("scopeTarget", scopeTarget);

            Component msg = MessageBuilder.kickMessage(record);

            if (scope == null || "NETWORK".equals(scope)) {
                // Full disconnect
                player.disconnect(msg);
                logger.info("Disconnected {} ({}): network-wide {}", player.getUsername(), uuidStr, type);
            } else {
                // Scoped ban: if the player is on the targeted group/service, boot them back to a lobby
                var conn = player.getCurrentServer().orElse(null);
                if (conn == null) return;
                String currentServer = conn.getServerInfo().getName();
                String currentGroup = deriveGroupName(currentServer);
                boolean affected =
                    ("GROUP".equals(scope) && currentGroup.equals(scopeTarget)) ||
                    ("SERVICE".equals(scope) && currentServer.equals(scopeTarget));
                if (!affected) return;

                // Try a lobby; fall back to disconnect if none available
                var lobby = server.getAllServers().stream()
                    .filter(s -> s.getServerInfo().getName().toLowerCase().startsWith("lobby"))
                    .filter(s -> !s.getServerInfo().getName().equals(currentServer))
                    .min(java.util.Comparator.comparingInt(s -> s.getPlayersConnected().size()));
                if (lobby.isPresent()) {
                    player.createConnectionRequest(lobby.get()).fireAndForget();
                    player.sendMessage(msg);
                    logger.info("Kicked {} from {} due to {} ({}) → {}",
                        player.getUsername(), currentServer, type, scope, lobby.get().getServerInfo().getName());
                } else {
                    player.disconnect(msg.append(Component.newline())
                        .append(Component.text("No lobby available — disconnected.", NamedTextColor.GRAY)));
                }
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
