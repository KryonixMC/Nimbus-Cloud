package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service crashes unexpectedly.
 */
public class ServiceCrashedEvent extends TypedEvent {

    public ServiceCrashedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getServiceName() { return get("service"); }

    public int getExitCode() {
        String code = get("exitCode");
        return code != null ? Integer.parseInt(code) : -1;
    }

    public int getRestartAttempt() {
        String attempt = get("restartAttempt");
        return attempt != null ? Integer.parseInt(attempt) : 0;
    }

    public static final String TYPE = "SERVICE_CRASHED";
}
