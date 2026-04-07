package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when the global MOTD configuration is updated.
 */
public class MotdUpdatedEvent extends TypedEvent {

    public MotdUpdatedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getLine1() { return get("line1"); }
    public String getLine2() { return get("line2"); }
    public String getMaxPlayers() { return get("maxPlayers"); }
    public String getPlayerCountOffset() { return get("playerCountOffset"); }

    public static final String TYPE = "MOTD_UPDATED";
}
