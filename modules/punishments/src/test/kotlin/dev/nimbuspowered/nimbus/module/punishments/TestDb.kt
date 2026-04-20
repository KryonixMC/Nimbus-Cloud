package dev.nimbuspowered.nimbus.module.punishments

import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val dbCounter = AtomicInteger(0)

fun buildPunishTestDb(dir: Path): DatabaseManager {
    val file = dir.resolve("punish-test-${dbCounter.incrementAndGet()}.db")
    val db = Database.connect("jdbc:sqlite:$file", "org.sqlite.JDBC")
    transaction(db) { SchemaUtils.create(Punishments) }
    val dm = mockk<DatabaseManager>(relaxed = true)
    every { dm.database } returns db
    return dm
}
