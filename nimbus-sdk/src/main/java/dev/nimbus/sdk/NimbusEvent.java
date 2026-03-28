package dev.nimbus.sdk;

import java.util.Collections;
import java.util.Map;

/**
 * Represents an event received from the Nimbus WebSocket event stream.
 */
public class NimbusEvent {

    private final String type;
    private final String timestamp;
    private final Map<String, String> data;

    public NimbusEvent(String type, String timestamp, Map<String, String> data) {
        this.type = type;
        this.timestamp = timestamp;
        this.data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    /** Event type, e.g. SERVICE_READY, SERVICE_CUSTOM_STATE_CHANGED, SCALE_UP */
    public String getType() { return type; }

    /** ISO-8601 timestamp */
    public String getTimestamp() { return timestamp; }

    /** Event data as key-value pairs */
    public Map<String, String> getData() { return data; }

    /** Convenience: get a data value by key */
    public String get(String key) { return data.get(key); }

    /** Convenience: get the service name from event data */
    public String getServiceName() { return data.get("service"); }

    /** Convenience: get the group name from event data */
    public String getGroupName() { return data.get("group"); }

    @Override
    public String toString() {
        return "NimbusEvent{type='" + type + "', data=" + data + "}";
    }
}
