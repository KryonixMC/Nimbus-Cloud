package dev.nimbus.loadbalancer

import dev.nimbus.config.LoadBalancerConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler

class TcpLoadBalancer(
    private val config: LoadBalancerConfig,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(TcpLoadBalancer::class.java)

    private var serverChannel: AsynchronousServerSocketChannel? = null
    private val strategy: LoadBalancerStrategy = when (config.strategy.lowercase()) {
        "round-robin" -> RoundRobinStrategy()
        else -> LeastPlayersStrategy()
    }

    @Volatile private var running = false
    @Volatile var totalConnections: Long = 0; private set
    @Volatile var activeConnections: Int = 0; private set

    fun start(): Job = scope.launch(Dispatchers.IO) {
        val address = InetSocketAddress(config.bind, config.port)
        serverChannel = AsynchronousServerSocketChannel.open().bind(address)
        running = true
        logger.info("TCP Load Balancer started on {}:{} (strategy: {})",
            config.bind, config.port, config.strategy)

        while (running && isActive) {
            try {
                val clientChannel = acceptAsync(serverChannel!!)
                launch {
                    handleConnection(clientChannel)
                }
            } catch (e: Exception) {
                if (running) logger.error("Error accepting connection: {}", e.message)
            }
        }
    }

    fun stop() {
        running = false
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
        totalConnections++
        activeConnections++
        try {
            // Read initial bytes to parse Minecraft handshake (for logging/future use)
            // But we are Layer-4 — we just pick a backend and relay bytes
            val backend = selectBackend()
            if (backend == null) {
                logger.warn("No backend proxy available for incoming connection")
                client.close()
                return
            }

            val backendChannel = AsynchronousSocketChannel.open()
            try {
                connectAsync(backendChannel, InetSocketAddress(backend.host, backend.port))
            } catch (e: Exception) {
                logger.warn("Failed to connect to backend {}:{}: {}", backend.host, backend.port, e.message)
                client.close()
                return
            }

            // If PROXY protocol is enabled, send PROXY protocol v2 header
            if (config.proxyProtocol) {
                val clientAddr = client.remoteAddress as? InetSocketAddress
                if (clientAddr != null) {
                    val header = ProxyProtocolV2.encode(clientAddr)
                    writeAsync(backendChannel, ByteBuffer.wrap(header))
                }
            }

            // Relay bytes bidirectionally
            coroutineScope {
                val bufSize = config.bufferSize
                launch { relay(client, backendChannel, bufSize) }
                launch { relay(backendChannel, client, bufSize) }
            }
        } catch (_: Exception) {
            // Connection closed
        } finally {
            activeConnections--
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun selectBackend(): BackendTarget? {
        val proxyServices = registry.getAll().filter { service ->
            service.state == ServiceState.READY &&
                groupManager.getGroup(service.groupName)
                    ?.config?.group?.software == ServerSoftware.VELOCITY
        }
        if (proxyServices.isEmpty()) return null
        val chosen = strategy.select(proxyServices)
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

    private suspend fun relay(from: AsynchronousSocketChannel, to: AsynchronousSocketChannel, bufferSize: Int) {
        val buffer = ByteBuffer.allocate(bufferSize)
        try {
            while (from.isOpen && to.isOpen) {
                buffer.clear()
                val read = readAsync(from, buffer)
                if (read <= 0) break
                buffer.flip()
                writeAsync(to, buffer)
            }
        } catch (_: Exception) {
            // Connection closed
        } finally {
            try { from.close() } catch (_: Exception) {}
            try { to.close() } catch (_: Exception) {}
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

    data class BackendTarget(val host: String, val port: Int)
}
