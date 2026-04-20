package dev.nimbuspowered.nimbus.module

import io.ktor.server.routing.Route
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the [NimbusModule] + [ModuleContext] contracts that modules rely on —
 * lifecycle ordering and the default implementations surfaced by the interfaces
 * themselves. The concrete `ModuleContextImpl` lives in nimbus-core, so we
 * don't claim to test that here; per-module test suites that depend on core
 * cover real wiring.
 */
class NimbusModuleContractTest {

    private class DummyModule : NimbusModule {
        override val id = "dummy"
        override val name = "Dummy"
        override val version = "1.0.0"
        override val description = "test"
        val order = mutableListOf<String>()
        override suspend fun init(context: ModuleContext) { order += "init" }
        override suspend fun enable() { order += "enable" }
        override fun disable() { order += "disable" }
    }

    private class MinimalContext(override val baseDir: Path) : ModuleContext {
        override val scope: CoroutineScope = mockk(relaxed = true)
        override val templatesDir: Path = baseDir.resolve("templates")
        override val database: Database = mockk(relaxed = true)
        override fun moduleConfigDir(moduleId: String): Path =
            baseDir.resolve("modules/$moduleId").also { Files.createDirectories(it) }
        override fun registerCommand(command: ModuleCommand) {}
        override fun unregisterCommand(name: String) {}
        override fun registerCompleter(commandName: String, completer: (args: List<String>, prefix: String) -> List<String>) {}
        override fun registerRoutes(block: Route.() -> Unit, auth: AuthLevel) {}
        override fun registerPluginDeployment(deployment: PluginDeployment) {}
        override fun registerEventFormatter(eventType: String, formatter: (data: Map<String, String>) -> String) {}
        override fun <T : Any> getService(type: Class<T>): T? = null
    }

    @Test
    fun `lifecycle is init then enable then disable`(@TempDir tmp: Path) = runBlocking {
        val m = DummyModule()
        val ctx = MinimalContext(tmp)
        m.init(ctx)
        m.enable()
        m.disable()
        assertEquals(listOf("init", "enable", "disable"), m.order)
    }

    @Test
    fun `dashboardConfig defaults to null`() {
        assertNull(DummyModule().dashboardConfig)
    }

    @Test
    fun `getServiceByClassName default returns null`(@TempDir tmp: Path) {
        // The default impl lives on the interface itself; modules that don't
        // override it should always get null back.
        val ctx = MinimalContext(tmp)
        assertNull(ctx.getServiceByClassName("anything"))
    }

    @Test
    fun `registerService and registerMigrations defaults are no-ops`(@TempDir tmp: Path) {
        // Both have default implementations that do nothing — calling them on a
        // bare context must not throw.
        val ctx = MinimalContext(tmp)
        ctx.registerService(String::class.java, "x")
        ctx.registerMigrations(emptyList())
    }

    @Test
    fun `service reified helper resolves to getService`(@TempDir tmp: Path) {
        val ctx = MinimalContext(tmp)
        val resolved: String? = ctx.service()
        assertNull(resolved)
        assertTrue(ctx.baseDir.startsWith(tmp))
    }
}
