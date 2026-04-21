package dev.nimbuspowered.nimbus.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.DeserializationStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Config decoder that surfaces unknown TOML keys as warnings (or errors in strict mode),
 * while still producing a decoded value via the lenient parser.
 *
 * 0.13 semantics: warn-by-default. `strict = true` makes unknown keys fatal.
 * 1.0 target: flip the default to strict for controller-owned configs.
 *
 * User-editable message templates (AuthMessages, PunishmentsMessages) must keep using
 * plain lenient decoding — forward-compat with future placeholder keys.
 */
object StrictToml {
    private val logger: Logger = LoggerFactory.getLogger(StrictToml::class.java)
    private val unknownKeyRegex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")

    private val strictToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    private val lenientToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

    fun <T> strictDecode(
        deserializer: DeserializationStrategy<T>,
        content: String,
        label: String,
        strict: Boolean
    ): T {
        val unknown = collectUnknownKeys(deserializer, content)
        if (unknown.isNotEmpty()) {
            if (strict) {
                throw ConfigException(
                    "Config [$label]: unknown keys rejected by strict_config=true: " +
                        unknown.joinToString(", ") { "'${it.key}' at [${it.scope}]" }
                )
            }
            for (k in unknown) {
                logger.warn(
                    "Config [{}]: unknown key '{}' at [{}] — will be an error in Nimbus 1.0",
                    label, k.key, k.scope
                )
            }
        }
        return lenientToml.decodeFromString(deserializer, content)
    }

    private data class UnknownKey(val key: String, val scope: String)

    private fun <T> collectUnknownKeys(
        deserializer: DeserializationStrategy<T>,
        content: String
    ): List<UnknownKey> {
        val found = mutableListOf<UnknownKey>()
        var remaining = content
        repeat(64) {
            try {
                strictToml.decodeFromString(deserializer, remaining)
                return found
            } catch (e: Exception) {
                // UnknownNameException is ktoml-internal — match by message pattern.
                val m = unknownKeyRegex.find(e.message ?: "") ?: return found
                val key = m.groupValues[1]
                val scope = m.groupValues[2]
                found += UnknownKey(key, scope)
                val stripped = stripKey(remaining, key, scope) ?: return found
                if (stripped == remaining) return found
                remaining = stripped
            }
        }
        return found
    }

    private fun stripKey(content: String, key: String, scope: String): String? {
        val lines = content.lines().toMutableList()
        var currentScope = ""
        val sectionRegex = Regex("^\\s*\\[([^\\]]+)\\]\\s*$")
        val keyRegex = Regex("^\\s*${Regex.escape(key)}\\s*=.*$")
        for (i in lines.indices) {
            val line = lines[i]
            val section = sectionRegex.matchEntire(line)
            if (section != null) {
                currentScope = section.groupValues[1]
                continue
            }
            if (currentScope == scope && keyRegex.matches(line)) {
                lines[i] = ""
                return lines.joinToString("\n")
            }
        }
        return null
    }
}
