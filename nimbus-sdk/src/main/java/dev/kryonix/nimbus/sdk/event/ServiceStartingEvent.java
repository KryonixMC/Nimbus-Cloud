package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service begins starting up.
 */
public class ServiceStartingEvent extends TypedEvent {

    public ServiceStartingEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getServiceName() { return get("service"); }
    public String getGroupName() { return get("group"); }
    public int getPort() {
        String p = get("port");
        return p != null ? Integer.parseInt(p) : 0;
    }

    public static final String TYPE = "SERVICE_STARTING";
}
