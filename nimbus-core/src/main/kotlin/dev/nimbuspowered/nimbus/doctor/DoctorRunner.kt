package dev.nimbuspowered.nimbus.doctor

import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.api.DoctorCheck
import dev.nimbuspowered.nimbus.module.api.DoctorFinding
import dev.nimbuspowered.nimbus.module.api.DoctorLevel
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Aggregates built-in health checks + module-contributed [DoctorCheck]s into a single
 * [DoctorReport]. Shared by the `doctor` console command, the Remote CLI `--doctor` mode
 * and the `GET /api/doctor` endpoint so all three see identical results.
 *
 * Module checks are invoked in registration order. A failing module check never takes
 * the whole run down — its exception is captured as a FAIL finding in its own section.
 */
class DoctorRunner(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val databaseManager: DatabaseManager? = null,
    private val nodeManager: NodeManager? = null,
    private val extraChecks: () -> List<DoctorCheck> = { emptyList() },
) {

    private val logger = LoggerFactory.getLogger(DoctorRunner::class.java)

    suspend fun run(): DoctorReport {
        val sections = linkedMapOf<String, MutableList<DoctorFinding>>()

        fun add(name: String, findings: List<DoctorFinding>) {
            if (findings.isEmpty()) return
            sections.getOrPut(name) { mutableListOf() }.addAll(findings)
        }

        add("Environment", checkEnvironment())
        add("Configuration", checkConfiguration())
        add("Storage", checkStorage())
        databaseManager?.let { add("Database", checkDatabase(it)) }
        add("Services", checkServices())
        nodeManager?.let { add("Cluster", checkCluster(it)) }

        // Module-contributed checks — isolated failure boundary per check.
        for (check in extraChecks()) {
            val findings = try {
                check.run()
            } catch (e: Exception) {
                logger.warn("Doctor check '${check.section}' threw: {}", e.message)
                listOf(DoctorFinding(DoctorLevel.FAIL,
                    "Check raised ${e::class.simpleName}: ${e.message ?: "(no message)"}",
                    "This is a bug in the module providing this check — report it to the module author"))
            }
            add(check.section, findings)
        }

        val all = sections.values.flatten()
        val warn = all.count { it.level == DoctorLevel.WARN }
        val fail = all.count { it.level == DoctorLevel.FAIL }
        val status = when {
            fail > 0 -> "fail"
            warn > 0 -> "warn"
            else     -> "ok"
        }
        return DoctorReport(
            sections = sections.map { (name, findings) ->
                DoctorSection(name, findings.map { f ->
                    DoctorFindingDto(f.level.name, f.message, f.hint)
                })
            },
            warnCount = warn,
            failCount = fail,
            status = status,
        )
    }

    // --- built-in checks --------------------------------------------------

    private fun checkEnvironment(): List<DoctorFinding> {
        val out = mutableListOf<DoctorFinding>()
        val javaVer = System.getProperty("java.version") ?: "unknown"
        val major = javaVer.substringBefore('.').toIntOrNull() ?: 0
        out += if (major >= 21) {
            DoctorFinding(DoctorLevel.OK, "Java $javaVer")
        } else {
            DoctorFinding(DoctorLevel.FAIL, "Java $javaVer (Java 21+ required)",
                "Install a Java 21 JDK and restart Nimbus with it")
        }
        val os = System.getProperty("os.name") ?: "unknown"
        val arch = System.getProperty("os.arch") ?: "unknown"
        out += DoctorFinding(DoctorLevel.OK, "Platform: $os ($arch)")
        return out
    }

    private fun checkConfiguration(): List<DoctorFinding> {
        val out = mutableListOf<DoctorFinding>()

        if (config.api.enabled) {
            if (config.api.token.isBlank() && System.getenv("NIMBUS_API_TOKEN").isNullOrBlank()) {
                out += DoctorFinding(DoctorLevel.WARN, "API enabled but no persistent token configured",
                    "Set [api] token in nimbus.toml or export NIMBUS_API_TOKEN — otherwise a new token is generated on every start")
            } else {
                out += DoctorFinding(DoctorLevel.OK, "API enabled on ${config.api.bind}:${config.api.port}")
            }
            if (config.api.bind == "0.0.0.0" && !config.api.jwtEnabled) {
                out += DoctorFinding(DoctorLevel.WARN, "API bound to 0.0.0.0 without JWT",
                    "If this controller is internet-facing, bind to 127.0.0.1 or enable JWT")
            }
        } else {
            out += DoctorFinding(DoctorLevel.OK, "API disabled")
        }

        if (config.cluster.enabled) {
            if (config.cluster.token.isBlank() && System.getenv("NIMBUS_CLUSTER_TOKEN").isNullOrBlank()) {
                out += DoctorFinding(DoctorLevel.FAIL, "Cluster enabled but cluster token is empty",
                    "Run `cluster token regenerate` or set NIMBUS_CLUSTER_TOKEN — agents cannot connect without it")
            } else {
                out += DoctorFinding(DoctorLevel.OK, "Cluster enabled (port ${config.cluster.agentPort}, token set)")
            }
            if (config.cluster.tlsEnabled && config.cluster.keystorePath.isNotBlank()) {
                val ks = Path.of(config.cluster.keystorePath)
                if (!Files.exists(ks)) {
                    out += DoctorFinding(DoctorLevel.FAIL, "Cluster keystore not found: ${config.cluster.keystorePath}",
                        "Run `cluster cert regenerate` to create a self-signed keystore")
                } else {
                    out += DoctorFinding(DoctorLevel.OK, "Cluster TLS keystore present")
                }
            }
        } else {
            out += DoctorFinding(DoctorLevel.OK, "Cluster disabled (single-node mode)")
        }

        val dbType = config.database.type.lowercase()
        if (dbType in setOf("mysql", "mariadb", "postgresql", "postgres")) {
            val credsMissing = config.database.username.isBlank() &&
                System.getenv("NIMBUS_DB_USERNAME").isNullOrBlank()
            if (credsMissing) {
                out += DoctorFinding(DoctorLevel.WARN, "${config.database.type} configured without username",
                    "Set [database] username/password or use NIMBUS_DB_* env vars")
            } else {
                out += DoctorFinding(DoctorLevel.OK, "Database: ${config.database.type} @ ${config.database.host}:${config.database.port}/${config.database.name}")
            }
        } else {
            out += DoctorFinding(DoctorLevel.OK, "Database: SQLite (embedded)")
        }
        return out
    }

    private fun checkStorage(): List<DoctorFinding> {
        val out = mutableListOf<DoctorFinding>()
        val paths = listOf(
            "templates" to config.paths.templates,
            "services"  to config.paths.services,
            "logs"      to config.paths.logs,
        )
        for ((label, p) in paths) {
            val path = Path.of(p)
            when {
                !Files.exists(path) -> out += DoctorFinding(DoctorLevel.WARN, "paths.$label missing: $p",
                    "Nimbus will create it on demand, but verify the parent directory is writable")
                !Files.isWritable(path) -> out += DoctorFinding(DoctorLevel.FAIL, "paths.$label not writable: $p",
                    "Check filesystem permissions on $p")
                else -> out += DoctorFinding(DoctorLevel.OK, "paths.$label: $p")
            }
        }
        try {
            val servicesPath = Path.of(config.paths.services).toAbsolutePath()
            val existingAncestor = generateSequence(servicesPath) { it.parent }.firstOrNull { Files.exists(it) }
            if (existingAncestor != null) {
                val freeGb = Files.getFileStore(existingAncestor).usableSpace / (1024L * 1024 * 1024)
                out += when {
                    freeGb < 1  -> DoctorFinding(DoctorLevel.FAIL, "Only ${freeGb}GB free on services volume",
                        "Free up disk — new service starts and state sync will fail")
                    freeGb < 5  -> DoctorFinding(DoctorLevel.WARN, "Only ${freeGb}GB free on services volume",
                        "Consider freeing disk before scaling up")
                    else        -> DoctorFinding(DoctorLevel.OK, "${freeGb}GB free on services volume")
                }
            }
        } catch (_: Exception) {
            // Non-critical — skip silently
        }
        return out
    }

    private fun checkDatabase(db: DatabaseManager): List<DoctorFinding> = try {
        transaction(db.database) { exec("SELECT 1") }
        listOf(DoctorFinding(DoctorLevel.OK, "Database ping successful"))
    } catch (e: Exception) {
        listOf(DoctorFinding(DoctorLevel.FAIL, "Database ping failed: ${e.message}",
            "Check database credentials, host/port reachability and firewall rules"))
    }

    private fun checkServices(): List<DoctorFinding> {
        val out = mutableListOf<DoctorFinding>()
        val all = registry.getAll()
        if (all.isEmpty()) {
            out += DoctorFinding(DoctorLevel.OK, "No services registered yet")
            return out
        }

        val crashed = all.count { it.state == ServiceState.CRASHED }
        out += if (crashed == 0) {
            DoctorFinding(DoctorLevel.OK, "No crashed services")
        } else {
            DoctorFinding(DoctorLevel.WARN, "$crashed crashed service(s)",
                "Inspect with `logs <service>`, clean up with `purge crashed`")
        }

        val ready = all.filter { it.state == ServiceState.READY }
        val unhealthy = ready.count { !it.healthy }
        out += if (unhealthy == 0) {
            DoctorFinding(DoctorLevel.OK, "${ready.size} ready service(s), all reporting healthy")
        } else {
            DoctorFinding(DoctorLevel.WARN, "$unhealthy of ${ready.size} ready service(s) unhealthy",
                "Run `health` for per-service detail — likely low TPS or missing SDK reports")
        }

        val now = Instant.now()
        val stuck = all.filter {
            (it.state == ServiceState.STARTING || it.state == ServiceState.PREPARING) &&
                it.startedAt != null &&
                Duration.between(it.startedAt, now).toMinutes() > 10
        }
        if (stuck.isNotEmpty()) {
            out += DoctorFinding(DoctorLevel.WARN,
                "${stuck.size} service(s) stuck >10min in STARTING/PREPARING: ${stuck.joinToString { it.name }}",
                "Check `logs <service>` — the process likely never printed 'Done' or is deadlocked")
        }

        val flapping = all.filter { it.restartCount >= 3 }
        if (flapping.isNotEmpty()) {
            out += DoctorFinding(DoctorLevel.WARN,
                "${flapping.size} service(s) with ≥3 restarts: ${flapping.joinToString { "${it.name}(${it.restartCount})" }}",
                "Recurring crashes often indicate bad plugins, OOM or port conflicts — check logs")
        }
        return out
    }

    private fun checkCluster(nm: NodeManager): List<DoctorFinding> {
        val out = mutableListOf<DoctorFinding>()
        val total = nm.getNodeCount()
        val online = nm.getOnlineNodeCount()
        if (total == 0) {
            out += DoctorFinding(DoctorLevel.OK, "Cluster enabled, no agent nodes registered yet")
            return out
        }
        out += when {
            online == total -> DoctorFinding(DoctorLevel.OK, "$online/$total agent node(s) online")
            online == 0     -> DoctorFinding(DoctorLevel.FAIL, "0/$total agent node(s) online",
                "All agents disconnected — check agent logs and network connectivity")
            else            -> DoctorFinding(DoctorLevel.WARN, "$online/$total agent node(s) online",
                "One or more agents disconnected — check their logs")
        }
        val offline = nm.getAllNodes().filter { !it.isConnected }
        if (offline.isNotEmpty()) {
            val host = config.cluster.publicHost.ifBlank { "this controller" }
            out += DoctorFinding(DoctorLevel.WARN, "Offline nodes: ${offline.joinToString { it.nodeId }}",
                "Verify agents can reach $host:${config.cluster.agentPort} and that the fingerprint matches")
        }
        return out
    }
}

/** Structured, serializable doctor output — the same shape for CLI, REST and JSON. */
@Serializable
data class DoctorReport(
    val sections: List<DoctorSection>,
    val warnCount: Int,
    val failCount: Int,
    /** Overall status: `ok` / `warn` / `fail`. Convenient for CI exit codes. */
    val status: String,
)

@Serializable
data class DoctorSection(
    val name: String,
    val findings: List<DoctorFindingDto>,
)

@Serializable
data class DoctorFindingDto(
    val level: String,
    val message: String,
    val hint: String? = null,
)
