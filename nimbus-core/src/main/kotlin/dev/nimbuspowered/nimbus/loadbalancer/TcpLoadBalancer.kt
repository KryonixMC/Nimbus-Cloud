package dev.nimbuspowered.nimbus.loadbalancer

import dev.nimbuspowered.nimbus.config.LoadBalancerConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TcpLoadBalancer(
    val config: LoadBalancerConfig,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(TcpLoadBalancer::class.java)

    private var serverChannel: AsynchronousServerSocketChannel? = null
    private val strategy: LoadBalancerStrategy = when (config.strategy.lowercase()) {
        "round-robin" -> RoundRobinStrategy()
        else -> LeastPlayersStrategy()
    }

    val healthManager = BackendHealthManager(config, eventBus, scope)

    @Volatile private var running = false
    private val _totalConnections = AtomicLong(0)
    private val _activeConnections = AtomicInteger(0)
    private val _rejectedConnections = AtomicLong(0)
    private val _failedConnections = AtomicLong(0)
    private val connectionSemaphore = Semaphore(config.maxConnections)

    val totalConnections: Long get() = _totalConnections.get()
    val activeConnections: Int get() = _activeConnections.get()
    val rejectedConnections: Long get() = _rejectedConnections.get()
    val failedConnections: Long get() = _failedConnections.get()

    fun start(): Job {
        // Subscribe to service lifecycle for connection draining
        eventBus.on<NimbusEvent.ServiceStopping> { event ->
            val service = registry.get(event.serviceName)
            if (service != null) {
                healthManager.markDraining(service.host, service.port)
            }
        }
        eventBus.on<NimbusEvent.ServiceStopped> { event ->
            val service = registry.get(event.serviceName)
            if (service != null) {
                healthManager.remove(service.host, service.port)
            }
        }

        healthManager.start()

        return scope.launch(Dispatchers.IO) {
            val address = InetSocketAddress(config.bind, config.port)
            serverChannel = AsynchronousServerSocketChannel.open().bind(address)
            running = true
            logger.info("TCP Load Balancer started on {}:{} (strategy: {}, max connections: {})",
                config.bind, config.port, config.strategy, config.maxConnections)

            while (running && isActive) {
                try {
                    val clientChannel = acceptAsync(serverChannel!!)
                    if (!connectionSemaphore.tryAcquire()) {
                        clientChannel.closeSilently()
                        _rejectedConnections.incrementAndGet()
                        continue
                    }
                    launch {
                        try {
                            handleConnection(clientChannel)
                        } finally {
                            connectionSemaphore.release()
                        }
                    }
                } catch (e: Exception) {
                    if (running) logger.error("Error accepting connection: {}", e.message)
                }
            }
        }
    }

    fun stop() {
        running = false
        healthManager.stop()
        try {
            serverChannel?.close()
        } catch (e: Exception) {
            logger.warn("Error closing LB server socket: {}", e.message)
        }
        logger.info("TCP Load Balancer stopped")
    }

    private suspend fun acceptAsync(server: AsynchronousServerSocketChannel): AsynchronousSocketChannel {
        return suspendCancellableCoroutine { cont ->
            server.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Void?> {
                override fun completed(result: AsynchronousSocketChannel, attachment: Void?) {
                    cont.resumeWith(Result.success(result))
                }
                override fun failed(exc: Throwable, attachment: Void?) {
                    cont.resumeWith(Result.failure(exc))
                }
            })
        }
    }

    private suspend fun handleConnection(client: AsynchronousSocketChannel) {
        _totalConnections.incrementAndGet()
        _activeConnections.incrementAndGet()
        var backendChannel: AsynchronousSocketChannel? = null
        var backend: BackendTarget? = null
        try {
            backend = selectBackend()
            if (backend == null) {
                logger.warn("No backend proxy available for incoming connection")
                return
            }

            backendChannel = AsynchronousSocketChannel.open()
            try {
                withTimeout(config.connectionTimeout.toLong()) {
                    connectAsync(backendChannel, InetSocketAddress(backend.host, backend.port))
                }
            } catch (e: Exception) {
                logger.warn("Failed to connect to backend {}:{}: {}", backend.host, backend.port, e.message)
                _failedConnections.incrementAndGet()
                healthManager.recordFailure(backend.host, backend.port)
                return
            }

            healthManager.incrementConnections(backend.host, backend.port)

            // If PROXY protocol is enabled, send PROXY protocol v2 header
            if (config.proxyProtocol) {
                val clientAddr = client.remoteAddress as? InetSocketAddress
                if (clientAddr != null) {
                    val header = ProxyProtocolV2.encode(clientAddr)
                    writeAsync(backendChannel, ByteBuffer.wrap(header))
                }
            }

            // Relay bytes bidirectionally with idle timeout watchdog
            val lastActivity = AtomicLong(System.currentTimeMillis())

            coroutineScope {
                val job1 = launch { relay(client, backendChannel, config.bufferSize, lastActivity) }
                val job2 = launch { relay(backendChannel, client, config.bufferSize, lastActivity) }
                launch {
                    // Idle timeout watchdog
                    while (isActive) {
                        delay(config.idleTimeout.toLong() / 2)
                        if (System.currentTimeMillis() - lastActivity.get() > config.idleTimeout) {
                            cancel()
                        }
                    }
                }
                // When either relay direction ends, cancel the other to avoid half-open lingering
                launch { job1.join(); cancel() }
                launch { job2.join(); cancel() }
            }
        } catch (_: Exception) {
            // Connection closed or timed out
        } finally {
            _activeConnections.decrementAndGet()
            if (backend != null) {
                healthManager.decrementConnections(backend.host, backend.port)
            }
            client.closeSilently()
            backendChannel?.closeSilently()
        }
    }

    private fun selectBackend(): BackendTarget? {
        val proxyServices = registry.getAll().filter { service ->
            service.state == ServiceState.READY &&
                groupManager.getGroup(service.groupName)
                    ?.config?.group?.software == ServerSoftware.VELOCITY
        }
        if (proxyServices.isEmpty()) return null

        // Register any new backends with health manager
        proxyServices.forEach { healthManager.ensureTracked(it.host, it.port) }

        // Filter to healthy backends only
        val healthy = proxyServices.filter { healthManager.isHealthy(it.host, it.port) }

        // Fallback: if ALL backends are unhealthy, try any READY one (avoid total outage)
        val candidates = healthy.ifEmpty {
            logger.warn("All backends unhealthy — falling back to any READY backend")
            proxyServices
        }

        val chosen = strategy.select(candidates)
        return BackendTarget(chosen.host, chosen.port)
    }

    private suspend fun connectAsync(channel: AsynchronousSocketChannel, address: InetSocketAddress) {
        suspendCancellableCoroutine<Void?> { cont ->
            channel.connect(address, null, object : CompletionHandler<Void?, Void?> {
                override fun completed(result: Void?, att: Void?) {
                    cont.resumeWith(Result.success(result))
                }
                override fun failed(exc: Throwable, att: Void?) {
                    cont.resumeWith(Result.failure(exc))
                }
            })
        }
    }

    private suspend fun relay(
        from: AsynchronousSocketChannel,
        to: AsynchronousSocketChannel,
        bufferSize: Int,
        lastActivity: AtomicLong
    ) {
        val buffer = ByteBuffer.allocate(bufferSize)
        while (from.isOpen && to.isOpen) {
            buffer.clear()
            val read = readAsync(from, buffer)
            if (read <= 0) break
            lastActivity.set(System.currentTimeMillis())
            buffer.flip()
            writeAsync(to, buffer)
        }
    }

    private suspend fun readAsync(channel: AsynchronousSocketChannel, buffer: ByteBuffer): Int {
        return suspendCancellableCoroutine { cont ->
            channel.read(buffer, null, object : CompletionHandler<Int, Void?> {
                override fun completed(result: Int, att: Void?) {
                    cont.resumeWith(Result.success(result))
                }
                override fun failed(exc: Throwable, att: Void?) {
                    cont.resumeWith(Result.failure(exc))
                }
            })
        }
    }

    private suspend fun writeAsync(channel: AsynchronousSocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            suspendCancellableCoroutine<Int> { cont ->
                channel.write(buffer, null, object : CompletionHandler<Int, Void?> {
                    override fun completed(result: Int, att: Void?) {
                        cont.resumeWith(Result.success(result))
                    }
                    override fun failed(exc: Throwable, att: Void?) {
                        cont.resumeWith(Result.failure(exc))
                    }
                })
            }
        }
    }

    private fun AsynchronousSocketChannel.closeSilently() {
        try { close() } catch (_: Exception) {}
    }

    data class BackendTarget(val host: String, val port: Int)
}
