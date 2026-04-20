package dev.nimbuspowered.nimbus.module.players

import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val dbCounter = AtomicInteger(0)

/**
 * Build a DatabaseManager mock backed by a real Exposed Database on a fresh
 * file-based SQLite file. We mock the class (its constructor has core-wide
 * dependencies) and stub `query` to run against the real DB.
 */
fun buildTestDb(dir: Path, vararg tables: Table): DatabaseManager {
    val file = dir.resolve("test-${dbCounter.incrementAndGet()}.db")
    val db = Database.connect(
        url = "jdbc:sqlite:${file}",
        driver = "org.sqlite.JDBC"
    )
    transaction(db) { SchemaUtils.create(*tables) }

    val mgr = mockk<DatabaseManager>()
    io.mockk.every { mgr.database } returns db
    coEvery { mgr.query<Any?>(any()) } coAnswers {
        val block = firstArg<Transaction.() -> Any?>()
        newSuspendedTransaction(Dispatchers.IO, db) { block() }
    }
    return mgr
}
