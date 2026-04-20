package dev.nimbuspowered.nimbus.logging

import ch.qos.logback.classic.LoggerContext
import org.junit.jupiter.api.Test
import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogbackJsonConfigTest {

    @Test
    fun `logback-json resource exists on the classpath`() {
        val res = this::class.java.classLoader.getResource("logback-json.xml")
        assertNotNull(res, "logback-json.xml must be on the classpath")
    }

    @Test
    fun `logback-json references the LogstashEncoder`() {
        // We don't try to fully parse the XML through a fresh LoggerContext here
        // because Joran can silently accept an encoder it fails to instantiate
        // (the appender ends up with a null encoder). A content check is a more
        // robust smoke test for "this config is wired to JSON output".
        val stream = this::class.java.classLoader.getResourceAsStream("logback-json.xml")
        assertNotNull(stream, "logback-json.xml must be readable")
        val content = stream.bufferedReader().readText()
        assertTrue(
            content.contains("net.logstash.logback.encoder.LogstashEncoder"),
            "logback-json.xml should reference LogstashEncoder"
        )
        assertTrue(content.contains("service_name"), "expected MDC key service_name")
        assertTrue(content.contains("request_id"), "expected MDC key request_id")
    }

    // Note: we deliberately don't assert `Class.forName("net.logstash...LogstashEncoder")`
    // from tests. The encoder is an `implementation` dependency, so depending on
    // Gradle's classpath separation it may or may not be visible to tests. The
    // packaged fat-JAR picks it up via Shadow regardless, and the XML-content
    // test above catches the common breakage (encoder removed from the XML).

    @Test
    fun `SLF4J still binds to logback at runtime`() {
        // Guards against a dependency shuffle silently removing logback-classic.
        val factory: ILoggerFactory = LoggerFactory.getILoggerFactory()
        assertTrue(factory is LoggerContext, "expected logback LoggerContext, got ${factory.javaClass}")
    }
}
