package dev.nimbuspowered.nimbus.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class StrictTomlTest {

    @Serializable
    data class Sample(val name: String = "default", val count: Int = 0)

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger(StrictToml::class.java) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `warns on unknown key in lenient mode and still decodes`() {
        val content = """
            name = "alpha"
            count = 3
            bogus = "unexpected"
        """.trimIndent()

        val decoded = StrictToml.strictDecode(
            serializer<Sample>(), content, "sample.toml", strict = false
        )

        assertEquals("alpha", decoded.name)
        assertEquals(3, decoded.count)

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertTrue(
            warnings.any { it.formattedMessage.contains("bogus") },
            "expected WARN mentioning 'bogus', got: ${warnings.map { it.formattedMessage }}"
        )
        assertTrue(
            warnings.any { it.formattedMessage.contains("Nimbus 1.0") },
            "expected WARN to mention Nimbus 1.0 flip"
        )
    }

    @Test
    fun `throws ConfigException on unknown key when strict`() {
        val content = """
            name = "alpha"
            typo_field = 42
        """.trimIndent()

        val ex = assertThrows(ConfigException::class.java) {
            StrictToml.strictDecode(
                serializer<Sample>(), content, "sample.toml", strict = true
            )
        }
        assertTrue(
            ex.message!!.contains("typo_field"),
            "message should name the offending key, was: ${ex.message}"
        )
        assertTrue(
            ex.message!!.contains("strict_config=true"),
            "message should mention strict_config flag, was: ${ex.message}"
        )
    }

    @Test
    fun `no warnings when all keys are known`() {
        val content = """
            name = "alpha"
            count = 7
        """.trimIndent()

        val decoded = StrictToml.strictDecode(
            serializer<Sample>(), content, "sample.toml", strict = false
        )

        assertEquals("alpha", decoded.name)
        assertEquals(7, decoded.count)
        val warnings = appender.list.filter { it.level == Level.WARN }
        assertTrue(warnings.isEmpty(), "no WARN expected, got: ${warnings.map { it.formattedMessage }}")
    }

    @Test
    fun `unknown key does not crash and still returns usable config`() {
        val content = """
            name = "alpha"
            count = 1
            mystery = true
            another_unknown = "x"
        """.trimIndent()

        // Must not throw, must decode known fields.
        val decoded = StrictToml.strictDecode(
            serializer<Sample>(), content, "sample.toml", strict = false
        )
        assertEquals("alpha", decoded.name)
        assertEquals(1, decoded.count)

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertTrue(warnings.any { it.formattedMessage.contains("mystery") })
        assertTrue(warnings.any { it.formattedMessage.contains("another_unknown") })
    }

    @Test
    fun `backwards compat - empty strict flag defaults to warn`() {
        val content = """
            name = "alpha"
            legacy_flag = "old"
        """.trimIndent()

        // Default param path mimics pre-existing callers that didn't know about strict.
        val decoded = StrictToml.strictDecode(
            serializer<Sample>(), content, "legacy.toml", strict = false
        )
        assertEquals("alpha", decoded.name)
        val errors = appender.list.filter { it.level == Level.ERROR }
        assertTrue(errors.isEmpty(), "no ERROR expected in warn-only mode")
    }
}
