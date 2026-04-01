package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

/**
 * Fired when a player disconnects from a service.
 */
public class PlayerDisconnectedEvent extends TypedEvent {

    public PlayerDisconnectedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getPlayerName() { return get("player"); }
    public String getServiceName() { return get("service"); }

    public static final String TYPE = "PLAYER_DISCONNECTED";
}
