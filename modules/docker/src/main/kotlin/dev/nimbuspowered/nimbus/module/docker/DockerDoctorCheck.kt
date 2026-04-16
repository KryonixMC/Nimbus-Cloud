package dev.nimbuspowered.nimbus.module.docker

import dev.nimbuspowered.nimbus.module.DoctorCheck
import dev.nimbuspowered.nimbus.module.DoctorFinding
import dev.nimbuspowered.nimbus.module.DoctorLevel
import kotlinx.serialization.json.jsonPrimitive

class DockerDoctorCheck(
    private val client: DockerClient,
    private val configManager: DockerConfigManager
) : DoctorCheck {

    override val section: String = "Docker"

    override suspend fun run(): List<DoctorFinding> {
        val cfg = configManager.config.docker
        if (!cfg.enabled) {
            return listOf(DoctorFinding(
                DoctorLevel.OK,
                "Docker module is disabled — services run as plain processes."
            ))
        }

        if (!client.ping()) {
            return listOf(DoctorFinding(
                DoctorLevel.FAIL,
                "Docker daemon not reachable at ${cfg.socket}",
                hint = "Start Docker, check the socket path, or set `enabled = false` in config/modules/docker/docker.toml"
            ))
        }

        val findings = mutableListOf<DoctorFinding>()
        val v = client.version()
        if (v != null) {
            findings += DoctorFinding(
                DoctorLevel.OK,
                "Daemon reachable — ${v.version} (api ${v.apiVersion}, ${v.os}/${v.arch})"
            )
            // Engine API v1.41 is the minimum — older daemons may miss endpoints.
            val parts = v.apiVersion.split('.').mapNotNull { it.toIntOrNull() }
            val major = parts.getOrNull(0) ?: 0
            val minor = parts.getOrNull(1) ?: 0
            if (major < 1 || (major == 1 && minor < 41)) {
                findings += DoctorFinding(
                    DoctorLevel.WARN,
                    "Daemon API version ${v.apiVersion} is older than the 1.41 target",
                    hint = "Upgrade Docker to 20.10 or newer for full compatibility"
                )
            }
        }

        val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
            .getOrNull().orEmpty()
        val running = containers.count { (it["State"]?.jsonPrimitive?.content ?: "") == "running" }
        val stale = containers.size - running
        findings += DoctorFinding(
            DoctorLevel.OK,
            "Nimbus-managed containers: $running running, $stale stopped"
        )
        if (stale > 5) {
            findings += DoctorFinding(
                DoctorLevel.WARN,
                "$stale stopped Nimbus containers are accumulating",
                hint = "Run `docker prune` (or POST /api/docker/prune) to remove them"
            )
        }
        return findings
    }
}
