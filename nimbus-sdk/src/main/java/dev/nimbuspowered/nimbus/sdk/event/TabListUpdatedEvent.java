package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;

/**
 * Fired when the global tab list configuration is updated.
 */
public class TabListUpdatedEvent extends TypedEvent {

    public TabListUpdatedEvent(NimbusEvent raw) {
        super(raw);
    }

    public String getHeader() { return get("header"); }
    public String getFooter() { return get("footer"); }
    public String getPlayerFormat() { return get("playerFormat"); }
    public String getUpdateInterval() { return get("updateInterval"); }

    public static final String TYPE = "TABLIST_UPDATED";
}
