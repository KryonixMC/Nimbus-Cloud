package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a group scales up (new instance starting).
 */
public class ScaleUpEvent extends TypedEvent {

    public ScaleUpEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getGroupName() { return get("group"); }
    public String getReason() { return get("reason"); }

    public int getFromInstances() {
        String v = get("from");
        return v != null ? Integer.parseInt(v) : 0;
    }

    public int getToInstances() {
        String v = get("to");
        return v != null ? Integer.parseInt(v) : 0;
    }

    public static final String TYPE = "SCALE_UP";
}
