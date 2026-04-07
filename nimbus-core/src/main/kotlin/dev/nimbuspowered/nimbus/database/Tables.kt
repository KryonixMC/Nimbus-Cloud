package dev.nimbuspowered.nimbus.database

import org.jetbrains.exposed.sql.Table

// ── Audit Log ──────────────────────────────────────────────

object AuditLog : Table("audit_log") {
    val id = integer("id").autoIncrement()
    val timestamp = varchar("timestamp", 30)
    val actor = varchar("actor", 128)
    val action = varchar("action", 64)
    val target = varchar("target", 256).default("")
    val details = varchar("details", 1024).default("")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, timestamp)
        index(false, actor)
        index(false, action)
    }
}

// ── Metrics Tables ──────────────────────────────────────────

object ServiceEvents : Table("service_events") {
    val id = integer("id").autoIncrement()
    val timestamp = varchar("timestamp", 30)
    val eventType = varchar("event_type", 32)
    val serviceName = varchar("service_name", 128)
    val groupName = varchar("group_name", 64).nullable()
    val port = integer("port").nullable()
    val exitCode = integer("exit_code").nullable()
    val restartAttempt = integer("restart_attempt").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, timestamp)
        index(false, serviceName)
    }
}

object ScalingEvents : Table("scaling_events") {
    val id = integer("id").autoIncrement()
    val timestamp = varchar("timestamp", 30)
    val eventType = varchar("event_type", 16)
    val groupName = varchar("group_name", 64)
    val serviceName = varchar("service_name", 128).nullable()
    val currentInstances = integer("current_instances").nullable()
    val targetInstances = integer("target_instances").nullable()
    val reason = varchar("reason", 512)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, timestamp)
        index(false, serviceName)
        index(false, groupName)
    }
}

object PlayerSessions : Table("player_sessions") {
    val id = integer("id").autoIncrement()
    val playerName = varchar("player_name", 16)
    val serviceName = varchar("service_name", 128)
    val connectedAt = varchar("connected_at", 30)
    val disconnectedAt = varchar("disconnected_at", 30).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, playerName, connectedAt)
        index(false, serviceName)
        index(false, playerName, serviceName, disconnectedAt)
    }
}
