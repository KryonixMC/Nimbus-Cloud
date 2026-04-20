package dev.nimbuspowered.nimbus.service.spawn

import org.slf4j.LoggerFactory

/**
 * Linux sandbox backend using `systemd-run --user --scope` to put the service
 * JVM into its own cgroup v2 with kernel-enforced memory and CPU limits. Cheap,
 * zero extra daemons beyond the systemd that every modern distro already runs.
 *
 * Availability is probed once at first access and cached for the process
 * lifetime. If systemd-run is not on PATH or the user systemd bus is not
 * reachable (e.g. inside Docker, WSL1, rootless containers without lingering),
 * this backend reports unavailable and the spawn path falls back to [SandboxMode.BARE].
 *
 * Memory-accounting: when a service is spawned through this wrapper, its
 * current RSS can be read from `/sys/fs/cgroup/<unit>/memory.current` which is
 * more accurate than `/proc/<pid>/status` (it excludes shared pages and
 * includes page-cache reclaim correctly). This is wired separately by
 * `ServiceMemoryResolver` and not required for correctness here.
 */
object SystemdRunSandbox {

    private val logger = LoggerFactory.getLogger(SystemdRunSandbox::class.java)

    @Volatile
    private var cachedAvailable: Boolean? = null

    /**
     * Returns `true` when systemd-run is on PATH AND the user systemd bus is
     * reachable. Result is cached after the first call.
     */
    fun isAvailable(): Boolean {
        cachedAvailable?.let { return it }
        synchronized(this) {
            cachedAvailable?.let { return it }
            val available = probe()
            cachedAvailable = available
            logger.info(
                if (available) "systemd-run sandbox available — services can run under cgroup v2 scopes"
                else "systemd-run sandbox unavailable on this platform — services will run as unconstrained processes"
            )
            return available
        }
    }

    /**
     * Overrides the cached probe result. Intended for tests only.
     */
    internal fun overrideAvailabilityForTesting(value: Boolean?) {
        cachedAvailable = value
    }

    private fun probe(): Boolean {
        if (!isLinux()) return false
        if (!commandExists("systemd-run")) return false
        return userBusReachable()
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun commandExists(name: String): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "command -v $name")
            .redirectErrorStream(true)
            .start()
        val finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }

    private fun userBusReachable(): Boolean = try {
        val p = ProcessBuilder("systemctl", "--user", "show-environment")
            .redirectErrorStream(true)
            .start()
        val finished = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            false
        } else {
            p.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Returns the given [command] wrapped in a `systemd-run --user --scope`
     * invocation that applies the given [limits]. The unit name is derived
     * from [serviceName] and sanitized for systemd.
     *
     * If [limits] is empty, the command is returned unchanged — there is no
     * point paying the systemd-run indirection if we're not enforcing anything.
     */
    fun wrapCommand(serviceName: String, command: List<String>, limits: SandboxLimits): List<String> {
        if (limits.isEmpty()) return command
        val unit = sanitizeUnitName(serviceName)
        val wrapped = mutableListOf(
            "systemd-run",
            "--user",
            "--scope",
            "--quiet",
            "--unit=$unit"
        )
        if (limits.memoryMb > 0) {
            wrapped += "-p"
            wrapped += "MemoryMax=${limits.memoryMb}M"
            // MemoryHigh is a soft limit just below the hard cap — gives the
            // kernel a chance to reclaim before OOM-killing.
            val high = (limits.memoryMb * 95L) / 100L
            if (high > 0 && high < limits.memoryMb) {
                wrapped += "-p"
                wrapped += "MemoryHigh=${high}M"
            }
        }
        if (limits.cpuQuota > 0.0) {
            val percent = (limits.cpuQuota * 100.0).toInt().coerceAtLeast(1)
            wrapped += "-p"
            wrapped += "CPUQuota=${percent}%"
        }
        if (limits.tasksMax > 0) {
            wrapped += "-p"
            wrapped += "TasksMax=${limits.tasksMax}"
        }
        wrapped += "--"
        wrapped += command
        return wrapped
    }

    /**
     * systemd unit names allow `[a-zA-Z0-9:_.\\-]`. Service names are already
     * validated against `[a-zA-Z0-9_-]`, so a straight prefix + replace is enough.
     */
    internal fun sanitizeUnitName(serviceName: String): String =
        "nimbus-" + serviceName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
}
