package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxies file operations to remote agent nodes via the cluster WebSocket.
 * Uses [CompletableDeferred] to await agent responses with a 10-second timeout.
 */
class RemoteFileProxy {

    private val logger = LoggerFactory.getLogger(RemoteFileProxy::class.java)

    private val pendingListRequests = ConcurrentHashMap<String, CompletableDeferred<ClusterMessage.FileListResponse>>()
    private val pendingReadRequests = ConcurrentHashMap<String, CompletableDeferred<ClusterMessage.FileReadResponse>>()
    private val pendingWriteRequests = ConcurrentHashMap<String, CompletableDeferred<ClusterMessage.FileWriteResponse>>()
    private val pendingDeleteRequests = ConcurrentHashMap<String, CompletableDeferred<ClusterMessage.FileDeleteResponse>>()

    suspend fun listFiles(node: NodeConnection, serviceName: String, path: String): ClusterMessage.FileListResponse {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ClusterMessage.FileListResponse>()
        pendingListRequests[requestId] = deferred
        try {
            node.send(ClusterMessage.FileListRequest(serviceName, path, requestId))
            return withTimeout(10_000) { deferred.await() }
        } finally {
            pendingListRequests.remove(requestId)
        }
    }

    suspend fun readFile(node: NodeConnection, serviceName: String, path: String): ClusterMessage.FileReadResponse {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ClusterMessage.FileReadResponse>()
        pendingReadRequests[requestId] = deferred
        try {
            node.send(ClusterMessage.FileReadRequest(serviceName, path, requestId))
            return withTimeout(10_000) { deferred.await() }
        } finally {
            pendingReadRequests.remove(requestId)
        }
    }

    suspend fun writeFile(node: NodeConnection, serviceName: String, path: String, content: String): ClusterMessage.FileWriteResponse {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ClusterMessage.FileWriteResponse>()
        pendingWriteRequests[requestId] = deferred
        try {
            node.send(ClusterMessage.FileWriteRequest(serviceName, path, content, requestId))
            return withTimeout(10_000) { deferred.await() }
        } finally {
            pendingWriteRequests.remove(requestId)
        }
    }

    suspend fun deleteFile(node: NodeConnection, serviceName: String, path: String): ClusterMessage.FileDeleteResponse {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ClusterMessage.FileDeleteResponse>()
        pendingDeleteRequests[requestId] = deferred
        try {
            node.send(ClusterMessage.FileDeleteRequest(serviceName, path, requestId))
            return withTimeout(10_000) { deferred.await() }
        } finally {
            pendingDeleteRequests.remove(requestId)
        }
    }

    /** Called by [ClusterWebSocketHandler] when a file response arrives from an agent. */
    fun onFileListResponse(response: ClusterMessage.FileListResponse) {
        pendingListRequests[response.requestId]?.complete(response)
    }

    fun onFileReadResponse(response: ClusterMessage.FileReadResponse) {
        pendingReadRequests[response.requestId]?.complete(response)
    }

    fun onFileWriteResponse(response: ClusterMessage.FileWriteResponse) {
        pendingWriteRequests[response.requestId]?.complete(response)
    }

    fun onFileDeleteResponse(response: ClusterMessage.FileDeleteResponse) {
        pendingDeleteRequests[response.requestId]?.complete(response)
    }
}
