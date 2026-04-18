package dev.nimbuspowered.nimbus.module.auth

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Operator-customisable chat templates for the dashboard-auth in-game UX.
 *
 * Placeholders:
 *  - `{code}`  — the 6-digit login code (for [code_message])
 *  - `{url}`   — the full magic-link URL (for [magic_link_message])
 *  - `{click}` — the clickable portion for the magic-link message (defaults
 *                to `[Klick zum Einloggen]`; customisable via [magicLinkClickLabel])
 *  - `{ttl}`   — remaining seconds for either challenge
 *
 * Colour codes use `&` (e.g. `&a`, `&e`). The SDK / Bridge convert them to
 * Adventure Components (with the `{click}` token turned into a ClickEvent on
 * Paper/Velocity, or a plain chat message with the URL appended on legacy
 * Spigot).
 */
@Serializable
data class AuthMessages(
    @SerialName("code_message")
    val codeMessage: String = "&a[Nimbus] &fDein Login-Code: &e&l{code}&r &7({ttl}s gültig). Öffne das Dashboard und gib ihn ein.",
    @SerialName("magic_link_message")
    val magicLinkMessage: String = "&d✨ &f[Nimbus] &fDein magischer Login-Link ist bereit! {click} &7({ttl}s gültig)",
    @SerialName("magic_link_click_label")
    val magicLinkClickLabel: String = "&e&l[Klick zum Einloggen]",
    @SerialName("magic_link_hover")
    val magicLinkHover: String = "&7Öffnet das Nimbus-Dashboard und loggt dich automatisch ein."
)

class AuthMessagesStore(private val moduleDir: Path) {
    private val logger = LoggerFactory.getLogger(AuthMessagesStore::class.java)
    private val file: Path = moduleDir.resolve("messages.toml")

    fun loadOrCreate(): AuthMessages {
        Files.createDirectories(moduleDir)
        if (!Files.exists(file)) {
            writeDefault()
            return AuthMessages()
        }
        return try {
            val text = Files.readString(file)
            Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
                .decodeFromString(AuthMessages.serializer(), text)
        } catch (e: Exception) {
            logger.warn("Failed to parse {} — using defaults ({})", file, e.message)
            AuthMessages()
        }
    }

    private fun writeDefault() {
        val content = """
            # Nimbus Auth — in-game chat templates for the /dashboard login flow.
            #
            # Placeholders:
            #   {code}  — 6-digit login code
            #   {url}   — full magic-link URL (rendered as the click target)
            #   {click} — clickable label for the magic-link message
            #   {ttl}   — remaining seconds
            #
            # Colour codes use `&` — they are translated per-platform
            # (Paper/Velocity use Adventure, legacy Spigot uses §).

            code_message = "&a[Nimbus] &fDein Login-Code: &e&l{code}&r &7({ttl}s gültig). Öffne das Dashboard und gib ihn ein."
            magic_link_message = "&d✨ &f[Nimbus] &fDein magischer Login-Link ist bereit! {click} &7({ttl}s gültig)"
            magic_link_click_label = "&e&l[Klick zum Einloggen]"
            magic_link_hover = "&7Öffnet das Nimbus-Dashboard und loggt dich automatisch ein."
        """.trimIndent() + "\n"
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
