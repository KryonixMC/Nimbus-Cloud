package dev.nimbuspowered.nimbus.auth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.nimbuspowered.nimbus.sdk.NimbusClient;
import dev.nimbuspowered.nimbus.sdk.NimbusEventStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Velocity companion to the Nimbus dashboard-auth module.
 *
 * <p>Its only job is to deliver dashboard-initiated magic-link chat
 * components: the dashboard login page posts a player's name, the auth
 * controller module fires an {@code AUTH_MAGIC_LINK_DELIVERY} event, and
 * this plugin turns that event into a clickable Adventure message for the
 * target player if they're connected to this proxy.
 *
 * <p>The in-game {@code /nimbus dashboard …} command lives in the Bridge's
 * CloudCommand now — it forwards to the controller's {@code DashboardCommand}
 * module command, same pattern as every other Nimbus module. No
 * command registration here.
 *
 * <p>Deployment: auto-installed on every Velocity proxy by the auth
 * controller module via {@code PluginDeployment(target = VELOCITY)}.
 */
@Plugin(
    id = "nimbus-auth",
    name = "Nimbus Auth",
    version = "0.0.0",  // replaced at build time from gradle.properties
    description = "Magic-link chat delivery for Nimbus dashboard auth",
    authors = {"NimbusPowered"}
)
public class NimbusAuthVelocityPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer server;
    private final Logger logger;

    private NimbusClient sdkClient;
    private NimbusEventStream eventStream;

    @Inject
    public NimbusAuthVelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        event.getClass(); // intentionally touch event to satisfy static analysis
        String apiUrl = System.getProperty("nimbus.api.url");
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        String token = (envToken != null && !envToken.isEmpty())
                ? envToken
                : System.getProperty("nimbus.api.token", "");

        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.warn("No nimbus.api.url configured — NimbusAuth magic-link delivery disabled");
            return;
        }
        if (apiUrl.endsWith("/")) apiUrl = apiUrl.substring(0, apiUrl.length() - 1);

        // Subscribe to AUTH_MAGIC_LINK_DELIVERY module events. The controller
        // fires one whenever the dashboard login page requests a magic link
        // for a MC name; we render a clickable Adventure component directly
        // to the target player if they're on this proxy.
        try {
            sdkClient = new NimbusClient(apiUrl, token);
            eventStream = sdkClient.createEventStream();
            eventStream.onEvent("AUTH_MAGIC_LINK_DELIVERY", ev -> {
                String uuidStr = ev.get("uuid");
                String url = ev.get("url");
                String rawTtl = ev.get("ttl");
                final String ttl = (rawTtl == null) ? "60" : rawTtl;
                if (uuidStr == null || url == null) return;
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ex) {
                    return;
                }
                server.getPlayer(uuid).ifPresent(p -> sendMagicLink(p, url, ttl));
            });
            eventStream.connect();
            logger.info("NimbusAuth enabled — AUTH_MAGIC_LINK_DELIVERY subscribed");
        } catch (Exception e) {
            logger.warn("Failed to subscribe to AUTH_MAGIC_LINK_DELIVERY — "
                    + "dashboard-initiated magic links will not reach players on this proxy: {}",
                    e.getMessage());
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (eventStream != null) {
            try {
                eventStream.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendMagicLink(Player player, String url, String ttl) {
        Component clickable = LEGACY.deserialize("&e&l[Klick zum Einloggen]")
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(LEGACY.deserialize(
                        "&7Öffnet das Nimbus-Dashboard und loggt dich automatisch ein.")));

        Component msg = LEGACY.deserialize("&d✨ &f[Nimbus] &fDein magischer Login-Link ist bereit! ")
                .append(clickable)
                .append(LEGACY.deserialize(" &7(" + ttl + "s gültig)"));
        player.sendMessage(msg);
    }
}
