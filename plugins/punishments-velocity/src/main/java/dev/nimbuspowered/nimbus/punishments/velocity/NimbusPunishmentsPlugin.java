package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.nimbuspowered.nimbus.sdk.NimbusClient;
import dev.nimbuspowered.nimbus.sdk.NimbusEventStream;
import org.slf4j.Logger;

/**
 * Velocity plugin that enforces Nimbus punishments proxy-wide.
 *
 * Loaded automatically on every proxy service prepare via the Punishments
 * controller module's {@code PluginDeployment(target = VELOCITY)}. Pulls
 * its config from the standard Nimbus env vars / system properties:
 *   - {@code nimbus.api.url} — controller REST endpoint
 *   - {@code NIMBUS_API_TOKEN} (env) / {@code nimbus.api.token} — bearer
 *
 * Responsibilities:
 *   - LoginListener: deny login for NETWORK-scoped bans
 *   - ConnectListener: block ServerPreConnectEvent for scoped bans
 *   - ChatListener: cancel chat for scoped mutes
 *   - LiveKickListener: subscribe to PUNISHMENT_ISSUED to disconnect instantly
 */
@Plugin(
    id = "nimbus-punishments",
    name = "Nimbus Punishments",
    version = "0.0.0",  // replaced at build time from gradle.properties
    description = "Network-wide ban/mute/kick enforcement for Nimbus-managed Velocity proxies",
    authors = {"NimbusPowered"}
)
public class NimbusPunishmentsPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private PunishmentsApiClient api;
    private NimbusEventStream eventStream;
    private LoginListener loginListener;

    @Inject
    public NimbusPunishmentsPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        String apiUrl = System.getProperty("nimbus.api.url");
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        String token = (envToken != null && !envToken.isEmpty())
                ? envToken
                : System.getProperty("nimbus.api.token", "");

        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.warn("No nimbus.api.url configured — NimbusPunishments disabled");
            return;
        }
        if (apiUrl.endsWith("/")) apiUrl = apiUrl.substring(0, apiUrl.length() - 1);

        this.api = new PunishmentsApiClient(apiUrl, token, logger);

        loginListener = new LoginListener(api, logger);
        server.getEventManager().register(this, loginListener);
        server.getEventManager().register(this, new ConnectListener(api, logger));
        server.getEventManager().register(this, new ChatListener(api, logger));

        // Subscribe to the controller's event stream for live-kick when a punishment
        // is issued against a currently-connected player. Without this, the offender
        // keeps their session until they disconnect.
        NimbusClient client = new NimbusClient(apiUrl, token);
        eventStream = client.createEventStream();
        eventStream.onEvent("PUNISHMENT_ISSUED", evt -> {
            new LiveKickHandler(server, logger, loginListener).handle(evt);
        });
        try {
            eventStream.connect();
            logger.info("Connected to controller event stream for live punishment propagation");
        } catch (Exception e) {
            logger.warn("Failed to connect event stream: {} — live-kick will not work until reconnect", e.getMessage());
        }

        logger.info("NimbusPunishments enabled — enforcing on Login, ServerPreConnect, PlayerChat");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (eventStream != null) {
            try { eventStream.close(); } catch (Exception ignored) {}
        }
    }
}
