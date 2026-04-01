package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service is being scaled down.
 */
public class ScaleDownEvent extends TypedEvent {

    public ScaleDownEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getGroupName() { return get("group"); }
    public String getServiceName() { return get("service"); }
    public String getReason() { return get("reason"); }

    public static final String TYPE = "SCALE_DOWN";
}
