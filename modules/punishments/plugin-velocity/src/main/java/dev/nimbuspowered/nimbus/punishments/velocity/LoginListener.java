package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Denies login for players with an active NETWORK-scoped ban.
 *
 * We hook {@link LoginEvent} rather than PreLoginEvent because LoginEvent fires
 * after Mojang authentication — {@code player.getUniqueId()} is then the real
 * premium UUID, which matches bans issued against that UUID. Group / service-scoped
 * bans do NOT deny login here; they're blocked per-server in {@link ConnectListener}.
 */
public class LoginListener {

    private final PunishmentsApiClient api;
    private final Logger logger;

    public LoginListener(PunishmentsApiClient api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String ip = player.getRemoteAddress().getAddress().getHostAddress();

            JsonObject record = api.checkLogin(uuid, ip);
            if (record == null) return;

            event.setResult(ResultedEvent.ComponentResult.denied(MessageBuilder.kickMessage(record)));
            logger.info("Denied login for {} ({}): {} — {}",
                player.getUsername(), uuid,
                record.has("type") ? record.get("type").getAsString() : "?",
                record.has("reason") && !record.get("reason").isJsonNull()
                    ? record.get("reason").getAsString() : "no reason");
        });
    }

    /** Called by the live-kick handler to purge the cache when a new ban is issued. */
    public void invalidate(UUID uuid) {
        api.invalidate(uuid);
    }
}
