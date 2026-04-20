package dev.nimbuspowered.nimbus.module.scaling

import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val dbCounter = AtomicInteger(0)

fun buildScalingTestDb(dir: Path): DatabaseManager {
    val file = dir.resolve("scaling-test-${dbCounter.incrementAndGet()}.db")
    val db = Database.connect("jdbc:sqlite:$file", "org.sqlite.JDBC")
    transaction(db) {
        SchemaUtils.create(ScalingSnapshots, ScalingDecisions)
    }
    val dm = mockk<DatabaseManager>(relaxed = true)
    every { dm.database } returns db
    coEvery { dm.query<Any?>(any()) } coAnswers {
        @Suppress("UNCHECKED_CAST")
        val block = firstArg<Transaction.() -> Any?>()
        newSuspendedTransaction(Dispatchers.IO, db) { block() }
    }
    return dm
}
