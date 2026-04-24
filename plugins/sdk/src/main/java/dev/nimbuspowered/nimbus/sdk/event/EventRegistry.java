package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps event type strings to typed event constructors.
 * Used internally by {@link dev.nimbuspowered.nimbus.sdk.NimbusEventStream} to dispatch typed events.
 */
@ApiStatus.Internal
public final class EventRegistry {

    private static final Map<String, Function<NimbusEvent, ? extends TypedEvent>> FACTORIES = new HashMap<>();
    private static final Map<Class<? extends TypedEvent>, String> TYPE_MAP = new HashMap<>();

    static {
        register(ServiceReadyEvent.TYPE, ServiceReadyEvent.class, ServiceReadyEvent::new);
        register(ServiceStartingEvent.TYPE, ServiceStartingEvent.class, ServiceStartingEvent::new);
        register(ServiceStoppedEvent.TYPE, ServiceStoppedEvent.class, ServiceStoppedEvent::new);
        register(ServiceCrashedEvent.TYPE, ServiceCrashedEvent.class, ServiceCrashedEvent::new);
        register(CustomStateChangedEvent.TYPE, CustomStateChangedEvent.class, CustomStateChangedEvent::new);
        register(ScaleUpEvent.TYPE, ScaleUpEvent.class, ScaleUpEvent::new);
        register(ScaleDownEvent.TYPE, ScaleDownEvent.class, ScaleDownEvent::new);
        register(PlayerConnectedEvent.TYPE, PlayerConnectedEvent.class, PlayerConnectedEvent::new);
        register(PlayerDisconnectedEvent.TYPE, PlayerDisconnectedEvent.class, PlayerDisconnectedEvent::new);
        register(ServiceMessageEvent.TYPE, ServiceMessageEvent.class, ServiceMessageEvent::new);
        register(TabListUpdatedEvent.TYPE, TabListUpdatedEvent.class, TabListUpdatedEvent::new);
        register(MotdUpdatedEvent.TYPE, MotdUpdatedEvent.class, MotdUpdatedEvent::new);
        register(PlayerTabUpdatedEvent.TYPE, PlayerTabUpdatedEvent.class, PlayerTabUpdatedEvent::new);
        register(ChatFormatUpdatedEvent.TYPE, ChatFormatUpdatedEvent.class, ChatFormatUpdatedEvent::new);
    }

    private static <T extends TypedEvent> void register(String type, Class<T> clazz, Function<NimbusEvent, T> factory) {
        FACTORIES.put(type, factory);
        TYPE_MAP.put(clazz, type);
    }

    /** Create a typed event from a raw event, or null if the type is not registered. */
    @SuppressWarnings("unchecked")
    public static <T extends TypedEvent> T create(String type, NimbusEvent raw) {
        Function<NimbusEvent, ? extends TypedEvent> factory = FACTORIES.get(type);
        if (factory == null) return null;
        return (T) factory.apply(raw);
    }

    /** Get the event type string for a typed event class. */
    public static String getType(Class<? extends TypedEvent> clazz) {
        return TYPE_MAP.get(clazz);
    }

    private EventRegistry() {}
}
