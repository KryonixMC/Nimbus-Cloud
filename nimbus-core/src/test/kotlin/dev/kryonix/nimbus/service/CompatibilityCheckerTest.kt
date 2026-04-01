package dev.kryonix.nimbus.service

import dev.kryonix.nimbus.config.*
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.group.ServerGroup
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompatibilityCheckerTest {

    private lateinit var groupManager: GroupManager
    private lateinit var config: NimbusConfig
    private lateinit var javaResolver: JavaResolver
    private lateinit var checker: CompatibilityChecker

    @BeforeEach
    fun setUp() {
        groupManager = mockk()
        config = NimbusConfig(paths = PathsConfig(templates = "/tmp/nimbus-test-templates"))
        javaResolver = mockk()
        checker = CompatibilityChecker(groupManager, config, javaResolver)

        // Default: no detected java versions
        every { javaResolver.getDetectedVersions() } returns emptyMap()
    }

    private fun makeGroup(name: String, software: ServerSoftware, version: String): ServerGroup {
        val groupConfig = GroupConfig(
            group = GroupDefinition(name = name, software = software, version = version, template = name)
        )
        return ServerGroup(groupConfig)
    }

    // ── isLegacyVersion ───────────────────────────────────────

    @Test
    fun `isLegacyVersion 1_12_2 returns true`() {
        assertTrue(checker.isLegacyVersion("1.12.2"))
    }

    @Test
    fun `isLegacyVersion 1_12 returns true`() {
        assertTrue(checker.isLegacyVersion("1.12"))
    }

    @Test
    fun `isLegacyVersion 1_7_10 returns true`() {
        assertTrue(checker.isLegacyVersion("1.7.10"))
    }

    @Test
    fun `isLegacyVersion 1_13 returns false`() {
        assertFalse(checker.isLegacyVersion("1.13"))
    }

    @Test
    fun `isLegacyVersion 1_13_2 returns false`() {
        assertFalse(checker.isLegacyVersion("1.13.2"))
    }

    @Test
    fun `isLegacyVersion 1_21_4 returns false`() {
        assertFalse(checker.isLegacyVersion("1.21.4"))
    }

    @Test
    fun `isLegacyVersion new versioning scheme 26_1 returns false`() {
        assertFalse(checker.isLegacyVersion("26.1"))
    }

    // ── noguiFlag ─────────────────────────────────────────────

    @Test
    fun `noguiFlag 1_21_4 returns double-dash nogui`() {
        assertEquals("--nogui", checker.noguiFlag("1.21.4"))
    }

    @Test
    fun `noguiFlag 1_14 returns double-dash nogui`() {
        assertEquals("--nogui", checker.noguiFlag("1.14"))
    }

    @Test
    fun `noguiFlag 1_13 returns nogui without dashes`() {
        assertEquals("nogui", checker.noguiFlag("1.13"))
    }

    @Test
    fun `noguiFlag 1_7_10 returns nogui without dashes`() {
        assertEquals("nogui", checker.noguiFlag("1.7.10"))
    }

    @Test
    fun `noguiFlag 1_12_2 returns nogui without dashes`() {
        assertEquals("nogui", checker.noguiFlag("1.12.2"))
    }

    @Test
    fun `noguiFlag pre-1_7 returns null`() {
        assertNull(checker.noguiFlag("1.6.4"))
    }

    @Test
    fun `noguiFlag 1_5_2 returns null`() {
        assertNull(checker.noguiFlag("1.5.2"))
    }

    @Test
    fun `noguiFlag new versioning scheme returns double-dash nogui`() {
        assertEquals("--nogui", checker.noguiFlag("26.1"))
    }

    // ── determineForwardingMode ───────────────────────────────

    @Test
    fun `determineForwardingMode all modern returns modern`() {
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        val bedwars = makeGroup("BedWars", ServerSoftware.PAPER, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(lobby, bedwars)

        assertEquals("modern", checker.determineForwardingMode())
    }

    @Test
    fun `determineForwardingMode one legacy returns legacy`() {
        val modern = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        val legacy = makeGroup("OldServer", ServerSoftware.PAPER, "1.12.2")
        every { groupManager.getAllGroups() } returns listOf(modern, legacy)

        assertEquals("legacy", checker.determineForwardingMode())
    }

    @Test
    fun `determineForwardingMode mixed versions based on oldest`() {
        val modern1 = makeGroup("Lobby", ServerSoftware.PAPER, "1.20.4")
        val modern2 = makeGroup("BedWars", ServerSoftware.PAPER, "1.19.4")
        val legacy = makeGroup("Classic", ServerSoftware.PAPER, "1.8.8")
        every { groupManager.getAllGroups() } returns listOf(modern1, modern2, legacy)

        assertEquals("legacy", checker.determineForwardingMode())
    }

    @Test
    fun `determineForwardingMode velocity groups are excluded from version check`() {
        val proxy = makeGroup("Proxy", ServerSoftware.VELOCITY, "3.3.0")
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(proxy, lobby)

        assertEquals("modern", checker.determineForwardingMode())
    }

    @Test
    fun `determineForwardingMode no groups returns modern`() {
        every { groupManager.getAllGroups() } returns emptyList()
        assertEquals("modern", checker.determineForwardingMode())
    }

    // ── checkCompatibility ────────────────────────────────────

    @Test
    fun `checkCompatibility legacy and Fabric produces ERROR warning`() {
        val legacy = makeGroup("OldServer", ServerSoftware.PAPER, "1.12.2")
        val fabric = makeGroup("FabricServer", ServerSoftware.FABRIC, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(legacy, fabric)
        every { javaResolver.requiredJavaVersion("1.12.2", ServerSoftware.PAPER) } returns 8
        every { javaResolver.maxJavaVersion("1.12.2", ServerSoftware.PAPER) } returns 11
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.FABRIC) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.FABRIC) } returns null

        val warnings = checker.checkCompatibility()

        val errorWarnings = warnings.filter { it.level == CompatibilityChecker.CompatWarning.Level.ERROR }
        assertTrue(errorWarnings.isNotEmpty())
        assertTrue(errorWarnings.any { it.title.contains("Forwarding mode conflict") })
    }

    @Test
    fun `checkCompatibility mixed major versions produces INFO`() {
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        val bedwars = makeGroup("BedWars", ServerSoftware.PAPER, "1.20.4")
        every { groupManager.getAllGroups() } returns listOf(lobby, bedwars)
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.PAPER) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.PAPER) } returns null
        every { javaResolver.requiredJavaVersion("1.20.4", ServerSoftware.PAPER) } returns 17
        every { javaResolver.maxJavaVersion("1.20.4", ServerSoftware.PAPER) } returns null

        val warnings = checker.checkCompatibility()

        val infoWarnings = warnings.filter { it.level == CompatibilityChecker.CompatWarning.Level.INFO }
        assertTrue(infoWarnings.any { it.title.contains("Multiple MC versions") })
    }

    @Test
    fun `checkCompatibility no groups returns empty warnings list`() {
        every { groupManager.getAllGroups() } returns emptyList()

        val warnings = checker.checkCompatibility()
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `checkCompatibility Java version mismatch produces WARN`() {
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(lobby)
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.PAPER) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.PAPER) } returns null
        // Detected Java 17 only, but need 21
        every { javaResolver.getDetectedVersions() } returns mapOf(17 to "/usr/lib/jvm/java-17/bin/java")

        val warnings = checker.checkCompatibility()

        val warnWarnings = warnings.filter { it.level == CompatibilityChecker.CompatWarning.Level.WARN }
        assertTrue(warnWarnings.any { it.title.contains("Missing Java") })
    }

    @Test
    fun `checkCompatibility compatible Java does not produce missing Java warning`() {
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(lobby)
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.PAPER) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.PAPER) } returns null
        every { javaResolver.getDetectedVersions() } returns mapOf(21 to "/usr/lib/jvm/java-21/bin/java")

        val warnings = checker.checkCompatibility()

        val missingJava = warnings.filter {
            it.level == CompatibilityChecker.CompatWarning.Level.WARN && it.title.contains("Missing Java")
        }
        assertTrue(missingJava.isEmpty())
    }

    @Test
    fun `checkCompatibility legacy plus NeoForge produces ERROR`() {
        val legacy = makeGroup("OldServer", ServerSoftware.PAPER, "1.8.8")
        val neoforge = makeGroup("ModServer", ServerSoftware.NEOFORGE, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(legacy, neoforge)
        every { javaResolver.requiredJavaVersion("1.8.8", ServerSoftware.PAPER) } returns 8
        every { javaResolver.maxJavaVersion("1.8.8", ServerSoftware.PAPER) } returns 11
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.NEOFORGE) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.NEOFORGE) } returns null

        val warnings = checker.checkCompatibility()

        val errors = warnings.filter { it.level == CompatibilityChecker.CompatWarning.Level.ERROR }
        assertTrue(errors.any { it.title.contains("Forwarding mode conflict") })
    }

    @Test
    fun `checkCompatibility all same version no multi-version info`() {
        val lobby = makeGroup("Lobby", ServerSoftware.PAPER, "1.21.4")
        val bedwars = makeGroup("BedWars", ServerSoftware.PAPER, "1.21.4")
        every { groupManager.getAllGroups() } returns listOf(lobby, bedwars)
        every { javaResolver.requiredJavaVersion("1.21.4", ServerSoftware.PAPER) } returns 21
        every { javaResolver.maxJavaVersion("1.21.4", ServerSoftware.PAPER) } returns null

        val warnings = checker.checkCompatibility()

        val multiVersion = warnings.filter { it.title.contains("Multiple MC versions") }
        assertTrue(multiVersion.isEmpty())
    }

    @Test
    fun `checkCompatibility detected Java shows INFO`() {
        every { groupManager.getAllGroups() } returns emptyList()
        every { javaResolver.getDetectedVersions() } returns mapOf(
            21 to "/usr/lib/jvm/java-21/bin/java",
            17 to "/usr/lib/jvm/java-17/bin/java"
        )

        val warnings = checker.checkCompatibility()

        val javaInfo = warnings.filter {
            it.level == CompatibilityChecker.CompatWarning.Level.INFO && it.title.contains("Java")
        }
        assertTrue(javaInfo.isNotEmpty())
    }
}
