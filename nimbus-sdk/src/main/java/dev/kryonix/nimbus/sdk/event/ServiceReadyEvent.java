package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service becomes ready to accept players.
 */
public class ServiceReadyEvent extends TypedEvent {

    public ServiceReadyEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getServiceName() { return get("service"); }
    public String getGroupName() { return get("group"); }

    public static final String TYPE = "SERVICE_READY";
}
