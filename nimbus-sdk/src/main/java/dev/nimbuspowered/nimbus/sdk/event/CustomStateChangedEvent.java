package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service's custom state changes (e.g. WAITING → INGAME).
 */
public class CustomStateChangedEvent extends TypedEvent {

    public CustomStateChangedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getServiceName() { return get("service"); }
    public String getGroupName() { return get("group"); }

    /** Previous custom state, or null if it was unset. */
    public String getOldState() { return get("oldState"); }

    /** New custom state, or null if it was cleared. */
    public String getNewState() { return get("newState"); }

    public static final String TYPE = "SERVICE_CUSTOM_STATE_CHANGED";
}
