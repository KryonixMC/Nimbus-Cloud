package dev.nimbuspowered.nimbus.cli

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * REST client for tab-completion requests to the controller.
 * Uses POST /api/console/complete for stateless completion lookups.
 */
class CompletionClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val token: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CompleteRequest(val buffer: String, val cursor: Int = -1)

    @Serializable
    private data class CompleteResponse(val candidates: List<String> = emptyList())

    /**
     * Fetches tab-completion candidates from the controller.
     * Returns an empty list on failure (graceful degradation).
     */
    suspend fun complete(buffer: String): List<String> {
        return try {
            val response = httpClient.post("$baseUrl/api/console/complete") {
                contentType(ContentType.Application.Json)
                if (token.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                setBody(json.encodeToString(CompleteRequest(buffer)))
            }
            if (response.status == HttpStatusCode.OK) {
                val body = json.decodeFromString<CompleteResponse>(response.bodyAsText())
                body.candidates
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
