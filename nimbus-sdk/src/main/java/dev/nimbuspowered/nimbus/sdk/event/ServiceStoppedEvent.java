package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service has stopped.
 */
public class ServiceStoppedEvent extends TypedEvent {

    public ServiceStoppedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getServiceName() { return get("service"); }

    public static final String TYPE = "SERVICE_STOPPED";
}
