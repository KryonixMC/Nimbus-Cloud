package dev.kryonix.nimbus.sdk.event;

import dev.kryonix.nimbus.sdk.NimbusEvent;

import java.util.Map;

/**
 * Base class for typed events. Wraps a raw {@link NimbusEvent}
 * and provides type-safe accessors.
 */
public abstract class TypedEvent {

    private final NimbusEvent raw;

    protected TypedEvent(NimbusEvent raw) {
        this.raw = raw;
    }

    /** The raw event. */
    public NimbusEvent getRaw() { return raw; }

    /** Event type string. */
    public String getType() { return raw.getType(); }

    /** ISO-8601 timestamp. */
    public String getTimestamp() { return raw.getTimestamp(); }

    /** All event data. */
    public Map<String, String> getData() { return raw.getData(); }

    /** Get a data value by key. */
    protected String get(String key) { return raw.get(key); }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + raw.getData() + "}";
    }
}
