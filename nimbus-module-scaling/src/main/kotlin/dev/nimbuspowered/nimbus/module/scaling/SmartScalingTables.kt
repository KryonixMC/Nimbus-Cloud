package dev.nimbuspowered.nimbus.module.scaling

import org.jetbrains.exposed.sql.Table

/** Periodic snapshots of player counts per group (collected every 60s). */
object ScalingSnapshots : Table("smart_scaling_snapshots") {
    val id = integer("id").autoIncrement()
    val timestamp = varchar("timestamp", 30)        // ISO-8601
    val groupName = varchar("group_name", 64)
    val playerCount = integer("player_count")
    val serviceCount = integer("service_count")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, timestamp, groupName)
        index(false, groupName)
    }
}

/** Log of scaling decisions made by the smart scaling module. */
object ScalingDecisions : Table("smart_scaling_decisions") {
    val id = integer("id").autoIncrement()
    val timestamp = varchar("timestamp", 30)        // ISO-8601
    val groupName = varchar("group_name", 64)
    val action = varchar("action", 32)              // "warmup", "schedule_scale"
    val reason = varchar("reason", 512)
    val decisionSource = varchar("source", 32)       // "schedule", "prediction"
    val servicesStarted = integer("services_started")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, timestamp)
        index(false, groupName)
    }
}
