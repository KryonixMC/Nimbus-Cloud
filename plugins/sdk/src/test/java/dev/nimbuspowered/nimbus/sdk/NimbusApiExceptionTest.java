package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NimbusApiExceptionTest {

    @Test
    void exposesStatusCodeAndBody() {
        NimbusApiException e = new NimbusApiException(404, "not found");
        assertEquals(404, e.getStatusCode());
        assertEquals("not found", e.getResponseBody());
    }

    @Test
    void messageIncludesStatusAndBody() {
        NimbusApiException e = new NimbusApiException(500, "boom");
        assertTrue(e.getMessage().contains("500"));
        assertTrue(e.getMessage().contains("boom"));
    }

    @Test
    void isRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(NimbusApiException.class));
    }
}
