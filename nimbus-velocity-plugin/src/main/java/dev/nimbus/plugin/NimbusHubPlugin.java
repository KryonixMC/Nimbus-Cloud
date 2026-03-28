package dev.nimbus.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Optional;

@Plugin(
    id = "nimbus-hub",
    name = "Nimbus Hub",
    version = "0.1.0",
    description = "Lobby/Hub command for Nimbus cloud networks",
    authors = {"Nimbus"}
)
public class NimbusHubPlugin {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public NimbusHubPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        HubCommand hubCommand = new HubCommand(server);
        var commandManager = server.getCommandManager();

        for (String alias : new String[]{"hub", "lobby", "l"}) {
            var meta = commandManager.metaBuilder(alias)
                .plugin(this)
                .build();
            commandManager.register(meta, hubCommand);
        }

        logger.info("Nimbus Hub plugin loaded — /hub, /lobby, /l registered");

        server.getEventManager().register(this, new ConnectionListener(server, logger));
    }

    private static Optional<RegisteredServer> findLobby(ProxyServer server) {
        return server.getAllServers().stream()
            .filter(s -> s.getServerInfo().getName().toLowerCase().startsWith("lobby"))
            .min((a, b) -> a.getServerInfo().getName().compareToIgnoreCase(b.getServerInfo().getName()));
    }

    /**
     * Handles initial connection (force lobby) and kicked-from-server (fallback to lobby).
     */
    private static class ConnectionListener {

        private final ProxyServer server;
        private final Logger logger;

        ConnectionListener(ProxyServer server, Logger logger) {
            this.server = server;
            this.logger = logger;
        }

        @Subscribe
        public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
            Optional<RegisteredServer> lobby = findLobby(server);
            if (lobby.isPresent()) {
                event.setInitialServer(lobby.get());
            } else {
                // No lobby available — kick with message
                event.setInitialServer(null);
                event.getPlayer().disconnect(
                    Component.text("No lobby server available. Please try again later.", NamedTextColor.RED)
                );
            }
        }

        @Subscribe
        public void onKickedFromServer(KickedFromServerEvent event) {
            Player player = event.getPlayer();
            String kickedFrom = event.getServer().getServerInfo().getName();

            // If kicked from a non-lobby server, try to send back to lobby
            if (!kickedFrom.toLowerCase().startsWith("lobby")) {
                Optional<RegisteredServer> lobby = findLobby(server);
                if (lobby.isPresent()) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        lobby.get(),
                        Component.text("Sent back to lobby.", NamedTextColor.YELLOW)
                    ));
                    logger.info("Redirected {} to lobby after kick from {}", player.getUsername(), kickedFrom);
                    return;
                }
            }

            // Kicked from lobby or no lobby available — disconnect with message
            Component reason = event.getServerKickReason().orElse(
                Component.text("Connection lost.", NamedTextColor.RED)
            );
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
        }
    }

    private static class HubCommand implements SimpleCommand {

        private final ProxyServer server;

        HubCommand(ProxyServer server) {
            this.server = server;
        }

        @Override
        public void execute(Invocation invocation) {
            var source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                return;
            }

            Optional<RegisteredServer> lobbyServer = findLobby(server);

            if (lobbyServer.isEmpty()) {
                player.sendMessage(Component.text("No lobby server available.", NamedTextColor.RED));
                return;
            }

            // Already on a lobby?
            var currentServer = player.getCurrentServer().orElse(null);
            if (currentServer != null &&
                currentServer.getServerInfo().getName().equalsIgnoreCase(lobbyServer.get().getServerInfo().getName())) {
                player.sendMessage(Component.text("You are already on the lobby.", NamedTextColor.YELLOW));
                return;
            }

            String name = lobbyServer.get().getServerInfo().getName();
            player.sendMessage(
                Component.text("Connecting to ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text("...", NamedTextColor.GREEN))
            );
            player.createConnectionRequest(lobbyServer.get()).fireAndForget();
        }
    }
}
