package dev.nimbus.service

import java.nio.file.Path
import java.time.Instant

data class Service(
    val name: String,
    val groupName: String,
    val port: Int,
    var state: ServiceState = ServiceState.PREPARING,
    var customState: String? = null,
    var pid: Long? = null,
    var playerCount: Int = 0,
    var startedAt: Instant? = null,
    var restartCount: Int = 0,
    var workingDirectory: Path,
    var isStatic: Boolean = false
)
