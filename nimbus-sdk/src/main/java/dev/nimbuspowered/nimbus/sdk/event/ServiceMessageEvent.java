package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when a service-to-service message is received.
 */
public class ServiceMessageEvent extends TypedEvent {

    public ServiceMessageEvent(NimbusEvent raw) {
        super(raw);
    }

    /** The service that sent the message. */
    public String getFromService() { return get("from"); }

    /** The target service. */
    public String getToService() { return get("to"); }

    /** The message channel (e.g. "game_ended", "player_stats"). */
    public String getChannel() { return get("channel"); }

    public static final String TYPE = "SERVICE_MESSAGE";
}
