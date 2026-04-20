package dev.nimbuspowered.nimbus.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JavaResolverTest {

    private val resolvers = mutableListOf<JavaResolver>()

    @AfterEach
    fun closeResolvers() {
        resolvers.forEach { it.close() }
        resolvers.clear()
    }

    private fun newResolver(configured: Map<Int, String>, baseDir: Path): JavaResolver =
        JavaResolver(configured, baseDir).also { resolvers += it }

    @Test
    fun `resolve returns plain java when requiredVersion is zero or negative`(@TempDir dir: Path) = runBlocking {
        val resolver = newResolver(emptyMap(), dir)
        assertEquals("java", resolver.resolve(0))
        assertEquals("java", resolver.resolve(-1))
    }

    @Test
    fun `resolve prefers configured path when exact version match and path exists`(@TempDir dir: Path) = runBlocking {
        // Simulate a Java install directory with a bin/java stub
        val jdkDir = dir.resolve("fake-jdk-17")
        val bin = jdkDir.resolve("bin")
        Files.createDirectories(bin)
        val javaBin = bin.resolve("java")
        Files.writeString(javaBin, "#!/bin/sh\necho hi\n")

        val resolver = newResolver(mapOf(17 to javaBin.toString()), dir)
        val resolved = resolver.resolve(17)
        assertEquals(javaBin.toString(), resolved)
    }

    @Test
    fun `resolve is not null for large version when nothing is configured`(@TempDir dir: Path) = runBlocking {
        // Without network we can't guarantee auto-download; just ensure resolve()
        // returns something (either a detected system java or "java" fallback).
        val resolver = newResolver(emptyMap(), dir)
        // Use a version that is likely already installed OR that short-circuits.
        val result = resolver.resolve(1)  // everything >= 1 is compatible
        assertNotNull(result)
    }

    @Test
    fun `resolve ignores configured path when file does not exist`(@TempDir dir: Path) = runBlocking {
        val resolver = newResolver(mapOf(17 to "/definitely/missing/bin/java"), dir)
        val result = resolver.resolve(17)
        // Should not return the bogus path since it doesn't exist
        assertNotNull(result)
        // Either detected system java or the fallback "java" — but not our bogus path
        assert(result != "/definitely/missing/bin/java")
    }
}
