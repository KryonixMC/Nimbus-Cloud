package dev.nimbuspowered.nimbus.module.storage

import dev.nimbuspowered.nimbus.module.storage.driver.StorageDriver
import dev.nimbuspowered.nimbus.module.storage.driver.StorageObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

class TemplateSyncManager(
    private val driver: StorageDriver,
    private val templatesDir: Path,
    private val config: StorageConfig
) {

    private val logger = LoggerFactory.getLogger(TemplateSyncManager::class.java)

    /**
     * Push local template directory to S3 (delta: skip files where local MD5 == S3 ETag).
     * Returns a SyncResult with counts.
     */
    suspend fun push(templateName: String, progress: (String) -> Unit = {}): SyncResult =
        withContext(Dispatchers.IO) {
            val localDir = templatesDir.resolve(templateName)
            if (!localDir.exists() || !localDir.isDirectory()) {
                return@withContext SyncResult(templateName, errors = listOf("Local template '$templateName' not found at $localDir"))
            }

            var uploaded = 0; var skipped = 0
            val errors = mutableListOf<String>()
            val prefix = s3Prefix(templateName)

            Files.walk(localDir).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { file ->
                    val relative = localDir.relativize(file).toString().replace('\\', '/')
                    val key = "$prefix/$relative"
                    try {
                        val localMd5 = md5Hex(file)
                        val remoteEtag = driver.headObject(key)
                        if (remoteEtag == localMd5) {
                            skipped++
                            progress("skip $relative")
                        } else {
                            driver.putObject(key, file)
                            uploaded++
                            progress("push $relative")
                        }
                    } catch (e: Exception) {
                        errors += "Failed to push $relative: ${e.message}"
                        logger.warn("Push error for key '{}': {}", key, e.message)
                    }
                }
            }

            SyncResult(templateName, uploaded = uploaded, skipped = skipped, errors = errors)
        }

    /**
     * Pull template from S3 to local disk (delta: skip files where S3 ETag == local MD5).
     */
    suspend fun pull(templateName: String, progress: (String) -> Unit = {}): SyncResult =
        withContext(Dispatchers.IO) {
            val prefix = s3Prefix(templateName)
            val remoteObjects = driver.listObjects(prefix)

            if (remoteObjects.isEmpty()) {
                return@withContext SyncResult(templateName, errors = listOf("No remote objects found for template '$templateName' under prefix '$prefix'"))
            }

            val localDir = templatesDir.resolve(templateName)
            var downloaded = 0; var skipped = 0
            val errors = mutableListOf<String>()

            remoteObjects.forEach { obj ->
                // Strip the prefix to get the relative path within the template
                val relative = obj.key.removePrefix("$prefix/")
                if (relative.isBlank()) return@forEach

                val localFile = localDir.resolve(relative)
                try {
                    val localMd5 = if (localFile.exists()) md5Hex(localFile) else null
                    if (localMd5 == obj.etag) {
                        skipped++
                        progress("skip $relative")
                    } else {
                        localFile.parent?.createDirectories()
                        driver.getObject(obj.key, localFile)
                        downloaded++
                        progress("pull $relative")
                    }
                } catch (e: Exception) {
                    errors += "Failed to pull $relative: ${e.message}"
                    logger.warn("Pull error for key '{}': {}", obj.key, e.message)
                }
            }

            SyncResult(templateName, downloaded = downloaded, skipped = skipped, errors = errors)
        }

    /**
     * List template names available in the remote bucket (distinct first-level prefixes).
     */
    suspend fun listRemote(): List<String> = withContext(Dispatchers.IO) {
        val bucketPrefix = config.prefix.trimEnd('/') + "/"
        driver.listObjects(bucketPrefix)
            .map { it.key.removePrefix(bucketPrefix).substringBefore('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    /**
     * List local template names (directory names under templatesDir).
     */
    suspend fun listLocal(): List<String> = withContext(Dispatchers.IO) {
        if (!templatesDir.exists()) return@withContext emptyList()
        Files.list(templatesDir).use { stream ->
            stream.filter { it.isDirectory() }.map { it.name }.sorted().toList()
        }
    }

    /**
     * Status of each local and remote template.
     */
    suspend fun status(): List<TemplateSyncStatus> = withContext(Dispatchers.IO) {
        val local = listLocal().toSet()
        val remote = listRemote().toSet()
        val all = (local + remote).sorted()

        all.map { name ->
            val localExists = name in local
            val remoteExists = name in remote
            val localCount = if (localExists) countLocalFiles(name) else 0
            val remoteObjects = if (remoteExists) driver.listObjects(s3Prefix(name)) else emptyList()
            val remoteCount = remoteObjects.size

            val inSync = if (localExists && remoteExists && localCount == remoteCount) {
                checkInSync(name, remoteObjects)
            } else false

            TemplateSyncStatus(name, localExists, remoteExists, inSync, localCount, remoteCount)
        }
    }

    private fun s3Prefix(templateName: String) = "${config.prefix.trimEnd('/')}/$templateName"

    private fun countLocalFiles(templateName: String): Int {
        val dir = templatesDir.resolve(templateName)
        if (!dir.exists()) return 0
        return Files.walk(dir).use { it.filter { f -> f.isRegularFile() }.count().toInt() }
    }

    private fun checkInSync(templateName: String, remoteObjects: List<StorageObject>): Boolean {
        val localDir = templatesDir.resolve(templateName)
        val prefix = s3Prefix(templateName)
        val remoteByRelative = remoteObjects.associateBy { it.key.removePrefix("$prefix/") }
        return try {
            Files.walk(localDir).use { stream ->
                stream.filter { it.isRegularFile() }.allMatch { file ->
                    val relative = localDir.relativize(file).toString().replace('\\', '/')
                    val remote = remoteByRelative[relative] ?: return@allMatch false
                    md5Hex(file) == remote.etag
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun md5Hex(file: Path): String {
            val md = MessageDigest.getInstance("MD5")
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } > 0) md.update(buf, 0, read)
            }
            return BigInteger(1, md.digest()).toString(16).padStart(32, '0')
        }
    }
}

data class SyncResult(
    val templateName: String,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
) {
    val success get() = errors.isEmpty()
}

data class TemplateSyncStatus(
    val templateName: String,
    val localExists: Boolean,
    val remoteExists: Boolean,
    val inSync: Boolean,
    val localFileCount: Int,
    val remoteFileCount: Int
)
