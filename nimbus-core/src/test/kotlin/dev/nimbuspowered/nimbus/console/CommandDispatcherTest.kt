package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.ModuleCommand
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommandDispatcherTest {

    private class FakeCommand(
        override val name: String,
        private val behavior: suspend (List<String>, CommandOutput) -> Boolean = { _, _ -> true }
    ) : ModuleCommand {
        override val description = ""
        override val usage = ""
        var lastArgs: List<String>? = null
        var executed = false
        override suspend fun execute(args: List<String>) {
            lastArgs = args
            executed = true
        }
        override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
            lastArgs = args
            executed = true
            return behavior(args, output)
        }
    }

    private class CapturingOutput : CommandOutput {
        val lines = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun header(text: String) { lines.add(text) }
        override fun info(text: String) { lines.add(text) }
        override fun success(text: String) { lines.add(text) }
        override fun error(text: String) { errors.add(text) }
        override fun item(text: String) { lines.add(text) }
        override fun text(text: String) { lines.add(text) }
    }

    @Test
    fun `register and dispatch calls execute with args split on whitespace`() = runBlocking {
        val d = CommandDispatcher()
        val cmd = FakeCommand("hello")
        d.register(cmd)
        val out = CapturingOutput()
        assertTrue(d.dispatch("hello  world  foo", out))
        assertTrue(cmd.executed)
        assertEquals(listOf("world", "foo"), cmd.lastArgs)
    }

    @Test
    fun `dispatch is case insensitive on command name`() = runBlocking {
        val d = CommandDispatcher()
        val cmd = FakeCommand("Services")
        d.register(cmd)
        d.dispatch("SERVICES", CapturingOutput())
        assertTrue(cmd.executed)
    }

    @Test
    fun `aliases - question mark maps to help, ver maps to version`() = runBlocking {
        val d = CommandDispatcher()
        val help = FakeCommand("help")
        val version = FakeCommand("version")
        d.register(help)
        d.register(version)
        d.dispatch("?", CapturingOutput())
        d.dispatch("ver", CapturingOutput())
        assertTrue(help.executed)
        assertTrue(version.executed)
    }

    @Test
    fun `unknown command emits error and returns false via output variant`() = runBlocking {
        val d = CommandDispatcher()
        val out = CapturingOutput()
        assertFalse(d.dispatch("doesnotexist", out))
        assertTrue(out.errors.any { it.contains("Unknown command", ignoreCase = true) })
    }

    @Test
    fun `empty input is a no-op returning true`() = runBlocking {
        val d = CommandDispatcher()
        assertTrue(d.dispatch("   ", CapturingOutput()))
    }

    @Test
    fun `unregister removes the command`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("foo"))
        assertNotNull(d.getCommand("foo"))
        d.unregister("foo")
        assertNull(d.getCommand("foo"))
    }

    @Test
    fun `complete empty buffer returns all command names`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("services"))
        d.register(FakeCommand("groups"))
        d.register(FakeCommand("help"))
        val out = d.complete("")
        assertTrue(out.containsAll(listOf("services", "groups", "help")))
    }

    @Test
    fun `complete with prefix filters command names`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("services"))
        d.register(FakeCommand("service-foo"))
        d.register(FakeCommand("groups"))
        val out = d.complete("ser")
        assertTrue(out.all { it.startsWith("ser") })
        assertTrue(out.contains("services"))
    }

    @Test
    fun `complete delegates to module-registered completer`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("foo"))
        d.registerCompleter("foo") { _, prefix ->
            listOf("alpha", "beta", "gamma").filter { it.startsWith(prefix) }
        }
        val out = d.complete("foo al")
        assertEquals(listOf("alpha"), out)
    }

    @Test
    fun `complete for 'cluster' subcommand returns fixed options`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("cluster"))
        val out = d.complete("cluster ")
        assertTrue(out.containsAll(listOf("status", "enable", "disable", "token")))
    }

    @Test
    fun `complete for 'lb strategy' returns known strategies`() {
        val d = CommandDispatcher()
        d.register(FakeCommand("lb"))
        val out = d.complete("lb strategy ")
        assertTrue(out.contains("least-players"))
        assertTrue(out.contains("round-robin"))
    }

    @Test
    fun `dispatch returns false from remote variant when command returns handled=false`() = runBlocking {
        val d = CommandDispatcher()
        d.register(FakeCommand("foo") { _, _ -> false })
        val out = CapturingOutput()
        assertFalse(d.dispatch("foo", out))
        assertTrue(out.errors.any { it.contains("does not support remote") })
    }

    @Test
    fun `dispatch swallows command exceptions and reports an error`() = runBlocking {
        val d = CommandDispatcher()
        val failing = object : ModuleCommand {
            override val name = "boom"
            override val description = ""
            override val usage = ""
            override suspend fun execute(args: List<String>) { throw RuntimeException("nope") }
            override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
                throw RuntimeException("remote nope")
            }
        }
        d.register(failing)
        val out = CapturingOutput()
        assertFalse(d.dispatch("boom", out))
        assertTrue(out.errors.any { it.contains("Error executing") })
    }
}
