package dev.kryonix.nimbus.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

class ConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    // --- NimbusConfig tests ---

    @Test
    fun `loadNimbusConfig parses valid nimbus toml`() {
        val tomlFile = tempDir.resolve("nimbus.toml")
        tomlFile.writeText(
            """
            [network]
            name = "MyNetwork"
            bind = "192.168.1.1"

            [controller]
            max_memory = "16G"
            max_services = 50
            heartbeat_interval = 10000

            [console]
            colored = false
            log_events = false
            history_file = ".my_history"

            [paths]
            templates = "my_templates"
            services = "my_services"
            logs = "my_logs"

            [api]
            enabled = false
            bind = "0.0.0.0"
            port = 9090
            token = "secret123"
            allowed_origins = ["http://localhost:3000"]

            [java]
            java_17 = "/usr/lib/jvm/java-17/bin/java"
            java_21 = "/usr/lib/jvm/java-21/bin/java"
            """.trimIndent()
        )

        val config = ConfigLoader.loadNimbusConfig(tomlFile)

        assertEquals("MyNetwork", config.network.name)
        assertEquals("192.168.1.1", config.network.bind)
        assertEquals("16G", config.controller.maxMemory)
        assertEquals(50, config.controller.maxServices)
        assertEquals(10000L, config.controller.heartbeatInterval)
        assertFalse(config.console.colored)
        assertFalse(config.console.logEvents)
        assertEquals(".my_history", config.console.historyFile)
        assertEquals("my_templates", config.paths.templates)
        assertEquals("my_services", config.paths.services)
        assertEquals("my_logs", config.paths.logs)
        assertFalse(config.api.enabled)
        assertEquals("0.0.0.0", config.api.bind)
        assertEquals(9090, config.api.port)
        assertEquals("secret123", config.api.token)
        assertEquals(listOf("http://localhost:3000"), config.api.allowedOrigins)
        assertEquals("/usr/lib/jvm/java-17/bin/java", config.java.java17)
        assertEquals("/usr/lib/jvm/java-21/bin/java", config.java.java21)
    }

    @Test
    fun `loadNimbusConfig with minimal toml uses defaults`() {
        val tomlFile = tempDir.resolve("nimbus.toml")
        tomlFile.writeText(
            """
            [network]
            name = "Nimbus"
            """.trimIndent()
        )

        val config = ConfigLoader.loadNimbusConfig(tomlFile)

        assertEquals("Nimbus", config.network.name)
        assertEquals("0.0.0.0", config.network.bind)
        assertEquals("10G", config.controller.maxMemory)
        assertEquals(20, config.controller.maxServices)
        assertTrue(config.console.colored)
        assertTrue(config.api.enabled)
        assertEquals(8080, config.api.port)
    }

    @Test
    fun `loadNimbusConfig with missing file returns defaults`() {
        val missing = tempDir.resolve("missing.toml")
        val config = ConfigLoader.loadNimbusConfig(missing)

        assertEquals("Nimbus", config.network.name)
        assertEquals("0.0.0.0", config.network.bind)
        assertEquals(20, config.controller.maxServices)
    }

    @Test
    fun `loadNimbusConfig with malformed toml throws ConfigException`() {
        val tomlFile = tempDir.resolve("bad.toml")
        tomlFile.writeText("this is not [valid toml }{")

        assertThrows(ConfigException::class.java) {
            ConfigLoader.loadNimbusConfig(tomlFile)
        }
    }

    // --- GroupConfig tests ---

    private fun validGroupToml(
        name: String = "Lobby",
        type: String = "DYNAMIC",
        software: String = "PAPER",
        version: String = "1.21.4",
        memory: String = "1G",
        maxPlayers: Int = 50,
        minInstances: Int = 1,
        maxInstances: Int = 4,
        scaleThreshold: Double = 0.8,
        template: String = "lobby"
    ): String = """
        [group]
        name = "$name"
        type = "$type"
        template = "$template"
        software = "$software"
        version = "$version"

        [group.resources]
        memory = "$memory"
        max_players = $maxPlayers

        [group.scaling]
        min_instances = $minInstances
        max_instances = $maxInstances
        scale_threshold = $scaleThreshold

        [group.lifecycle]
        stop_on_empty = false
        restart_on_crash = true
        max_restarts = 5

        [group.jvm]
        optimize = true
    """.trimIndent()

    @Test
    fun `loadGroupConfigs parses all toml files in directory`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("lobby.toml").writeText(validGroupToml(name = "Lobby"))
        groupsDir.resolve("bedwars.toml").writeText(validGroupToml(name = "BedWars", template = "bedwars"))

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)

        assertEquals(2, configs.size)
        val names = configs.map { it.group.name }.toSet()
        assertTrue(names.contains("Lobby"))
        assertTrue(names.contains("BedWars"))
    }

    @Test
    fun `loadGroupConfigs skips non-toml files`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("lobby.toml").writeText(validGroupToml(name = "Lobby"))
        groupsDir.resolve("readme.txt").writeText("This is not a config file")
        groupsDir.resolve("notes.md").writeText("# Notes")

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)

        assertEquals(1, configs.size)
        assertEquals("Lobby", configs[0].group.name)
    }

    @Test
    fun `reloadGroupConfigs returns fresh configs`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("lobby.toml").writeText(validGroupToml(name = "Lobby"))

        val first = ConfigLoader.reloadGroupConfigs(groupsDir)
        assertEquals(1, first.size)

        // Add another config file
        groupsDir.resolve("bedwars.toml").writeText(validGroupToml(name = "BedWars", template = "bedwars"))

        val second = ConfigLoader.reloadGroupConfigs(groupsDir)
        assertEquals(2, second.size)
    }

    @Test
    fun `empty groups directory returns empty list`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `non-existent groups directory returns empty list`() {
        val missing = tempDir.resolve("no-such-dir")

        val configs = ConfigLoader.loadGroupConfigs(missing)
        assertTrue(configs.isEmpty())
    }

    // --- Validation tests ---

    @Test
    fun `validation rejects blank group name`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("blank.toml").writeText(validGroupToml(name = ""))

        // Blank name is caught by validation, group is skipped (logged error)
        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects minInstances greater than maxInstances`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", minInstances = 10, maxInstances = 2)
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects scaleThreshold greater than 1`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", scaleThreshold = 1.5)
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects scaleThreshold less than 0`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", scaleThreshold = -0.5)
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects maxPlayers less than 1`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", maxPlayers = 0)
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects invalid memory format without suffix`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", memory = "1024")
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation accepts valid memory formats`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("a.toml").writeText(validGroupToml(name = "ServerA", memory = "512M", template = "a"))
        groupsDir.resolve("b.toml").writeText(validGroupToml(name = "ServerB", memory = "2G", template = "b"))

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertEquals(2, configs.size)
    }

    @Test
    fun `malformed toml content is skipped`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("good.toml").writeText(validGroupToml(name = "Good"))
        groupsDir.resolve("bad.toml").writeText("not valid [toml content }{")

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertEquals(1, configs.size)
        assertEquals("Good", configs[0].group.name)
    }

    @Test
    fun `validation rejects invalid version format`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", version = "latest")
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `validation rejects blank template`() {
        val groupsDir = tempDir.resolve("groups").createDirectory()
        groupsDir.resolve("bad.toml").writeText(
            validGroupToml(name = "Bad", template = "")
        )

        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        assertTrue(configs.isEmpty())
    }
}
