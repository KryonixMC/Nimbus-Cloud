package dev.nimbuspowered.nimbus.module.resourcepacks

import dev.nimbuspowered.nimbus.module.api.DoctorCheck
import dev.nimbuspowered.nimbus.module.api.DoctorFinding
import dev.nimbuspowered.nimbus.module.api.DoctorLevel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

/**
 * Doctor checks specific to the resource packs module:
 *
 * 1. LOCAL packs whose on-disk `.zip` has gone missing (DB entry exists, file does not).
 *    These will 404 on player join and break pack negotiation.
 * 2. On-disk files under `data/resourcepacks/` that no DB entry references.
 *    Harmless but waste disk — typically left over after a failed upload.
 */
class ResourcePacksDoctorCheck(
    private val manager: ResourcePackManager,
    private val storageDir: Path,
) : DoctorCheck {

    override val section = "Resource Packs"

    override suspend fun run(): List<DoctorFinding> {
        val findings = mutableListOf<DoctorFinding>()
        val packs = manager.listPacks()
        val localPacks = packs.filter { it.source.equals("LOCAL", ignoreCase = true) }

        // 1. DB entries without a corresponding file.
        val missing = localPacks.filter { !Files.exists(storageDir.resolve("${it.packUuid}.zip")) }
        if (missing.isEmpty()) {
            findings += DoctorFinding(DoctorLevel.OK,
                "${localPacks.size} local pack file(s) present (+ ${packs.size - localPacks.size} URL pack(s))")
        } else {
            findings += DoctorFinding(DoctorLevel.FAIL,
                "${missing.size} local pack(s) missing their .zip file: ${missing.joinToString { it.name }}",
                "Re-upload these packs or remove them with `resourcepack remove <id>` — players will currently see a 404")
        }

        // 2. Orphan files on disk.
        if (Files.isDirectory(storageDir)) {
            val known = localPacks.map { "${it.packUuid}.zip" }.toSet()
            val orphans = Files.list(storageDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".zip") }
                    .map { it.fileName.toString() }
                    .toList()
                    .filter { it !in known }
            }
            if (orphans.isNotEmpty()) {
                val sizeMb = orphans.sumOf {
                    try { Files.size(storageDir.resolve(it)) } catch (_: Exception) { 0L }
                } / (1024L * 1024)
                findings += DoctorFinding(DoctorLevel.WARN,
                    "${orphans.size} orphan pack file(s) on disk (~${sizeMb}MB)",
                    "Delete from ${storageDir} if no longer needed — they are not referenced by any pack")
            }
        }
        return findings
    }
}
