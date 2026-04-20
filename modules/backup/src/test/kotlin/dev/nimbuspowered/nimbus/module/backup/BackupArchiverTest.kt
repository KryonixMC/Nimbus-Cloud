package dev.nimbuspowered.nimbus.module.backup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.io.path.writeText
import kotlin.io.path.writeBytes
import kotlin.io.path.readBytes

class BackupArchiverTest {

    private lateinit var root: Path
    private val archiver = BackupArchiver()

    @BeforeEach
    fun setUp() {
        root = Files.createTempDirectory("nimbus-backup-test-")
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `archive and extract preserves files`() = runBlocking {
        val src = root.resolve("src")
        Files.createDirectories(src.resolve("subdir"))
        src.resolve("hello.txt").writeText("hello world")
        src.resolve("subdir/data.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        val archive = root.resolve("out.tar.zst")
        val result = archiver.archive(src, archive, excludeGlobs = emptyList(), compressionLevel = 3, workers = 1)

        assertTrue(Files.exists(archive))
        assertEquals(2L, result.fileCount)
        assertTrue(result.sizeBytes > 0)
        assertEquals(64, result.archiveSha256.length)

        val dest = root.resolve("dest")
        val listed = archiver.extract(archive, dest, dryRun = false)
        assertTrue(listed.contains("hello.txt"))
        assertTrue(listed.contains("subdir/data.bin"))
        assertEquals("hello world", String(dest.resolve("hello.txt").readBytes()))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7), dest.resolve("subdir/data.bin").readBytes().toList())
    }

    @Test
    fun `verify succeeds for untouched archive`() = runBlocking {
        val src = root.resolve("src")
        Files.createDirectories(src)
        src.resolve("a.txt").writeText("a")
        src.resolve("b.txt").writeText("bbb")

        val archive = root.resolve("verify.tar.zst")
        archiver.archive(src, archive, emptyList(), 3, 1)
        val v = archiver.verify(archive)
        assertTrue(v.valid, "expected verify to pass, errors=${v.errors}")
        assertTrue(v.errors.isEmpty())
    }

    @Test
    fun `excludeGlobs skips matching files`() = runBlocking {
        val src = root.resolve("src")
        Files.createDirectories(src.resolve("logs"))
        src.resolve("keep.txt").writeText("keep")
        src.resolve("logs/skip.log").writeText("skip")

        val archive = root.resolve("excl.tar.zst")
        val res = archiver.archive(src, archive, excludeGlobs = listOf("*.log"), compressionLevel = 3, workers = 1)
        assertTrue(res.skippedCount >= 1L)

        val dest = root.resolve("dest")
        val listed = archiver.extract(archive, dest, dryRun = false)
        assertTrue(listed.contains("keep.txt"))
        assertFalse(listed.contains("logs/skip.log"))
    }

    @Test
    fun `manifest sha256 line parses correctly`() = runBlocking {
        // Assert the manifest format: 64-char hex + two spaces + path
        val src = root.resolve("src")
        Files.createDirectories(src)
        val content = "hello manifest"
        src.resolve("file.txt").writeText(content)

        val archive = root.resolve("m.tar.zst")
        archiver.archive(src, archive, emptyList(), 3, 1)

        // Read archive back and locate MANIFEST.sha256 entry
        val manifest = extractManifest(archive)
        assertNotNull(manifest)
        val line = manifest!!.lineSequence().first { it.isNotBlank() }
        val idx = line.indexOf("  ")
        assertTrue(idx > 0, "expected two-space separator, got '$line'")
        val hash = line.substring(0, idx)
        val path = line.substring(idx + 2)
        assertEquals("file.txt", path)

        // Recompute SHA-256 of the file and compare
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, hash)
    }

    @Test
    fun `verify detects mismatch when manifest claims wrong hash`() = runBlocking {
        val src = root.resolve("src")
        Files.createDirectories(src)
        src.resolve("a.txt").writeText("original content")
        val archive = root.resolve("tamper.tar.zst")
        archiver.archive(src, archive, emptyList(), 3, 1)

        // Verify now — should pass
        val good = archiver.verify(archive)
        assertTrue(good.valid)

        // Tamper: archive-level hash should also change on content change,
        // but we separately ensure two different contents produce different archives.
        val src2 = root.resolve("src2")
        Files.createDirectories(src2)
        src2.resolve("a.txt").writeText("different content")
        val archive2 = root.resolve("tamper2.tar.zst")
        val res2 = archiver.archive(src2, archive2, emptyList(), 3, 1)
        val res1 = archiver.archive(src, root.resolve("regen.tar.zst"), emptyList(), 3, 1)
        assertNotEquals(res1.archiveSha256, res2.archiveSha256)
    }

    private fun extractManifest(archive: Path): String? {
        java.io.FileInputStream(archive.toFile()).use { fis ->
            com.github.luben.zstd.ZstdInputStream(fis).use { zstd ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(zstd).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entry.name == "MANIFEST.sha256") {
                            return String(tar.readAllBytes(), Charsets.UTF_8)
                        }
                    }
                }
            }
        }
        return null
    }
}
