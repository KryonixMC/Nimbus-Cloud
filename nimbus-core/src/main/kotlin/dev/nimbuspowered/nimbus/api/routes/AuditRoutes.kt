package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.database.AuditLog
import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll

fun Route.auditRoutes(db: DatabaseManager) {
    get("/api/audit") {
        val limit = (call.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        val offset = (call.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0)
        val actionFilter = call.queryParameters["action"]
        val actorFilter = call.queryParameters["actor"]

        val entries = db.query {
            var query = AuditLog.selectAll()
            if (actionFilter != null) query = query.andWhere { AuditLog.action eq actionFilter }
            if (actorFilter != null) query = query.andWhere { AuditLog.actor eq actorFilter }
            query.orderBy(AuditLog.timestamp, SortOrder.DESC)
                .limit(limit).offset(offset)
                .map { row ->
                    mapOf(
                        "timestamp" to row[AuditLog.timestamp],
                        "actor" to row[AuditLog.actor],
                        "action" to row[AuditLog.action],
                        "target" to row[AuditLog.target],
                        "details" to row[AuditLog.details]
                    )
                }
        }

        call.respond(HttpStatusCode.OK, mapOf(
            "entries" to entries,
            "limit" to limit,
            "offset" to offset
        ))
    }
}
