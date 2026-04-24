package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.protocol.StateFileEntry
import dev.nimbuspowered.nimbus.protocol.StateManifest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class StateSyncManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newManager(quota: Long = 0L): StateSyncManager {
        val root = tempDir.resolve("state")
        Files.createDirectories(root)
        return StateSyncManager(stateRoot = root, diskQuotaBytes = quota)
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256").digest(bytes)
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun seedCanonical(mgr: StateSyncManager, service: String, files: Map<String, ByteArray>) {
        val dir = mgr.canonicalDir(service)
        Files.createDirectories(dir)
        for ((rel, content) in files) {
            val target = dir.resolve(rel)
            Files.createDirectories(target.parent)
            Files.write(target, content)
        }
    }

    @Test
    fun `buildManifest returns empty manifest when no canonical exists`() {
        val mgr = newManager()
        val manifest = mgr.buildManifest("absent")
        assertTrue(manifest.files.isEmpty())
    }

    @Test
    fun `buildManifest hashes all files and reports sizes`() {
        val mgr = newManager()
        val content = "hello".toByteArray()
        seedCanonical(mgr, "svc", mapOf("a.txt" to content, "sub/b.txt" to "world".toByteArray()))

        val manifest = mgr.buildManifest("svc")
        assertEquals(2, manifest.files.size)
        assertEquals(sha256(content), manifest.files["a.txt"]!!.sha256)
        assertEquals(content.size.toLong(), manifest.files["a.txt"]!!.size)
        assertTrue(manifest.files.containsKey("sub/b.txt"))
    }

    @Test
    fun `buildManifest honors excludes`() {
        val mgr = newManager()
        seedCanonical(
            mgr, "svc",
            mapOf("a.txt" to "a".toByteArray(), "logs/out.log" to "x".toByteArray())
        )
        val manifest = mgr.buildManifest("svc", excludes = listOf("logs/"))
        assertEquals(setOf("a.txt"), manifest.files.keys)
    }

    @Test
    fun `tryAcquireLock blocks concurrent threads and releases correctly`() {
        val mgr = newManager()
        assertTrue(mgr.tryAcquireLock("svc"))
        assertTrue(mgr.isSyncInFlight("svc"))

        // A different thread must NOT acquire while held (ReentrantLock is reentrant
        // on the same thread, so we need a second thread to exercise contention).
        val otherThreadAcquired = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = Thread {
            otherThreadAcquired.set(mgr.tryAcquireLock("svc"))
            if (otherThreadAcquired.get()) mgr.releaseLock("svc")
        }
        t.start()
        t.join()
        assertFalse(otherThreadAcquired.get(), "other thread acquired while lock was held")

        mgr.releaseLock("svc")
        assertFalse(mgr.isSyncInFlight("svc"))
        assertTrue(mgr.tryAcquireLock("svc"))
        mgr.releaseLock("svc")
    }

    @Test
    fun `tryAcquireLock distinguishes per service`() {
        val mgr = newManager()
        assertTrue(mgr.tryAcquireLock("a"))
        assertTrue(mgr.tryAcquireLock("b"))
        mgr.releaseLock("a")
        mgr.releaseLock("b")
    }

    @Test
    fun `openFileForRead rejects path traversal`() {
        val mgr = newManager()
        seedCanonical(mgr, "svc", mapOf("real.txt" to "x".toByteArray()))
        assertNull(mgr.openFileForRead("svc", "../../etc/passwd"))
        assertNotNull(mgr.openFileForRead("svc", "real.txt"))
    }

    @Test
    fun `openFileForRead returns null for missing files`() {
        val mgr = newManager()
        seedCanonical(mgr, "svc", mapOf("a.txt" to "x".toByteArray()))
        assertNull(mgr.openFileForRead("svc", "missing.txt"))
    }

    @Test
    fun `beginSync seeds staging from canonical and writeStagedFile commits`() {
        val mgr = newManager()
        val oldContent = "old".toByteArray()
        seedCanonical(mgr, "svc", mapOf("unchanged.txt" to oldContent))

        val staging = mgr.beginSync("svc")
        assertTrue(staging.toFile().exists())
        // Seeded: unchanged.txt should already exist in staging via hardlink
        assertTrue(Files.exists(staging.resolve("unchanged.txt")))

        // Push a new file
        val newContent = "hello".toByteArray()
        val expected = sha256(newContent)
        val staged = mgr.writeStagedFile("svc", "new.txt", expected, newContent.inputStream())
        assertEquals(newContent.size.toLong(), staged.bytes)
        assertEquals(expected, staged.actualSha256)

        // Commit with manifest containing both files
        val targetManifest = StateManifest(
            mapOf(
                "unchanged.txt" to StateFileEntry(sha256(oldContent), oldContent.size.toLong()),
                "new.txt" to StateFileEntry(expected, newContent.size.toLong())
            )
        )
        val finalManifest = mgr.commitSync("svc", targetManifest)
        assertEquals(2, finalManifest.files.size)

        // Canonical now contains new.txt with new content
        val committed = mgr.canonicalDir("svc").resolve("new.txt")
        assertArrayEquals(newContent, Files.readAllBytes(committed))
    }

    @Test
    fun `commitSync removes files not in target manifest`() {
        val mgr = newManager()
        seedCanonical(
            mgr, "svc",
            mapOf("keep.txt" to "k".toByteArray(), "drop.txt" to "d".toByteArray())
        )
        mgr.beginSync("svc")
        val keepBytes = "k".toByteArray()
        val manifest = StateManifest(
            mapOf("keep.txt" to StateFileEntry(sha256(keepBytes), keepBytes.size.toLong()))
        )
        mgr.commitSync("svc", manifest)

        assertTrue(Files.exists(mgr.canonicalDir("svc").resolve("keep.txt")))
        assertFalse(Files.exists(mgr.canonicalDir("svc").resolve("drop.txt")))
    }

    @Test
    fun `writeStagedFile rejects path traversal`() {
        val mgr = newManager()
        mgr.beginSync("svc")
        val content = "x".toByteArray()
        assertThrows<IllegalArgumentException> {
            mgr.writeStagedFile("svc", "../escape.txt", sha256(content), content.inputStream())
        }
    }

    @Test
    fun `writeStagedFile accepts SHA mismatch and returns actual hash`() {
        val mgr = newManager()
        mgr.beginSync("svc")
        val content = "data".toByteArray()
        // Deliberately wrong expected hash — should still succeed and return actual.
        val staged = mgr.writeStagedFile("svc", "f.txt", "0".repeat(64), content.inputStream())
        assertEquals(sha256(content), staged.actualSha256)
    }

    @Test
    fun `abortSync wipes staging`() {
        val mgr = newManager()
        mgr.beginSync("svc")
        assertTrue(Files.exists(mgr.stagingDir("svc")))
        mgr.abortSync("svc")
        assertFalse(Files.exists(mgr.stagingDir("svc")))
    }

    @Test
    fun `deleteState removes canonical and staging`() {
        val mgr = newManager()
        seedCanonical(mgr, "svc", mapOf("a.txt" to "x".toByteArray()))
        mgr.beginSync("svc")
        mgr.deleteState("svc")
        assertFalse(Files.exists(mgr.canonicalDir("svc")))
        assertFalse(Files.exists(mgr.stagingDir("svc")))
    }

    @Test
    fun `canonicalSizeBytes sums all file sizes`() {
        val mgr = newManager()
        seedCanonical(
            mgr, "svc",
            mapOf("a" to ByteArray(100), "b" to ByteArray(200))
        )
        assertEquals(300L, mgr.canonicalSizeBytes("svc"))
    }

    @Test
    fun `canonicalSizeBytes is zero for nonexistent service`() {
        val mgr = newManager()
        assertEquals(0L, mgr.canonicalSizeBytes("none"))
    }

    @Test
    fun `enforceQuota throws when over limit`() {
        val mgr = newManager(quota = 1000L)
        seedCanonical(mgr, "svc", mapOf("a" to ByteArray(500)))
        // Projected = 500 (cluster) - 500 (this service) + 2000 (new) = 2000 > 1000
        assertThrows<StateSyncManager.QuotaExceededException> {
            mgr.enforceQuota("svc", 2000L)
        }
    }

    @Test
    fun `enforceQuota no-op when unlimited`() {
        val mgr = newManager(quota = 0L)
        // Should not throw
        mgr.enforceQuota("svc", Long.MAX_VALUE / 2)
    }

    @Test
    fun `enforceQuota allows replacing existing data`() {
        val mgr = newManager(quota = 1000L)
        seedCanonical(mgr, "svc", mapOf("a" to ByteArray(800)))
        // Replacing 800 with 900 is fine (under 1000)
        mgr.enforceQuota("svc", 900L)
    }

    @Test
    fun `commitSync throws quota exception before swap`() {
        val mgr = newManager(quota = 100L)
        seedCanonical(mgr, "svc", mapOf("a" to ByteArray(50)))
        mgr.beginSync("svc")
        val content = ByteArray(200)
        mgr.writeStagedFile("svc", "big", sha256(content), content.inputStream())
        val manifest = StateManifest(
            mapOf("big" to StateFileEntry(sha256(content), content.size.toLong()))
        )
        assertThrows<StateSyncManager.QuotaExceededException> {
            mgr.commitSync("svc", manifest)
        }
        // Canonical untouched
        assertTrue(Files.exists(mgr.canonicalDir("svc").resolve("a")))
    }

    @Test
    fun `cleanupStaleStaging removes incoming and old dirs`() {
        val mgr = newManager()
        val root = tempDir.resolve("state")
        Files.createDirectories(root.resolve("svc.incoming"))
        Files.createDirectories(root.resolve("svc.old"))
        Files.createDirectories(root.resolve("real-svc"))

        val cleaned = mgr.cleanupStaleStaging()
        assertEquals(2, cleaned)
        assertFalse(Files.exists(root.resolve("svc.incoming")))
        assertFalse(Files.exists(root.resolve("svc.old")))
        assertTrue(Files.exists(root.resolve("real-svc")))
    }

    @Test
    fun `listSyncServices enumerates canonical directories`() {
        val mgr = newManager()
        seedCanonical(mgr, "alpha", mapOf("a" to ByteArray(1)))
        seedCanonical(mgr, "beta", mapOf("b" to ByteArray(1)))
        // Staging/old dirs shouldn't show up as services
        Files.createDirectories(tempDir.resolve("state/gamma.incoming"))

        val services = mgr.listSyncServices()
        assertTrue(services.contains("alpha"))
        assertTrue(services.contains("beta"))
        assertFalse(services.contains("gamma.incoming"))
    }

    @Test
    fun `custom root resolver redirects canonical dir`() {
        val customRoot = tempDir.resolve("custom-dedicated")
        Files.createDirectories(customRoot)
        val mgr = StateSyncManager(
            stateRoot = tempDir.resolve("state").also { Files.createDirectories(it) },
            customRootResolver = { name -> if (name == "special") customRoot.resolve("special") else null }
        )
        assertEquals(customRoot.resolve("special"), mgr.canonicalDir("special"))
        // Non-special still uses default
        assertTrue(mgr.canonicalDir("regular").startsWith(tempDir.resolve("state")))
    }

    // ── Additional: buildManifest edge cases ────────────────────────────────

    @Test
    fun `buildManifest on empty directory returns empty manifest`() {
        val mgr = newManager()
        // Create an empty canonical directory for the service
        val dir = mgr.canonicalDir("empty-svc")
        Files.createDirectories(dir)
        val manifest = mgr.buildManifest("empty-svc")
        assertTrue(manifest.files.isEmpty(), "Empty directory should produce empty manifest")
    }

    @Test
    fun `buildManifest with excludes omits matching files`() {
        val mgr = newManager()
        seedCanonical(
            mgr, "svc2",
            mapOf(
                "keep.txt" to "keep".toByteArray(),
                "cache/data.bin" to "cache".toByteArray(),
                "logs/server.log" to "log".toByteArray()
            )
        )
        val manifest = mgr.buildManifest("svc2", excludes = listOf("cache/", "logs/"))
        assertEquals(setOf("keep.txt"), manifest.files.keys, "Only keep.txt should remain after exclude")
    }

    // ── Additional: cleanupStaleStaging edge cases ───────────────────────────

    @Test
    fun `cleanupStaleStaging returns 0 when no stale dirs exist`() {
        val mgr = newManager()
        // State root is fresh (created by newManager), no *.incoming or *.old entries
        val cleaned = mgr.cleanupStaleStaging()
        assertEquals(0, cleaned, "Fresh manager with no stale dirs should return 0")
    }

    @Test
    fun `cleanupStaleStaging uses extra roots when provided`() {
        val mgr = newManager()
        val extraRoot = tempDir.resolve("extra-root")
        Files.createDirectories(extraRoot)
        Files.createDirectories(extraRoot.resolve("dedicated-svc.incoming"))
        Files.createDirectories(extraRoot.resolve("dedicated-svc.old"))

        val cleaned = mgr.cleanupStaleStaging(extraRoots = listOf(extraRoot))
        assertEquals(2, cleaned, "Two stale dirs in extra root should be cleaned")
        assertFalse(Files.exists(extraRoot.resolve("dedicated-svc.incoming")))
        assertFalse(Files.exists(extraRoot.resolve("dedicated-svc.old")))
    }

    // ── Additional: enforceQuota with 0 quota ────────────────────────────────

    @Test
    fun `enforceQuota with 0 quota does not throw even for very large values`() {
        val mgr = newManager(quota = 0L) // 0 = unlimited
        // Should never throw regardless of projected size
        mgr.enforceQuota("svc", Long.MAX_VALUE)
        mgr.enforceQuota("svc", 1_000_000_000L)
    }

    // ── Additional: listSyncServices ─────────────────────────────────────────

    @Test
    fun `listSyncServices returns empty when no services synced`() {
        val mgr = newManager()
        // Fresh manager — no canonical dirs, no in-flight locks, no stats
        val services = mgr.listSyncServices()
        assertTrue(services.isEmpty(), "Fresh manager should have no sync services")
    }

    // ── Additional: concurrent lock serialization ────────────────────────────

    @Test
    fun `concurrent tryAcquireLock for same service allows only one holder`() {
        val mgr = newManager()
        // Acquire on main thread
        assertTrue(mgr.tryAcquireLock("concurrent-svc"))
        assertTrue(mgr.isSyncInFlight("concurrent-svc"))

        // Spin up N threads; none of them should be able to acquire
        val acquired = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..5).map {
            Thread {
                if (mgr.tryAcquireLock("concurrent-svc")) {
                    acquired.incrementAndGet()
                    mgr.releaseLock("concurrent-svc")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(0, acquired.get(), "No other thread should acquire while lock is held")

        // Release and verify another thread can now acquire
        mgr.releaseLock("concurrent-svc")
        assertFalse(mgr.isSyncInFlight("concurrent-svc"))

        val acquired2 = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            if (mgr.tryAcquireLock("concurrent-svc")) {
                acquired2.set(true)
                mgr.releaseLock("concurrent-svc")
            }
        }
        t.start()
        t.join()
        assertTrue(acquired2.get(), "Thread should acquire lock after it was released")
    }

    @Test
    fun `tryAcquireLock for different services does not contend`() {
        val mgr = newManager()
        // Locking "svc-x" must not prevent locking "svc-y"
        assertTrue(mgr.tryAcquireLock("svc-x"))
        val acquiredY = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            acquiredY.set(mgr.tryAcquireLock("svc-y"))
            if (acquiredY.get()) mgr.releaseLock("svc-y")
        }
        t.start()
        t.join()
        assertTrue(acquiredY.get(), "Lock on svc-x must not block svc-y")
        mgr.releaseLock("svc-x")
    }
}
