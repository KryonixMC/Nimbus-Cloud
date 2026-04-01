package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

/**
 * Fired when a player's tab list display name override is set or cleared.
 */
public class PlayerTabUpdatedEvent extends TypedEvent {

    public PlayerTabUpdatedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getUuid() { return get("uuid"); }

    /** Returns the MiniMessage format string, or null if the override was cleared. */
    public String getFormat() { return get("format"); }

    public static final String TYPE = "PLAYER_TAB_UPDATED";
}
