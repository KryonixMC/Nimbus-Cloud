package dev.nimbuspowered.nimbus.velocity

import dev.nimbuspowered.nimbus.config.*
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.group.ServerGroup
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VelocityConfigGenTest {

    private lateinit var registry: ServiceRegistry
    private lateinit var groupManager: GroupManager
    private lateinit var configGen: VelocityConfigGen

    @TempDir
    lateinit var tempDir: Path

    private val velocityTomlTemplate = """
        [version]
        version = "3.3.0"

        [servers]
        lobby = "127.0.0.1:25566"
        try = ["lobby"]

        [forced-hosts]
        "mc.example.com" = ["lobby"]

        [advanced]
        compression-threshold = 256
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        registry = mockk()
        groupManager = mockk()
        configGen = VelocityConfigGen(registry, groupManager)
    }

    private fun createService(
        name: String,
        groupName: String,
        port: Int,
        state: ServiceState = ServiceState.READY
    ): Service {
        val service = Service(
            name = name,
            groupName = groupName,
            port = port,
            initialState = ServiceState.PREPARING,
            workingDirectory = tempDir
        )
        // Transition through valid states to reach desired state
        when (state) {
            ServiceState.STARTING -> {
                service.transitionTo(ServiceState.STARTING)
            }
            ServiceState.READY -> {
                service.transitionTo(ServiceState.STARTING)
                service.transitionTo(ServiceState.READY)
            }
            ServiceState.STOPPED -> {
                service.transitionTo(ServiceState.STOPPED)
            }
            else -> {}
        }
        return service
    }

    private fun mockGroupWithSoftware(groupName: String, software: ServerSoftware) {
        val groupConfig = GroupConfig(
            group = GroupDefinition(name = groupName, software = software)
        )
        every { groupManager.getGroup(groupName) } returns ServerGroup(groupConfig)
    }

    private fun writeVelocityToml(): Path {
        val configFile = tempDir.resolve("velocity.toml")
        configFile.writeText(velocityTomlTemplate)
        return configFile
    }

    @Test
    fun `regenerateServerList adds READY backend services to servers section`() {
        writeVelocityToml()

        val lobby1 = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)
        val lobby2 = createService("Lobby-2", "Lobby", 30002, ServiceState.READY)

        every { registry.getAll() } returns listOf(lobby1, lobby2)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("""Lobby-1 = "127.0.0.1:30001""""))
        assertTrue(result.contains("""Lobby-2 = "127.0.0.1:30002""""))
    }

    @Test
    fun `only READY services are included, STARTING and STOPPED excluded`() {
        writeVelocityToml()

        val ready = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)
        val starting = createService("Lobby-2", "Lobby", 30002, ServiceState.STARTING)
        val stopped = createService("Lobby-3", "Lobby", 30003, ServiceState.STOPPED)

        every { registry.getAll() } returns listOf(ready, starting, stopped)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("""Lobby-1 = "127.0.0.1:30001""""))
        assertFalse(result.contains("Lobby-2"))
        assertFalse(result.contains("Lobby-3"))
    }

    @Test
    fun `lobby services appear in try list`() {
        writeVelocityToml()

        val lobby1 = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)
        val bedwars1 = createService("BedWars-1", "BedWars", 30010, ServiceState.READY)

        every { registry.getAll() } returns listOf(lobby1, bedwars1)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)
        mockGroupWithSoftware("BedWars", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("""try = ["Lobby-1"]"""))
        // BedWars should NOT be in the try list
        assertFalse(result.contains(""""BedWars-1"""".let { it in result.substringAfter("try = [").substringBefore("]") }.toString()))
        // Verify BedWars is in servers section but not in try
        val tryLine = result.lines().first { it.trim().startsWith("try = ") }
        assertTrue(tryLine.contains("Lobby-1"))
        assertFalse(tryLine.contains("BedWars-1"))
    }

    @Test
    fun `replaceTOMLSection replaces existing section content`() {
        val original = """
            [version]
            version = "3.3.0"

            [servers]
            old-server = "127.0.0.1:25566"
            try = ["old-server"]

            [advanced]
            compression-threshold = 256
        """.trimIndent()

        val newSection = "[servers]\nnew-server = \"127.0.0.1:30001\"\ntry = [\"new-server\"]\n"

        val result = configGen.replaceTOMLSection(original, "servers", newSection)

        assertTrue(result.contains("new-server"))
        assertFalse(result.contains("old-server"))
        assertTrue(result.contains("[advanced]"))
        assertTrue(result.contains("[version]"))
    }

    @Test
    fun `no READY services produces empty servers block with empty try`() {
        writeVelocityToml()

        val starting = createService("Lobby-1", "Lobby", 30001, ServiceState.STARTING)

        every { registry.getAll() } returns listOf(starting)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("[servers]"))
        assertTrue(result.contains("try = []"))
    }

    @Test
    fun `multiple groups with different states`() {
        writeVelocityToml()

        val lobby1 = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)
        val lobby2 = createService("Lobby-2", "Lobby", 30002, ServiceState.STARTING)
        val bw1 = createService("BedWars-1", "BedWars", 30010, ServiceState.READY)
        val bw2 = createService("BedWars-2", "BedWars", 30011, ServiceState.READY)
        val sw1 = createService("SkyWars-1", "SkyWars", 30020, ServiceState.STOPPED)

        every { registry.getAll() } returns listOf(lobby1, lobby2, bw1, bw2, sw1)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)
        mockGroupWithSoftware("BedWars", ServerSoftware.PAPER)
        mockGroupWithSoftware("SkyWars", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        // Only READY services
        assertTrue(result.contains("""Lobby-1 = "127.0.0.1:30001""""))
        assertTrue(result.contains("""BedWars-1 = "127.0.0.1:30010""""))
        assertTrue(result.contains("""BedWars-2 = "127.0.0.1:30011""""))
        assertFalse(result.contains("Lobby-2"))
        assertFalse(result.contains("SkyWars-1"))

        // Try list should contain Lobby-1 (lobby group)
        val tryLine = result.lines().first { it.trim().startsWith("try = ") }
        assertTrue(tryLine.contains("Lobby-1"))
    }

    @Test
    fun `velocity services are excluded from backend list`() {
        writeVelocityToml()

        val proxy = createService("Proxy-1", "Proxy", 25565, ServiceState.READY)
        val lobby = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)

        every { registry.getAll() } returns listOf(proxy, lobby)
        mockGroupWithSoftware("Proxy", ServerSoftware.VELOCITY)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("""Lobby-1 = "127.0.0.1:30001""""))
        assertFalse(result.contains("Proxy-1"))
    }

    @Test
    fun `regenerateServerList does nothing when velocity toml missing`() {
        // No velocity.toml created — should just return without error
        every { registry.getAll() } returns emptyList()
        configGen.regenerateServerList(tempDir)
        // No exception thrown = pass
    }

    @Test
    fun `setModdedSettings updates existing announce-forge at root level`() {
        val content = """
            announce-forge = false

            [advanced]
            compression-threshold = 256
            connection-timeout = 5000
            read-timeout = 30000
        """.trimIndent()

        val result = configGen.setModdedSettings(content, true)
        assertTrue(result.contains("announce-forge = true"))
        assertFalse(result.contains("announce-forge = false"))
        assertTrue(result.contains("connection-timeout = 10000"))
        assertTrue(result.contains("read-timeout = 60000"))
    }

    @Test
    fun `setModdedSettings inserts announce-forge before first section when missing`() {
        val content = """
            [advanced]
            compression-threshold = 256
        """.trimIndent()

        val result = configGen.setModdedSettings(content, true)
        assertTrue(result.contains("announce-forge = true"))
        // announce-forge should be before [advanced]
        assertTrue(result.indexOf("announce-forge") < result.indexOf("[advanced]"))
    }

    @Test
    fun `setModdedSettings disabled does not change timeouts`() {
        val content = """
            announce-forge = true

            [advanced]
            connection-timeout = 5000
            read-timeout = 30000
        """.trimIndent()

        val result = configGen.setModdedSettings(content, false)
        assertTrue(result.contains("announce-forge = false"))
        // Timeouts should remain unchanged
        assertTrue(result.contains("connection-timeout = 5000"))
        assertTrue(result.contains("read-timeout = 30000"))
    }

    @Test
    fun `setModdedSettings creates advanced section for timeouts when missing`() {
        val content = """
            announce-forge = false
        """.trimIndent()

        val result = configGen.setModdedSettings(content, true)
        assertTrue(result.contains("announce-forge = true"))
        assertTrue(result.contains("[advanced]"))
        assertTrue(result.contains("connection-timeout = 10000"))
        assertTrue(result.contains("read-timeout = 60000"))
    }

    @Test
    fun `modded backend enables announce-forge and increases timeouts`() {
        writeVelocityToml()

        val lobby = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)
        val modded = createService("Modded-1", "Modded", 30010, ServiceState.READY)

        every { registry.getAll() } returns listOf(lobby, modded)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)
        mockGroupWithSoftware("Modded", ServerSoftware.FORGE)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("announce-forge = true"))
        assertTrue(result.contains("connection-timeout = 10000"))
        assertTrue(result.contains("read-timeout = 60000"))
    }

    @Test
    fun `no modded backends sets announce-forge to false`() {
        writeVelocityToml()

        val lobby = createService("Lobby-1", "Lobby", 30001, ServiceState.READY)

        every { registry.getAll() } returns listOf(lobby)
        mockGroupWithSoftware("Lobby", ServerSoftware.PAPER)

        configGen.regenerateServerList(tempDir)

        val result = tempDir.resolve("velocity.toml").readText()
        assertTrue(result.contains("announce-forge = false"))
    }

    @Test
    fun `replaceTOMLSection appends when section not found`() {
        val original = """
            [version]
            version = "3.3.0"
        """.trimIndent()

        val newSection = "[servers]\nlobby = \"127.0.0.1:30001\"\ntry = [\"lobby\"]\n"

        val result = configGen.replaceTOMLSection(original, "servers", newSection)
        assertTrue(result.contains("[version]"))
        assertTrue(result.contains("[servers]"))
        assertTrue(result.contains("lobby"))
    }
}
