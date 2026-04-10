package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.AuditEntryResponse
import dev.nimbuspowered.nimbus.api.AuditListResponse
import dev.nimbuspowered.nimbus.database.AuditLog
import dev.nimbuspowered.nimbus.database.DatabaseManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll

/**
 * GET /api/audit — admin-only audit log listing.
 *
 * The previous implementation returned `mapOf<String, Any?>(...)` which
 * kotlinx.serialization cannot serialize (no polymorphic `Any` support),
 * causing the dashboard to silently receive a 500 and render an empty
 * table. We now serialize through proper `@Serializable` DTOs so the
 * dashboard and any other API consumer get real JSON back.
 */
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
                    AuditEntryResponse(
                        timestamp = row[AuditLog.timestamp],
                        actor = row[AuditLog.actor],
                        action = row[AuditLog.action],
                        target = row[AuditLog.target],
                        details = row[AuditLog.details],
                    )
                }
        }

        call.respond(HttpStatusCode.OK, AuditListResponse(entries, limit, offset))
    }
}
