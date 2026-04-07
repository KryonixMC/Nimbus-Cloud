package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a player connects to a service.
 */
public class PlayerConnectedEvent extends TypedEvent {

    public PlayerConnectedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getPlayerName() { return get("player"); }
    public String getServiceName() { return get("service"); }

    public static final String TYPE = "PLAYER_CONNECTED";
}
