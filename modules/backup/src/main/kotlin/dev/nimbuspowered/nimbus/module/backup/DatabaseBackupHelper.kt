package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Per-database-type dump helpers. SQLite uses `VACUUM INTO` — atomic, in-process,
 * no external tool. MySQL/Postgres shell out to the official dump utilities and
 * skip with a WARN if the tool is missing on PATH.
 *
 * Callers archive the resulting file just like any other file target.
 */
class DatabaseBackupHelper(
    private val db: Database,
    private val config: DatabaseConfig
) {

    private val logger = LoggerFactory.getLogger(DatabaseBackupHelper::class.java)

    sealed class Result {
        data class Success(val file: Path, val sizeBytes: Long) : Result()
        data class Skipped(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Dump the configured database into a single file under [stagingDir].
     * Returned file path is absolute. Caller is responsible for archiving +
     * cleanup.
     */
    suspend fun dump(stagingDir: Path): Result = withContext(Dispatchers.IO) {
        Files.createDirectories(stagingDir)
        when (config.type.lowercase()) {
            "sqlite" -> dumpSqlite(stagingDir)
            "mysql", "mariadb" -> dumpMysql(stagingDir)
            "postgresql", "postgres" -> dumpPostgres(stagingDir)
            else -> Result.Skipped("unknown database type '${config.type}'")
        }
    }

    private fun dumpSqlite(stagingDir: Path): Result {
        val dest = stagingDir.resolve("nimbus.sqlite")
        val tmp = stagingDir.resolve("nimbus.sqlite.tmp")
        return try {
            Files.deleteIfExists(tmp)
            // VACUUM INTO writes a consistent copy without locking the source for
            // the duration of the backup. SQLite handles this atomically.
            transaction(db) {
                exec("VACUUM INTO '${tmp.toAbsolutePath().toString().replace("'", "''")}'")
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Result.Success(dest, Files.size(dest))
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            Result.Failed("SQLite VACUUM INTO failed: ${e.message}")
        }
    }

    private fun dumpMysql(stagingDir: Path): Result {
        if (!isOnPath("mysqldump")) {
            return Result.Skipped("mysqldump not on PATH — install mysql-client to enable MySQL backups")
        }
        val dest = stagingDir.resolve("nimbus.sql")
        val cmd = listOf(
            "mysqldump",
            "--host=${config.host}",
            "--port=${config.port}",
            "--user=${config.username}",
            "--single-transaction",
            "--routines", "--triggers", "--events",
            "--no-tablespaces",
            config.name
        )
        return runDump(cmd, config.password.takeIf { it.isNotEmpty() }?.let { "MYSQL_PWD" to it }, dest)
    }

    private fun dumpPostgres(stagingDir: Path): Result {
        if (!isOnPath("pg_dump")) {
            return Result.Skipped("pg_dump not on PATH — install postgresql-client to enable Postgres backups")
        }
        val dest = stagingDir.resolve("nimbus.pgsql")
        val cmd = listOf(
            "pg_dump",
            "--host=${config.host}",
            "--port=${config.port}",
            "--username=${config.username}",
            "--format=custom",
            "--file=${dest.toAbsolutePath()}",
            config.name
        )
        return runDump(cmd, config.password.takeIf { it.isNotEmpty() }?.let { "PGPASSWORD" to it }, dest,
            captureStdoutToFile = false)
    }

    private fun runDump(
        cmd: List<String>,
        env: Pair<String, String>?,
        dest: Path,
        captureStdoutToFile: Boolean = true
    ): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        if (env != null) pb.environment()[env.first] = env.second
        if (captureStdoutToFile) {
            pb.redirectOutput(dest.toFile())
            pb.redirectError(ProcessBuilder.Redirect.PIPE)
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            pb.redirectError(ProcessBuilder.Redirect.PIPE)
        }
        return try {
            val proc = pb.start()
            // Drain stderr so the process doesn't block on a full pipe buffer.
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                runCatching { Files.deleteIfExists(dest) }
                Result.Failed("${cmd.first()} exited $exit: ${stderr.trim().take(500)}")
            } else if (!Files.exists(dest) || Files.size(dest) == 0L) {
                Result.Failed("${cmd.first()} produced empty output")
            } else {
                Result.Success(dest, Files.size(dest))
            }
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(dest) }
            Result.Failed("${cmd.first()} invocation failed: ${e.message}")
        }
    }

    private fun isOnPath(binary: String): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        val exts = if (System.getProperty("os.name").lowercase().contains("win"))
            listOf(".exe", ".bat", ".cmd", "") else listOf("")
        return pathEnv.split(java.io.File.pathSeparator).any { dir ->
            exts.any { ext -> java.io.File(dir, binary + ext).canExecute() }
        }
    }
}
