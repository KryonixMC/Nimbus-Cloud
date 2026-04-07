package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Tracks Remote CLI sessions in the database.
 * Subscribes to [NimbusEvent.CliSessionConnected] and [NimbusEvent.CliSessionDisconnected].
 */
class CliSessionTracker(
    private val db: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(CliSessionTracker::class.java)

    fun start(): List<Job> {
        val jobs = mutableListOf<Job>()

        jobs += eventBus.on<NimbusEvent.CliSessionConnected> { event ->
            try {
                db.query {
                    CliSessions.insert {
                        it[sessionId] = event.sessionId
                        it[remoteIp] = event.remoteIp
                        it[authenticatedAs] = event.user
                        it[connectedAt] = event.timestamp.toString()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to record CLI session connect: {}", e.message)
            }
        }

        jobs += eventBus.on<NimbusEvent.CliSessionDisconnected> { event ->
            try {
                db.query {
                    CliSessions.update({ CliSessions.sessionId eq event.sessionId }) {
                        it[disconnectedAt] = Instant.now().toString()
                        it[durationSeconds] = event.durationSeconds
                        it[commandCount] = event.commandCount
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to record CLI session disconnect: {}", e.message)
            }
        }

        logger.debug("CLI session tracking started")
        return jobs
    }

    data class SessionEntry(
        val sessionId: Int,
        val remoteIp: String,
        val authenticatedAs: String,
        val connectedAt: String,
        val disconnectedAt: String?,
        val durationSeconds: Long?,
        val commandCount: Int
    )

    suspend fun getRecentSessions(limit: Int = 20): List<SessionEntry> {
        return db.query {
            CliSessions.selectAll()
                .orderBy(CliSessions.connectedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    SessionEntry(
                        sessionId = row[CliSessions.sessionId],
                        remoteIp = row[CliSessions.remoteIp],
                        authenticatedAs = row[CliSessions.authenticatedAs],
                        connectedAt = row[CliSessions.connectedAt],
                        disconnectedAt = row[CliSessions.disconnectedAt],
                        durationSeconds = row[CliSessions.durationSeconds],
                        commandCount = row[CliSessions.commandCount]
                    )
                }
        }
    }

    suspend fun getActiveSessions(): List<SessionEntry> {
        return db.query {
            CliSessions.selectAll()
                .where { CliSessions.disconnectedAt.isNull() }
                .orderBy(CliSessions.connectedAt, SortOrder.DESC)
                .map { row ->
                    SessionEntry(
                        sessionId = row[CliSessions.sessionId],
                        remoteIp = row[CliSessions.remoteIp],
                        authenticatedAs = row[CliSessions.authenticatedAs],
                        connectedAt = row[CliSessions.connectedAt],
                        disconnectedAt = null,
                        durationSeconds = null,
                        commandCount = row[CliSessions.commandCount]
                    )
                }
        }
    }
}
