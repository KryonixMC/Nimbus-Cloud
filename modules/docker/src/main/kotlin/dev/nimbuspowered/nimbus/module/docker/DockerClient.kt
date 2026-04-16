package dev.nimbuspowered.nimbus.module.docker

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * Thin Kotlin wrapper over the Docker Engine HTTP API.
 *
 * All methods are synchronous — callers that need cancellation should invoke
 * them inside `withContext(Dispatchers.IO)`. This keeps the client itself
 * coroutine-agnostic and easy to reason about.
 *
 * The API version is pinned to v1.41 (Docker 20.10 / ~2021) for broad
 * compatibility; Podman also implements this version.
 */
class DockerClient(endpoint: String) {

    private val logger = LoggerFactory.getLogger(DockerClient::class.java)
    private val transport = DockerTransport(endpoint)
    private val json = Json { ignoreUnknownKeys = true }

    private val apiPrefix = "/v1.41"

    /** Probes the daemon — returns true on HTTP 200 from /_ping. */
    fun ping(): Boolean {
        return try {
            transport.request("GET", "$apiPrefix/_ping").status == 200
        } catch (e: Exception) {
            logger.debug("Docker ping failed: {}", e.message)
            false
        }
    }

    /** Returns the `Version` + `ApiVersion` from /version, or null if unreachable. */
    fun version(): DockerVersionInfo? {
        return try {
            val resp = transport.request("GET", "$apiPrefix/version")
            if (resp.status != 200) return null
            val obj = json.parseToJsonElement(resp.bodyText).jsonObject
            DockerVersionInfo(
                version = obj["Version"]?.jsonPrimitive?.content ?: "",
                apiVersion = obj["ApiVersion"]?.jsonPrimitive?.content ?: "",
                os = obj["Os"]?.jsonPrimitive?.content ?: "",
                arch = obj["Arch"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            logger.debug("Docker /version failed: {}", e.message)
            null
        }
    }

    /**
     * Lists containers matching the given label filters. Keys and values are
     * combined into Docker's `filters` JSON syntax (`{"label":["k=v", ...]}`).
     */
    fun listContainers(labels: Map<String, String> = emptyMap(), all: Boolean = true): List<JsonObject> {
        val filtersObj = buildJsonObject {
            if (labels.isNotEmpty()) {
                putJsonArray("label") {
                    for ((k, v) in labels) add("$k=$v")
                }
            }
        }
        val query = StringBuilder("?all=").append(if (all) "1" else "0")
        if (labels.isNotEmpty()) {
            query.append("&filters=").append(URLEncoder.encode(filtersObj.toString(), Charsets.UTF_8))
        }
        val resp = transport.request("GET", "$apiPrefix/containers/json$query")
        if (resp.status != 200) throw DockerException("listContainers failed (${resp.status}): ${resp.bodyText}")
        return json.parseToJsonElement(resp.bodyText).jsonArray.map { it.jsonObject }
    }

    fun inspect(id: String): JsonObject? {
        val resp = transport.request("GET", "$apiPrefix/containers/$id/json")
        if (resp.status == 404) return null
        if (resp.status != 200) throw DockerException("inspect failed (${resp.status}): ${resp.bodyText}")
        return json.parseToJsonElement(resp.bodyText).jsonObject
    }

    /**
     * Creates a container. Returns the container ID.
     *
     * The spec uses Docker's raw Create Container JSON — we let callers build it
     * via [buildContainerSpec] so we don't need a 30-field data class.
     */
    fun createContainer(name: String, spec: JsonObject): String {
        val body = spec.toString().toByteArray(Charsets.UTF_8)
        val path = "$apiPrefix/containers/create?name=${URLEncoder.encode(name, Charsets.UTF_8)}"
        val resp = transport.request("POST", path, body = body)
        if (resp.status != 201) throw DockerException("create container '$name' failed (${resp.status}): ${resp.bodyText}")
        val obj = json.parseToJsonElement(resp.bodyText).jsonObject
        return obj["Id"]?.jsonPrimitive?.content
            ?: throw DockerException("create container response missing Id: ${resp.bodyText}")
    }

    fun startContainer(id: String) {
        val resp = transport.request("POST", "$apiPrefix/containers/$id/start")
        if (resp.status !in listOf(204, 304)) {
            throw DockerException("start container failed (${resp.status}): ${resp.bodyText}")
        }
    }

    /**
     * Graceful stop — Docker sends SIGTERM, waits [timeoutSeconds], then SIGKILL.
     * For Minecraft we already issue `stop` via the attach stdin before this, so
     * SIGTERM is the hard fallback.
     */
    fun stopContainer(id: String, timeoutSeconds: Int = 10) {
        val resp = transport.request("POST", "$apiPrefix/containers/$id/stop?t=$timeoutSeconds")
        if (resp.status !in listOf(204, 304, 404)) {
            throw DockerException("stop container failed (${resp.status}): ${resp.bodyText}")
        }
    }

    fun killContainer(id: String, signal: String = "SIGKILL") {
        val resp = transport.request("POST", "$apiPrefix/containers/$id/kill?signal=$signal")
        if (resp.status !in listOf(204, 404)) {
            throw DockerException("kill container failed (${resp.status}): ${resp.bodyText}")
        }
    }

    fun removeContainer(id: String, force: Boolean = true, volumes: Boolean = true) {
        val q = "?force=${if (force) 1 else 0}&v=${if (volumes) 1 else 0}"
        val resp = transport.request("DELETE", "$apiPrefix/containers/$id$q")
        if (resp.status !in listOf(204, 404)) {
            throw DockerException("remove container failed (${resp.status}): ${resp.bodyText}")
        }
    }

    /**
     * Attaches to the container's stdin+stdout (TTY-mode streams — no framing).
     * The returned [DockerStream] is a hijacked connection: writes go to the
     * container's stdin, reads produce stdout+stderr.
     */
    fun attach(id: String): DockerStream {
        val path = "$apiPrefix/containers/$id/attach?stream=1&stdin=1&stdout=1&stderr=1"
        val stream = transport.openStream("POST", path)
        if (stream.status != 200 && stream.status != 101) {
            stream.close()
            throw DockerException("attach failed (${stream.status})")
        }
        return stream
    }

    /**
     * One-shot stats read. When TTY is enabled (as we configure our containers),
     * Docker returns a JSON object with memory_stats / cpu_stats.
     */
    fun stats(id: String): DockerStats? {
        val resp = transport.request("GET", "$apiPrefix/containers/$id/stats?stream=false&one-shot=true")
        if (resp.status != 200) return null
        val text = resp.bodyText.trim()
        if (text.isEmpty()) return null
        val obj = json.parseToJsonElement(text).jsonObject
        val memory = obj["memory_stats"]?.jsonObject?.get("usage")?.jsonPrimitive?.longOrNull ?: 0L
        val memLimit = obj["memory_stats"]?.jsonObject?.get("limit")?.jsonPrimitive?.longOrNull ?: 0L
        val cpuTotal = obj["cpu_stats"]?.jsonObject?.get("cpu_usage")?.jsonObject?.get("total_usage")?.jsonPrimitive?.longOrNull ?: 0L
        val preCpuTotal = obj["precpu_stats"]?.jsonObject?.get("cpu_usage")?.jsonObject?.get("total_usage")?.jsonPrimitive?.longOrNull ?: 0L
        val sysCpu = obj["cpu_stats"]?.jsonObject?.get("system_cpu_usage")?.jsonPrimitive?.longOrNull ?: 0L
        val preSysCpu = obj["precpu_stats"]?.jsonObject?.get("system_cpu_usage")?.jsonPrimitive?.longOrNull ?: 0L
        val onlineCpus = obj["cpu_stats"]?.jsonObject?.get("online_cpus")?.jsonPrimitive?.doubleOrNull ?: 1.0

        val cpuDelta = (cpuTotal - preCpuTotal).coerceAtLeast(0L)
        val sysDelta = (sysCpu - preSysCpu).coerceAtLeast(0L)
        val cpuPercent = if (sysDelta > 0) (cpuDelta.toDouble() / sysDelta.toDouble()) * onlineCpus * 100.0 else 0.0

        return DockerStats(
            memoryBytes = memory,
            memoryLimitBytes = memLimit,
            cpuPercent = cpuPercent
        )
    }

    /** Creates a network with [name] if it doesn't already exist. No-op when present. */
    fun ensureNetwork(name: String) {
        // Check existence first so we don't spam errors on the common case.
        val listResp = transport.request("GET", "$apiPrefix/networks?filters=" +
            URLEncoder.encode("{\"name\":[\"$name\"]}", Charsets.UTF_8))
        if (listResp.status == 200) {
            val arr = json.parseToJsonElement(listResp.bodyText).jsonArray
            if (arr.isNotEmpty()) return
        }
        val body = buildJsonObject {
            put("Name", name)
            put("Driver", "bridge")
            put("CheckDuplicate", true)
        }.toString().toByteArray(Charsets.UTF_8)
        val resp = transport.request("POST", "$apiPrefix/networks/create", body = body)
        if (resp.status !in listOf(201, 409)) {
            throw DockerException("create network '$name' failed (${resp.status}): ${resp.bodyText}")
        }
    }

    /** Pulls an image if not already present locally. No-op if found. */
    fun ensureImage(image: String) {
        val inspectResp = transport.request("GET", "$apiPrefix/images/${URLEncoder.encode(image, Charsets.UTF_8)}/json")
        if (inspectResp.status == 200) return
        if (inspectResp.status != 404) {
            logger.warn("Image inspect for '{}' returned {}: {}", image, inspectResp.status, inspectResp.bodyText)
        }
        logger.info("Pulling Docker image '{}' (not present locally)", image)
        // /images/create returns a stream of JSON progress lines; we drain it.
        val stream = transport.openStream("POST", "$apiPrefix/images/create?fromImage=${URLEncoder.encode(image, Charsets.UTF_8)}")
        try {
            if (stream.status != 200) throw DockerException("pull image '$image' failed (${stream.status})")
            stream.input.bufferedReader().use { it.readText() }
        } finally {
            stream.close()
        }
    }

    /**
     * Builds the Create Container request body. Keeping this as a helper avoids
     * a 25-field data class — callers stay concise and only set what they need.
     *
     * @param image         Base image (e.g. `eclipse-temurin:21-jre`)
     * @param cmd           Command array (JVM command line the ServiceFactory built)
     * @param workDir       Container-side working directory (where the jar is)
     * @param hostWorkDir   Host directory to bind-mount into [workDir]
     * @param env           Environment variables (key=value strings)
     * @param portMappings  Map of container port → host port (TCP)
     * @param memoryBytes   Hard memory limit in bytes (0 = no limit)
     * @param cpuLimit      CPU quota in cores (2.0 = 2 full cores). 0.0 = no limit.
     * @param network       Docker network name to attach to
     * @param labels        Labels to set (nimbus tags for later discovery)
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun buildContainerSpec(
        image: String,
        cmd: List<String>,
        workDir: String,
        hostWorkDir: String,
        env: Map<String, String>,
        portMappings: Map<Int, Int>,
        memoryBytes: Long,
        cpuLimit: Double,
        network: String,
        labels: Map<String, String>
    ): JsonObject = buildJsonObject {
        put("Image", image)
        put("WorkingDir", workDir)
        put("Tty", true)
        put("OpenStdin", true)
        put("AttachStdin", true)
        put("AttachStdout", true)
        put("AttachStderr", true)
        put("StdinOnce", false)

        putJsonArray("Cmd") {
            for (part in cmd) add(part)
        }

        putJsonArray("Env") {
            for ((k, v) in env) add("$k=$v")
        }

        putJsonObject("Labels") {
            for ((k, v) in labels) put(k, v)
        }

        putJsonObject("ExposedPorts") {
            for ((containerPort, _) in portMappings) {
                putJsonObject("$containerPort/tcp") {}
            }
        }

        putJsonObject("HostConfig") {
            putJsonArray("Binds") {
                // Bind-mount the service work dir as /server (and any other dirs we need).
                add("$hostWorkDir:$workDir")
            }
            put("NetworkMode", network)
            put("RestartPolicy", buildJsonObject {
                put("Name", "no")
            })
            if (memoryBytes > 0) {
                put("Memory", memoryBytes)
                put("MemorySwap", memoryBytes) // disable swap (same as Memory)
            }
            if (cpuLimit > 0.0) {
                // Docker uses NanoCpus: 1.0 CPU = 1_000_000_000
                put("NanoCpus", (cpuLimit * 1_000_000_000.0).toLong())
            }
            putJsonObject("PortBindings") {
                for ((containerPort, hostPort) in portMappings) {
                    putJsonArray("$containerPort/tcp") {
                        addJsonObject {
                            put("HostIp", "0.0.0.0")
                            put("HostPort", hostPort.toString())
                        }
                    }
                }
            }
        }
    }
}

data class DockerVersionInfo(
    val version: String,
    val apiVersion: String,
    val os: String,
    val arch: String
)

data class DockerStats(
    val memoryBytes: Long,
    val memoryLimitBytes: Long,
    val cpuPercent: Double
)
