package dev.nimbuspowered.nimbus.sdk;

import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import dev.nimbuspowered.nimbus.sdk.compat.VersionHelper;
import dev.nimbuspowered.nimbus.sdk.event.ChatFormatUpdatedEvent;
import dev.nimbuspowered.nimbus.sdk.event.CustomStateChangedEvent;
import dev.nimbuspowered.nimbus.sdk.event.EventRegistry;
import dev.nimbuspowered.nimbus.sdk.event.MotdUpdatedEvent;
import dev.nimbuspowered.nimbus.sdk.event.PlayerConnectedEvent;
import dev.nimbuspowered.nimbus.sdk.event.PlayerDisconnectedEvent;
import dev.nimbuspowered.nimbus.sdk.event.PlayerTabUpdatedEvent;
import dev.nimbuspowered.nimbus.sdk.event.ScaleDownEvent;
import dev.nimbuspowered.nimbus.sdk.event.ScaleUpEvent;
import dev.nimbuspowered.nimbus.sdk.event.ServiceCrashedEvent;
import dev.nimbuspowered.nimbus.sdk.event.ServiceMessageEvent;
import dev.nimbuspowered.nimbus.sdk.event.ServiceReadyEvent;
import dev.nimbuspowered.nimbus.sdk.event.ServiceStartingEvent;
import dev.nimbuspowered.nimbus.sdk.event.ServiceStoppedEvent;
import dev.nimbuspowered.nimbus.sdk.event.TabListUpdatedEvent;
import dev.nimbuspowered.nimbus.sdk.event.TypedEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkPublicSurfaceTest {

    private static final List<Class<?>> STABLE = List.of(
        Nimbus.class, NimbusClient.class, NimbusService.class,
        NimbusGroup.class, NimbusSelfService.class, NimbusDisplay.class,
        NimbusEvent.class, NimbusEventStream.class, PlayerTracker.class,
        TpsTracker.class, RoutingStrategy.class, ServiceRouter.class,
        NimbusApiException.class, ColorUtil.class, ServiceCache.class,
        NimbusSdkPlugin.class,
        SchedulerCompat.class, TextCompat.class, VersionHelper.class,
        TypedEvent.class, ServiceReadyEvent.class, ServiceStartingEvent.class,
        ServiceStoppedEvent.class, ServiceCrashedEvent.class,
        CustomStateChangedEvent.class, ScaleUpEvent.class, ScaleDownEvent.class,
        PlayerConnectedEvent.class, PlayerDisconnectedEvent.class,
        ServiceMessageEvent.class, TabListUpdatedEvent.class,
        MotdUpdatedEvent.class, PlayerTabUpdatedEvent.class,
        ChatFormatUpdatedEvent.class
    );

    private static final List<Class<?>> INTERNAL = List.of(
        EventRegistry.class
    );

    // @ApiStatus.Internal has CLASS retention, so reflection won't see it.
    // We scan the class-file bytes for the annotation descriptor instead.
    private static final String INTERNAL_DESCRIPTOR = "Lorg/jetbrains/annotations/ApiStatus$Internal;";

    private static boolean hasInternalAnnotation(Class<?> c) {
        String resource = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("class file not found: " + resource);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains(INTERNAL_DESCRIPTOR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void stableClassesHaveNoInternalAnnotation() {
        for (Class<?> c : STABLE) {
            assertFalse(
                hasInternalAnnotation(c),
                () -> c.getName() + " is part of the stable SDK surface and must not be @ApiStatus.Internal"
            );
        }
    }

    @Test
    void internalClassesAreAnnotated() {
        for (Class<?> c : INTERNAL) {
            assertTrue(
                hasInternalAnnotation(c),
                () -> c.getName() + " must be annotated @ApiStatus.Internal"
            );
        }
    }
}
