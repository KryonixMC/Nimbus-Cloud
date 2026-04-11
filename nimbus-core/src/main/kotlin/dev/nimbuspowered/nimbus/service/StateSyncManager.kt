package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.protocol.StateFileEntry
import dev.nimbuspowered.nimbus.protocol.StateManifest
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.exists

/**
 * Controller-side state store for services with `sync.enabled = true`.
 *
 * Canonical data lives under [stateRoot] (typically `services/state/<name>/`).
 * The manager handles:
 *   - Building a [StateManifest] for a service's canonical copy.
 *   - Serving individual files to agents (pull direction).
 *   - Accepting agent-pushed files into a staging dir and atomically committing.
 *
 * Atomicity: all writes go to `services/state/<name>.incoming/` first. On commit,
 * the current canonical dir is moved to `<name>.old`, staging is renamed to
 * canonical, and `<name>.old` is deleted. If a sync is interrupted mid-flight,
 * the staging dir is left behind and cleaned up on the next sync begin.
 */
class StateSyncManager(
    private val stateRoot: Path,
    /**
     * Optional lookup for services whose canonical dir lives outside [stateRoot]
     * (e.g. dedicated services in `dedicated/<name>/`). Return null to fall back to
     * the default `stateRoot/<name>` layout.
     */
    private val customRootResolver: ((String) -> Path?)? = null
) {
    private val logger = LoggerFactory.getLogger(StateSyncManager::class.java)

    /**
     * Per-service locks so two syncs for the same service serialize. Concurrent
     * pushes from (e.g.) two crash-respawn paths would otherwise corrupt staging.
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    init {
        if (!stateRoot.exists()) Files.createDirectories(stateRoot)
    }

    /**
     * Attempts to acquire the sync lock for [serviceName]. Returns true on success,
     * false if another sync is already in progress. The caller MUST call [releaseLock]
     * in a finally block.
     */
    fun tryAcquireLock(serviceName: String): Boolean {
        val lock = locks.computeIfAbsent(serviceName) { ReentrantLock() }
        return lock.tryLock()
    }

    fun releaseLock(serviceName: String) {
        locks[serviceName]?.let { lock ->
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    /** Absolute path to the canonical state dir for [serviceName]. */
    fun canonicalDir(serviceName: String): Path {
        val custom = customRootResolver?.invoke(serviceName)
        return custom ?: stateRoot.resolve(serviceName)
    }

    /**
     * Absolute path to the staging dir for [serviceName]. Always a sibling of the
     * canonical dir so the atomic rename on commit stays on the same filesystem.
     */
    fun stagingDir(serviceName: String): Path {
        val canonical = canonicalDir(serviceName)
        return canonical.resolveSibling("${canonical.fileName}.incoming")
    }

    /**
     * Builds a manifest of the canonical copy of [serviceName]. Returns an empty
     * manifest if no canonical exists (first start / sync never happened yet).
     * Excluded paths are filtered out.
     */
    fun buildManifest(serviceName: String, excludes: List<String> = emptyList()): StateManifest {
        val root = canonicalDir(serviceName)
        if (!root.exists() || !Files.isDirectory(root)) return StateManifest()
        return scanManifest(root, excludes)
    }

    /**
     * Opens [relPath] from the canonical copy of [serviceName] for streaming.
     * Returns null if the file doesn't exist or escapes the canonical dir.
     */
    fun openFileForRead(serviceName: String, relPath: String): Path? {
        val root = canonicalDir(serviceName).toAbsolutePath().normalize()
        val resolved = root.resolve(relPath).normalize()
        if (!resolved.startsWith(root)) return null
        if (!resolved.exists() || !Files.isRegularFile(resolved)) return null
        return resolved
    }

    /**
     * Prepares the staging dir for a new sync. Seeds it by hardlinking every file
     * from the current canonical copy (if any). This makes uploads a diff operation:
     * the agent only needs to upload files that differ, and unchanged files are
     * already present in staging via hardlinks.
     *
     * Returns the absolute staging path.
     */
    fun beginSync(serviceName: String): Path {
        val staging = stagingDir(serviceName)

        // Clean up any stale staging from a previous interrupted sync
        if (staging.exists()) {
            logger.warn("Stale staging dir for '{}' — wiping", serviceName)
            deleteRecursively(staging)
        }
        Files.createDirectories(staging)

        val canonical = canonicalDir(serviceName)
        if (canonical.exists() && Files.isDirectory(canonical)) {
            seedStagingFromCanonical(canonical, staging)
        }
        return staging
    }

    /**
     * Writes a single uploaded file into the staging dir at [relPath]. Validates
     * that the written bytes' SHA-256 matches [expectedSha256]. On mismatch the
     * file is deleted and an IOException is thrown.
     *
     * Also replaces any existing hardlink from seeding (we unlink first to avoid
     * mutating the canonical copy via the shared inode).
     */
    fun writeStagedFile(serviceName: String, relPath: String, expectedSha256: String, input: InputStream): Long {
        val staging = stagingDir(serviceName).toAbsolutePath().normalize()
        val target = staging.resolve(relPath).normalize()
        if (!target.startsWith(staging)) throw IllegalArgumentException("path escapes staging: $relPath")

        target.parent?.let { Files.createDirectories(it) }
        // Unlink any pre-seeded hardlink so we don't mutate canonical
        Files.deleteIfExists(target)

        val md = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                bytes += n
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            Files.deleteIfExists(target)
            throw java.io.IOException("SHA-256 mismatch for $relPath: expected $expectedSha256, got $actual")
        }
        return bytes
    }

    /**
     * Commits a staged sync: applies deletions (files present in staging but not in
     * the target manifest are removed), then atomically renames staging over
     * canonical. Returns the final StateManifest.
     */
    fun commitSync(serviceName: String, targetManifest: StateManifest): StateManifest {
        val staging = stagingDir(serviceName)
        if (!staging.exists()) throw IllegalStateException("no staging for '$serviceName' — beginSync not called")

        // Reconcile: remove files from staging that aren't in the target manifest.
        // This handles the case where the agent removed files locally (deletions).
        val kept = targetManifest.files.keys
        val stagingRoot = staging.toAbsolutePath().normalize()
        Files.walkFileTree(staging, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = stagingRoot.relativize(file.toAbsolutePath()).toString().replace('\\', '/')
                if (rel !in kept) {
                    Files.deleteIfExists(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (dir != staging) {
                    try {
                        if (Files.list(dir).use { !it.findAny().isPresent }) {
                            Files.deleteIfExists(dir)
                        }
                    } catch (_: Exception) {}
                }
                return FileVisitResult.CONTINUE
            }
        })

        // Atomic rename: canonical → .old, staging → canonical, delete .old.
        // .old lives as sibling of canonical so it's on the same filesystem.
        val canonical = canonicalDir(serviceName)
        val old = canonical.resolveSibling("${canonical.fileName}.old")
        if (old.exists()) deleteRecursively(old)

        if (canonical.exists()) {
            Files.move(canonical, old, StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(staging, canonical, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Rollback: restore canonical if the staging move failed
            if (old.exists()) Files.move(old, canonical, StandardCopyOption.ATOMIC_MOVE)
            throw e
        }
        if (old.exists()) deleteRecursively(old)

        logger.info("Sync committed for '{}' ({} files)", serviceName, targetManifest.files.size)
        return scanManifest(canonical, emptyList())
    }

    /** Aborts a sync by wiping the staging dir. */
    fun abortSync(serviceName: String) {
        val staging = stagingDir(serviceName)
        if (staging.exists()) deleteRecursively(staging)
    }

    /**
     * Deletes all canonical + staging state for [serviceName]. Used by operators
     * who want to force a service to re-template on next start.
     */
    fun deleteState(serviceName: String) {
        val canonical = canonicalDir(serviceName)
        if (canonical.exists()) deleteRecursively(canonical)
        abortSync(serviceName)
    }

    // ── internals ───────────────────────────────────────────

    private fun seedStagingFromCanonical(canonical: Path, staging: Path) {
        val canonicalRoot = canonical.toAbsolutePath().normalize()
        Files.walkFileTree(canonical, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = canonicalRoot.relativize(dir.toAbsolutePath())
                if (rel.toString().isEmpty()) return FileVisitResult.CONTINUE
                val target = staging.resolve(rel.toString())
                Files.createDirectories(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = canonicalRoot.relativize(file.toAbsolutePath())
                val target = staging.resolve(rel.toString())
                target.parent?.let { Files.createDirectories(it) }
                try {
                    Files.createLink(target, file)
                } catch (_: UnsupportedOperationException) {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                } catch (_: java.nio.file.FileSystemException) {
                    // Some filesystems (e.g. NTFS over WSL) may reject hardlinks for certain
                    // files — fall back to copy rather than failing the whole sync.
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun scanManifest(root: Path, excludes: List<String>): StateManifest {
        val matchers = excludes.map { compileGlob(it) }
        val rootAbs = root.toAbsolutePath().normalize()
        val files = mutableMapOf<String, StateFileEntry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rootAbs.relativize(file.toAbsolutePath()).toString().replace('\\', '/')
                if (matchers.any { it(rel) }) return FileVisitResult.CONTINUE
                val hash = sha256(file)
                files[rel] = StateFileEntry(sha256 = hash, size = attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
        return StateManifest(files)
    }

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Compiles an rsync-style pattern to a relative-path matcher. Supports:
     *   - Trailing `/` → directory match (matches "foo/" and anything under "foo/")
     *   - `*` and `?` glob wildcards
     *   - Leading path segments (e.g. `plugins/logs/`)
     */
    private fun compileGlob(pattern: String): (String) -> Boolean {
        if (pattern.isBlank()) return { _: String -> false }
        val trimmed = pattern.trimEnd('/')
        val isDir = pattern.endsWith('/')
        val pathSlash = trimmed.contains('/')
        val fs = FileSystems.getDefault()

        return if (pathSlash || isDir) {
            // Path-anchored glob (matches from the root of the relative path)
            val pathMatcher = fs.getPathMatcher("glob:$trimmed")
            val dirPrefix = "$trimmed/"
            val fn: (String) -> Boolean = { rel ->
                val relPath: Path = java.nio.file.Paths.get(rel)
                pathMatcher.matches(relPath) || rel.startsWith(dirPrefix)
            }
            fn
        } else {
            // Basename glob (matches any segment of the path)
            val pathMatcher = fs.getPathMatcher("glob:$trimmed")
            val fn: (String) -> Boolean = { rel ->
                val relPath: Path = java.nio.file.Paths.get(rel)
                var matched = false
                for (i in 0 until relPath.nameCount) {
                    if (pathMatcher.matches(relPath.getName(i))) {
                        matched = true
                        break
                    }
                }
                matched
            }
            fn
        }
    }
}
