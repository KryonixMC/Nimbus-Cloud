package dev.nimbuspowered.nimbus.module.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleCommandTest {

    private class BasicCmd : ModuleCommand {
        override val name = "basic"
        override val description = "d"
        override val usage = "/basic"
        override suspend fun execute(args: List<String>) {}
    }

    private class TypedCmd : ModuleCommand {
        override val name = "typed"
        override val description = "d"
        override val usage = "/typed"
        var lastOutput: String? = null
        override suspend fun execute(args: List<String>) {}
        override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
            lastOutput = args.joinToString(" ")
            output.info(lastOutput!!)
            return true
        }
    }

    @Test
    fun `default permission is empty and subcommandMeta empty`() {
        val cmd = BasicCmd()
        assertEquals("", cmd.permission)
        assertTrue(cmd.subcommandMeta.isEmpty())
    }

    @Test
    fun `default typed execute returns false`() = runBlocking {
        val cmd = BasicCmd()
        val out = CollectingOutput()
        assertFalse(cmd.execute(listOf("a"), out))
    }

    @Test
    fun `caller-aware execute delegates to typed variant by default`() = runBlocking {
        val cmd = TypedCmd()
        val out = CollectingOutput()
        val handled = cmd.execute(listOf("hello", "world"), out, caller = null)
        assertTrue(handled)
        assertEquals("hello world", cmd.lastOutput)
        assertEquals(listOf("INFO:hello world"), out.lines)
    }

    private class CollectingOutput : CommandOutput {
        val lines = mutableListOf<String>()
        override fun header(text: String) { lines += "HEADER:$text" }
        override fun info(text: String) { lines += "INFO:$text" }
        override fun success(text: String) { lines += "SUCCESS:$text" }
        override fun error(text: String) { lines += "ERROR:$text" }
        override fun item(text: String) { lines += "ITEM:$text" }
        override fun text(text: String) { lines += "TEXT:$text" }
    }
}
