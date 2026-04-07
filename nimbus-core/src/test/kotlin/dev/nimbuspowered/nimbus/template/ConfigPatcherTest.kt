package dev.nimbuspowered.nimbus.template

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPatcherTest {

    private lateinit var patcher: ConfigPatcher
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        patcher = ConfigPatcher()
        tempDir = createTempDirectory("nimbus-patcher-test")
    }

    // --- patchServerProperties ---

    @Test
    fun `patchServerProperties sets server-port in existing file`() {
        val file = tempDir.resolve("server.properties")
        file.writeText(buildString {
            appendLine("server-port=25565")
            appendLine("online-mode=true")
            appendLine("motd=A Minecraft Server")
        })

        patcher.patchServerProperties(tempDir, 30001)

        val lines = file.readLines()
        assertTrue(lines.contains("server-port=30001"), "Port should be patched to 30001")
        assertTrue(lines.contains("online-mode=false"), "online-mode should be set to false")
    }

    @Test
    fun `patchServerProperties preserves other properties`() {
        val file = tempDir.resolve("server.properties")
        file.writeText(buildString {
            appendLine("server-port=25565")
            appendLine("motd=My Server")
            appendLine("max-players=50")
            appendLine("level-name=world")
        })

        patcher.patchServerProperties(tempDir, 30001)

        val lines = file.readLines()
        assertTrue(lines.contains("motd=My Server"), "motd should be preserved")
        assertTrue(lines.contains("max-players=50"), "max-players should be preserved")
        assertTrue(lines.contains("level-name=world"), "level-name should be preserved")
    }

    @Test
    fun `patchServerProperties creates file if it does not exist`() {
        assertFalse(tempDir.resolve("server.properties").exists())

        patcher.patchServerProperties(tempDir, 30005)

        val file = tempDir.resolve("server.properties")
        assertTrue(file.exists(), "server.properties should be created")
        val text = file.readText()
        assertTrue(text.contains("server-port=30005"))
        assertTrue(text.contains("online-mode=false"))
        assertTrue(text.contains("server-ip=0.0.0.0"))
    }

    // --- patchVelocityConfig ---

    @Test
    fun `patchVelocityConfig sets bind address and forwarding mode`() {
        val file = tempDir.resolve("velocity.toml")
        file.writeText(buildString {
            appendLine("bind = \"0.0.0.0:25577\"")
            appendLine("motd = \"A Velocity Proxy\"")
            appendLine("player-info-forwarding-mode = \"NONE\"")
            appendLine("online-mode = true")
        })

        patcher.patchVelocityConfig(tempDir, 25565, "modern")

        val lines = file.readLines()
        assertTrue(lines.contains("bind = \"0.0.0.0:25565\""), "Bind should be patched")
        assertTrue(lines.contains("player-info-forwarding-mode = \"modern\""), "Forwarding mode should be set")
        assertTrue(lines.contains("online-mode = true"), "online-mode should remain true")
        assertTrue(lines.contains("motd = \"A Velocity Proxy\""), "motd should be preserved")
    }

    @Test
    fun `patchVelocityConfig with legacy forwarding mode`() {
        val file = tempDir.resolve("velocity.toml")
        file.writeText(buildString {
            appendLine("bind = \"0.0.0.0:25577\"")
            appendLine("player-info-forwarding-mode = \"NONE\"")
        })

        patcher.patchVelocityConfig(tempDir, 25565, "legacy")

        val lines = file.readLines()
        assertTrue(lines.contains("player-info-forwarding-mode = \"legacy\""))
    }

    @Test
    fun `patchVelocityConfig does nothing if file missing`() {
        // Should not throw
        patcher.patchVelocityConfig(tempDir, 25565)
        assertFalse(tempDir.resolve("velocity.toml").exists())
    }

    // --- patchSpigotForBungeeCord ---

    @Test
    fun `patchSpigotForBungeeCord sets bungeecord true in existing spigot yml`() {
        val file = tempDir.resolve("spigot.yml")
        file.writeText(buildString {
            appendLine("settings:")
            appendLine("  bungeecord: false")
            appendLine("  timeout-time: 60")
        })

        patcher.patchSpigotForBungeeCord(tempDir)

        val lines = file.readLines()
        assertTrue(lines.any { it.trim() == "bungeecord: true" },
            "bungeecord should be set to true. Lines: $lines")
        assertTrue(lines.any { it.trim() == "timeout-time: 60" },
            "Other settings should be preserved")
    }

    @Test
    fun `patchSpigotForBungeeCord adds bungeecord if not present`() {
        val file = tempDir.resolve("spigot.yml")
        file.writeText(buildString {
            appendLine("settings:")
            appendLine("  timeout-time: 60")
        })

        patcher.patchSpigotForBungeeCord(tempDir)

        val text = file.readText()
        assertTrue(text.contains("bungeecord: true"), "bungeecord should be added")
    }

    @Test
    fun `patchSpigotForBungeeCord creates file if not exists`() {
        assertFalse(tempDir.resolve("spigot.yml").exists())

        patcher.patchSpigotForBungeeCord(tempDir)

        val file = tempDir.resolve("spigot.yml")
        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.contains("settings:"))
        assertTrue(text.contains("bungeecord: true"))
    }

    // --- patchPaperForVelocity ---

    @Test
    fun `patchPaperForVelocity copies forwarding secret`() {
        val velocityDir = tempDir.resolve("velocity-template").createDirectories()
        velocityDir.resolve("forwarding.secret").writeText("my-secret-key")

        val workDir = tempDir.resolve("server").createDirectories()

        patcher.patchPaperForVelocity(workDir, velocityDir)

        val secretFile = workDir.resolve("forwarding.secret")
        assertTrue(secretFile.exists(), "forwarding.secret should be copied")
        assertEquals("my-secret-key", secretFile.readText())
    }

    @Test
    fun `patchPaperForVelocity patches existing paper-global yml`() {
        val velocityDir = tempDir.resolve("velocity-template").createDirectories()
        velocityDir.resolve("forwarding.secret").writeText("test-secret")

        val workDir = tempDir.resolve("server").createDirectories()
        val configDir = workDir.resolve("config").createDirectories()
        configDir.resolve("paper-global.yml").writeText(buildString {
            appendLine("proxies:")
            appendLine("  velocity:")
            appendLine("    enabled: false")
            appendLine("    online-mode: false")
            appendLine("    secret: ''")
        })

        patcher.patchPaperForVelocity(workDir, velocityDir)

        val lines = configDir.resolve("paper-global.yml").readLines()
        assertTrue(lines.any { it.trim() == "enabled: true" }, "enabled should be true. Lines: $lines")
        assertTrue(lines.any { it.trim() == "online-mode: true" }, "online-mode should be true. Lines: $lines")
    }

    @Test
    fun `patchPaperForVelocity patches existing paper yml for older versions`() {
        val velocityDir = tempDir.resolve("velocity-template").createDirectories()
        velocityDir.resolve("forwarding.secret").writeText("old-secret")

        val workDir = tempDir.resolve("server").createDirectories()
        workDir.resolve("paper.yml").writeText(buildString {
            appendLine("settings:")
            appendLine("  velocity-support:")
            appendLine("    enabled: false")
            appendLine("    online-mode: false")
            appendLine("    secret: ''")
        })

        patcher.patchPaperForVelocity(workDir, velocityDir)

        val lines = workDir.resolve("paper.yml").readLines()
        assertTrue(lines.any { it.trim() == "enabled: true" }, "enabled should be true")
        assertTrue(lines.any { it.trim() == "online-mode: true" }, "online-mode should be true")
        assertTrue(lines.any { it.trim() == "secret: 'old-secret'" }, "secret should be patched. Lines: $lines")
    }

    @Test
    fun `patchPaperForVelocity creates both config files when none exist`() {
        val velocityDir = tempDir.resolve("velocity-template").createDirectories()
        velocityDir.resolve("forwarding.secret").writeText("new-secret")

        val workDir = tempDir.resolve("server").createDirectories()

        patcher.patchPaperForVelocity(workDir, velocityDir)

        // paper.yml should be created
        val paperYml = workDir.resolve("paper.yml")
        assertTrue(paperYml.exists(), "paper.yml should be created")
        val paperText = paperYml.readText()
        assertTrue(paperText.contains("velocity-support:"))
        assertTrue(paperText.contains("enabled: true"))
        assertTrue(paperText.contains("secret: 'new-secret'"))

        // config/paper-global.yml should be created
        val paperGlobal = workDir.resolve("config/paper-global.yml")
        assertTrue(paperGlobal.exists(), "config/paper-global.yml should be created")
        val globalText = paperGlobal.readText()
        assertTrue(globalText.contains("velocity:"))
        assertTrue(globalText.contains("enabled: true"))
        assertTrue(globalText.contains("secret: 'new-secret'"))
    }

    @Test
    fun `patchPaperForVelocity does not overwrite existing forwarding secret`() {
        val velocityDir = tempDir.resolve("velocity-template").createDirectories()
        velocityDir.resolve("forwarding.secret").writeText("new-secret")

        val workDir = tempDir.resolve("server").createDirectories()
        workDir.resolve("forwarding.secret").writeText("existing-secret")

        patcher.patchPaperForVelocity(workDir, velocityDir)

        assertEquals("existing-secret", workDir.resolve("forwarding.secret").readText(),
            "Existing forwarding.secret should not be overwritten")
    }
}
