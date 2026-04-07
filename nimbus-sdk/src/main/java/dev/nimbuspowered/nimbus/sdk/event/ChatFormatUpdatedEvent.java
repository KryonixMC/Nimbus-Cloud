package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when the global chat format configuration is updated.
 */
public class ChatFormatUpdatedEvent extends TypedEvent {

    public ChatFormatUpdatedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getFormat() { return get("format"); }
    public String getEnabled() { return get("enabled"); }

    public static final String TYPE = "CHAT_FORMAT_UPDATED";
}
