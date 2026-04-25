package dev.nimbuspowered.nimbus.module.storage.driver

import java.nio.file.Path

interface StorageDriver {
    /** List all object keys under the given prefix */
    fun listObjects(prefix: String): List<StorageObject>

    /** Upload a file. Returns the ETag. */
    fun putObject(key: String, file: Path): String

    /** Download an object to a local path. */
    fun getObject(key: String, destination: Path)

    /** Get ETag of an object without downloading. Returns null if not found. */
    fun headObject(key: String): String?

    /** Delete an object. */
    fun deleteObject(key: String)

    fun close()
}

data class StorageObject(
    val key: String,
    val etag: String,
    val size: Long
)
