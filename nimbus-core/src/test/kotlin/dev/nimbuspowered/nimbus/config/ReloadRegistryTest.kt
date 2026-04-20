package dev.nimbuspowered.nimbus.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadRegistryTest {

    @Test
    fun `every registered section appears in the report with the correct scope`() {
        val report = ReloadRegistry.buildReport(
            success = true, groupsLoaded = 3,
            appliedSections = setOf("groups"),
            message = "ok"
        )
        val groupsSection = report.sections.single { it.name == "groups" }
        assertEquals(ReloadScope.LIVE, groupsSection.scope)
        assertTrue(groupsSection.applied)

        val apiSection = report.sections.single { it.name == "api" }
        assertEquals(ReloadScope.REQUIRES_RESTART, apiSection.scope)
        assertFalse(apiSection.applied)

        val sandboxSection = report.sections.single { it.name == "sandbox" }
        assertEquals(ReloadScope.NEXT_SERVICE_PREPARE, sandboxSection.scope)
    }

    @Test
    fun `requiresRestartIfChanged lists exactly the REQUIRES_RESTART sections`() {
        val report = ReloadRegistry.buildReport(
            success = true, groupsLoaded = 0,
            appliedSections = emptySet(),
            message = ""
        )
        assertTrue("api" in report.requiresRestartIfChanged)
        assertTrue("database" in report.requiresRestartIfChanged)
        assertTrue("cluster" in report.requiresRestartIfChanged)
        // LIVE and NEXT_SERVICE_PREPARE sections must not appear here.
        assertFalse("groups" in report.requiresRestartIfChanged)
        assertFalse("sandbox" in report.requiresRestartIfChanged)
        assertFalse("modules.backup" in report.requiresRestartIfChanged)
    }

    @Test
    fun `legacy fields are populated for backwards compat`() {
        val ok = ReloadRegistry.buildReport(true, 7, setOf("groups"), "Reloaded 7 group config(s)")
        assertEquals(true, ok.success)
        assertEquals(7, ok.groupsLoaded)
        assertEquals("Reloaded 7 group config(s)", ok.message)

        val fail = ReloadRegistry.buildReport(false, 0, emptySet(), "parse error")
        assertFalse(fail.success)
        assertEquals(0, fail.groupsLoaded)
    }

    @Test
    fun `applied flag is driven by the appliedSections input`() {
        val report = ReloadRegistry.buildReport(
            success = true, groupsLoaded = 0,
            appliedSections = setOf("groups", "modules.syncproxy.motd"),
            message = ""
        )
        val appliedNames = report.sections.filter { it.applied }.map { it.name }.toSet()
        assertEquals(setOf("groups", "modules.syncproxy.motd"), appliedNames)
    }
}
