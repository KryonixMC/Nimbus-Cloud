package dev.nimbuspowered.nimbus.module.resourcepacks

import dev.nimbuspowered.nimbus.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Persistence + file handling for resource packs.
 *
 * Uploaded files are streamed to `<baseDir>/data/resourcepacks/<packUuid>.zip`
 * while computing SHA-1 in a single pass. The returned record's `url` is a
 * relative path which the backend plugin resolves against the controller's
 * configured public base URL.
 */
class ResourcePackManager(
    private val db: DatabaseManager,
    private val storageDir: Path
) {

    private val logger = LoggerFactory.getLogger(ResourcePackManager::class.java)

    init {
        Files.createDirectories(storageDir)
    }

    // ── Packs ──────────────────────────────────────────────────

    suspend fun listPacks(): List<ResourcePackRecord> = newSuspendedTransaction(Dispatchers.IO, db.database) {
        ResourcePacks.selectAll().orderBy(ResourcePacks.uploadedAt, SortOrder.DESC).map { it.toPack() }
    }

    suspend fun getPack(id: Int): ResourcePackRecord? = newSuspendedTransaction(Dispatchers.IO, db.database) {
        ResourcePacks.selectAll().where { ResourcePacks.id eq id }.firstOrNull()?.toPack()
    }

    suspend fun getPackByUuid(packUuid: String): ResourcePackRecord? = newSuspendedTransaction(Dispatchers.IO, db.database) {
        ResourcePacks.selectAll().where { ResourcePacks.packUuid eq packUuid }.firstOrNull()?.toPack()
    }

    suspend fun createUrlPack(
        name: String,
        url: String,
        sha1Hash: String,
        promptMessage: String,
        force: Boolean,
        uploadedBy: String
    ): ResourcePackRecord = newSuspendedTransaction(Dispatchers.IO, db.database) {
        val now = Instant.now().toString()
        val uuid = UUID.randomUUID().toString()
        val id = ResourcePacks.insertAndGetId {
            it[packUuid] = uuid
            it[ResourcePacks.name] = name
            it[packSource] = "URL"
            it[ResourcePacks.url] = url
            it[ResourcePacks.sha1Hash] = sha1Hash
            it[ResourcePacks.promptMessage] = promptMessage
            it[ResourcePacks.force] = force
            it[fileSize] = 0
            it[uploadedAt] = now
            it[ResourcePacks.uploadedBy] = uploadedBy
        }
        ResourcePackRecord(id.value, uuid, name, "URL", url, sha1Hash, promptMessage, force, 0, now, uploadedBy)
    }

    /**
     * Stream an uploaded pack to disk, hashing as it flows.
     *
     * <h3>Memory profile</h3>
     * Peak RAM usage per upload is **64 KiB** regardless of file size — that's
     * the fixed-size transfer buffer we read into. The full flow is end-to-end
     * streaming:
     *   1. Browser: `fetch({ body: File })` streams the file off disk into the
     *      socket, no full-file buffer.
     *   2. Ktor CIO: [io.ktor.server.request.receiveStream] returns an
     *      [InputStream] that reads from the request channel as bytes arrive
     *      with TCP backpressure — never holds the whole body.
     *   3. Here: read 64 KiB → hash incrementally → write 64 KiB to disk.
     *   4. Atomic rename once the stream completes.
     *
     * We fail fast if the stream exceeds [maxBytes] so a client uploading a
     * 10 GB file gets rejected after 64 KiB, not after the whole body arrived.
     *
     * @throws IllegalArgumentException if upload exceeds [maxBytes]
     */
    suspend fun uploadLocalPack(
        name: String,
        input: InputStream,
        maxBytes: Long,
        promptMessage: String,
        force: Boolean,
        uploadedBy: String
    ): ResourcePackRecord {
        val packUuid = UUID.randomUUID().toString()
        val targetFile = storageDir.resolve("$packUuid.zip")
        val tempFile = storageDir.resolve("$packUuid.zip.part")
        val sha1 = MessageDigest.getInstance("SHA-1")
        var size = 0L

        try {
            // FileOutputStream writes straight to disk — no JVM-side buffering.
            // The 64 KiB transfer buffer is the only memory we allocate per upload.
            java.io.FileOutputStream(tempFile.toFile()).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    size += read
                    if (size > maxBytes) {
                        throw IllegalArgumentException("Upload exceeds max size ($maxBytes bytes)")
                    }
                    sha1.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
                // Ensure bytes hit the OS page cache before we atomic-move the file.
                out.fd.sync()
            }
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
            Files.deleteIfExists(targetFile)
            throw e
        }

        val hashHex = sha1.digest().joinToString("") { "%02x".format(it) }
        val now = Instant.now().toString()
        val relativeUrl = "/api/resourcepacks/files/$packUuid.zip"

        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val id = ResourcePacks.insertAndGetId {
                it[ResourcePacks.packUuid] = packUuid
                it[ResourcePacks.name] = name
                it[packSource] = "LOCAL"
                it[url] = relativeUrl
                it[sha1Hash] = hashHex
                it[ResourcePacks.promptMessage] = promptMessage
                it[ResourcePacks.force] = force
                it[fileSize] = size
                it[uploadedAt] = now
                it[ResourcePacks.uploadedBy] = uploadedBy
            }
            ResourcePackRecord(id.value, packUuid, name, "LOCAL", relativeUrl, hashHex, promptMessage, force, size, now, uploadedBy)
        }
    }

    /**
     * Delete a pack + its assignments. If the pack is locally hosted, removes
     * the file from disk as well.
     */
    suspend fun deletePack(id: Int): Boolean {
        val record = getPack(id) ?: return false
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            ResourcePackAssignments.deleteWhere { packId eq id }
            ResourcePacks.deleteWhere { ResourcePacks.id eq id }
        }
        if (record.source == "LOCAL") {
            try {
                Files.deleteIfExists(storageDir.resolve("${record.packUuid}.zip"))
            } catch (e: Exception) {
                logger.warn("Failed to delete pack file for {}: {}", record.packUuid, e.message)
            }
        }
        return true
    }

    /** Path to the on-disk file for a locally-hosted pack; null if not local or missing. */
    fun localPackFile(packUuid: String): Path? {
        val path = storageDir.resolve("$packUuid.zip")
        return if (Files.exists(path)) path else null
    }

    // ── Assignments ────────────────────────────────────────────

    suspend fun listAssignments(packId: Int? = null): List<AssignmentRecord> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            val query = if (packId != null)
                ResourcePackAssignments.selectAll().where { ResourcePackAssignments.packId eq packId }
            else ResourcePackAssignments.selectAll()
            query.orderBy(ResourcePackAssignments.priority, SortOrder.ASC).map { it.toAssignment() }
        }

    suspend fun createAssignment(packId: Int, scope: String, target: String, priority: Int): AssignmentRecord? =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            if (ResourcePacks.selectAll().where { ResourcePacks.id eq packId }.empty()) return@newSuspendedTransaction null
            val id = ResourcePackAssignments.insertAndGetId {
                it[ResourcePackAssignments.packId] = packId
                it[ResourcePackAssignments.scope] = scope
                it[ResourcePackAssignments.target] = target
                it[ResourcePackAssignments.priority] = priority
            }
            AssignmentRecord(id.value, packId, scope, target, priority)
        }

    suspend fun deleteAssignment(id: Int): Boolean = newSuspendedTransaction(Dispatchers.IO, db.database) {
        ResourcePackAssignments.deleteWhere { ResourcePackAssignments.id eq id } > 0
    }

    /**
     * Resolve all packs that apply to a given (group, service) context.
     * Packs are stacked in ascending priority order:
     *   GLOBAL < GROUP < SERVICE
     * Within the same scope, lower priority comes first.
     *
     * Local URLs are rewritten to absolute using [publicBaseUrl] so the client
     * can fetch them directly from the controller.
     */
    suspend fun resolvePacks(groupName: String, serviceName: String, publicBaseUrl: String): List<ResolvedPack> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            val predicate: Op<Boolean> =
                (ResourcePackAssignments.scope eq "GLOBAL") or
                ((ResourcePackAssignments.scope eq "GROUP") and (ResourcePackAssignments.target eq groupName)) or
                ((ResourcePackAssignments.scope eq "SERVICE") and (ResourcePackAssignments.target eq serviceName))

            val assignments = ResourcePackAssignments.selectAll().where { predicate }
                .map { it.toAssignment() }
                .sortedWith(compareBy({ scopeWeight(it.scope) }, { it.priority }))

            val packIds = assignments.map { it.packId }.distinct()
            val packs = if (packIds.isEmpty()) emptyMap() else
                ResourcePacks.selectAll().where { ResourcePacks.id inList packIds }
                    .associateBy({ it[ResourcePacks.id].value }, { it.toPack() })

            assignments.mapNotNull { a ->
                val pack = packs[a.packId] ?: return@mapNotNull null
                val absoluteUrl = if (pack.source == "LOCAL") publicBaseUrl.trimEnd('/') + pack.url else pack.url
                ResolvedPack(
                    packUuid = pack.packUuid,
                    name = pack.name,
                    url = absoluteUrl,
                    sha1Hash = pack.sha1Hash,
                    promptMessage = pack.promptMessage,
                    force = pack.force,
                    priority = a.priority
                )
            }
        }

    private fun scopeWeight(scope: String): Int = when (scope) {
        "GLOBAL" -> 0
        "GROUP" -> 1
        "SERVICE" -> 2
        else -> 3
    }

    // ── Row mapping ────────────────────────────────────────────

    private fun ResultRow.toPack() = ResourcePackRecord(
        id = this[ResourcePacks.id].value,
        packUuid = this[ResourcePacks.packUuid],
        name = this[ResourcePacks.name],
        source = this[ResourcePacks.packSource],
        url = this[ResourcePacks.url],
        sha1Hash = this[ResourcePacks.sha1Hash],
        promptMessage = this[ResourcePacks.promptMessage],
        force = this[ResourcePacks.force],
        fileSize = this[ResourcePacks.fileSize],
        uploadedAt = this[ResourcePacks.uploadedAt],
        uploadedBy = this[ResourcePacks.uploadedBy]
    )

    private fun ResultRow.toAssignment() = AssignmentRecord(
        id = this[ResourcePackAssignments.id].value,
        packId = this[ResourcePackAssignments.packId].value,
        scope = this[ResourcePackAssignments.scope],
        target = this[ResourcePackAssignments.target],
        priority = this[ResourcePackAssignments.priority]
    )
}

