package dev.nimbuspowered.nimbus.loadbalancer

import dev.nimbuspowered.nimbus.config.LoadBalancerConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.service.ServerListPing
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BackendHealthManager(
    private val config: LoadBalancerConfig,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(BackendHealthManager::class.java)
    private val backends = ConcurrentHashMap<String, BackendState>()
    private var healthJob: Job? = null

    enum class HealthStatus { HEALTHY, UNHEALTHY }

    data class BackendState(
        val host: String,
        val port: Int,
        val status: AtomicReference<HealthStatus> = AtomicReference(HealthStatus.HEALTHY),
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
        val consecutiveSuccesses: AtomicInteger = AtomicInteger(0),
        val activeConnections: AtomicInteger = AtomicInteger(0),
        @Volatile var lastCheckTime: Instant? = null,
        @Volatile var draining: Boolean = false
    )

    fun start(): Job {
        healthJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(config.healthCheckInterval * 1000L)
                checkAll()
            }
        }
        return healthJob!!
    }

    fun stop() {
        healthJob?.cancel()
    }

    private suspend fun checkAll() {
        for (backend in backends.values) {
            if (backend.draining) continue

            val result = ServerListPing.ping(
                host = backend.host,
                port = backend.port,
                timeout = config.healthCheckTimeout
            )

            val success = result != null
            backend.lastCheckTime = Instant.now()

            if (success) {
                backend.consecutiveFailures.set(0)
                val successes = backend.consecutiveSuccesses.incrementAndGet()
                val oldStatus = backend.status.get()
                if (oldStatus == HealthStatus.UNHEALTHY && successes >= config.healthyThreshold
                    && backend.status.compareAndSet(HealthStatus.UNHEALTHY, HealthStatus.HEALTHY)) {
                    logger.info("Backend {}:{} is now HEALTHY", backend.host, backend.port)
                    scope.launch {
                        eventBus.emit(NimbusEvent.LoadBalancerBackendHealthChanged(
                            backend.host, backend.port, oldStatus.name, HealthStatus.HEALTHY.name
                        ))
                    }
                }
            } else {
                backend.consecutiveSuccesses.set(0)
                val failures = backend.consecutiveFailures.incrementAndGet()
                val oldStatus = backend.status.get()
                if (oldStatus == HealthStatus.HEALTHY && failures >= config.unhealthyThreshold
                    && backend.status.compareAndSet(HealthStatus.HEALTHY, HealthStatus.UNHEALTHY)) {
                    logger.warn("Backend {}:{} is now UNHEALTHY after {} consecutive failures",
                        backend.host, backend.port, failures)
                    scope.launch {
                        eventBus.emit(NimbusEvent.LoadBalancerBackendHealthChanged(
                            backend.host, backend.port, oldStatus.name, HealthStatus.UNHEALTHY.name
                        ))
                    }
                }
            }
        }
    }

    fun ensureTracked(host: String, port: Int) {
        val key = "$host:$port"
        backends.computeIfAbsent(key) { BackendState(host, port) }
    }

    fun isHealthy(host: String, port: Int): Boolean {
        val key = "$host:$port"
        val state = backends[key] ?: return true // Unknown backends assumed healthy
        return !state.draining && state.status.get() == HealthStatus.HEALTHY
    }

    fun incrementConnections(host: String, port: Int) {
        backends["$host:$port"]?.activeConnections?.incrementAndGet()
    }

    fun decrementConnections(host: String, port: Int) {
        backends["$host:$port"]?.activeConnections?.decrementAndGet()
    }

    fun recordFailure(host: String, port: Int) {
        val state = backends["$host:$port"] ?: return
        state.consecutiveSuccesses.set(0)
        val failures = state.consecutiveFailures.incrementAndGet()
        if (failures >= config.unhealthyThreshold &&
            state.status.compareAndSet(HealthStatus.HEALTHY, HealthStatus.UNHEALTHY)) {
            logger.warn("Backend {}:{} marked UNHEALTHY via passive failure detection", host, port)
            scope.launch {
                eventBus.emit(NimbusEvent.LoadBalancerBackendHealthChanged(
                    host, port, HealthStatus.HEALTHY.name, HealthStatus.UNHEALTHY.name
                ))
            }
        }
    }

    fun markDraining(host: String, port: Int) {
        val state = backends["$host:$port"] ?: return
        state.draining = true
        logger.info("Backend {}:{} marked as draining", host, port)
    }

    fun remove(host: String, port: Int) {
        backends.remove("$host:$port")
    }

    fun getAll(): List<BackendState> = backends.values.toList()

    fun get(host: String, port: Int): BackendState? = backends["$host:$port"]
}
