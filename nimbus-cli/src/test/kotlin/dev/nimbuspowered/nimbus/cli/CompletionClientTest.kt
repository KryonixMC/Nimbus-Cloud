package dev.nimbuspowered.nimbus.cli

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompletionClientTest {

    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun cleanup() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun mockClient(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val engine = MockEngine { req -> handler(req) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        clients += client
        return client
    }

    @Test
    fun `complete returns candidates from controller`() = runBlocking {
        var seenAuth: String? = null
        var seenBody: String? = null
        val http = mockClient { req ->
            seenAuth = req.headers["Authorization"]
            seenBody = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"candidates":["service","services","start"]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = CompletionClient(http, "http://ctrl:8080", "tok123")
        val out = client.complete("se")

        assertEquals(listOf("service", "services", "start"), out)
        assertEquals("Bearer tok123", seenAuth)
        assertTrue(seenBody!!.contains("\"buffer\":\"se\""))
    }

    @Test
    fun `complete omits Authorization header when token blank`() = runBlocking {
        var seenAuth: String? = null
        val http = mockClient { req ->
            seenAuth = req.headers["Authorization"]
            respond(
                content = """{"candidates":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = CompletionClient(http, "http://ctrl:8080", "")
        val out = client.complete("")
        assertTrue(out.isEmpty())
        assertEquals(null, seenAuth)
    }

    @Test
    fun `complete returns empty list on non-200`() = runBlocking {
        val http = mockClient {
            respond(
                content = "internal",
                status = HttpStatusCode.InternalServerError
            )
        }
        val client = CompletionClient(http, "http://ctrl:8080", "tok")
        assertTrue(client.complete("foo").isEmpty())
    }

    @Test
    fun `complete swallows exceptions and returns empty`() = runBlocking {
        val http = mockClient { throw RuntimeException("boom") }
        val client = CompletionClient(http, "http://ctrl:8080", "tok")
        val out = client.complete("x")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `complete tolerates missing candidates field`() = runBlocking {
        val http = mockClient {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = CompletionClient(http, "http://ctrl:8080", "tok")
        val out = client.complete("anything")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `complete sends request to correct path`() = runBlocking {
        var seenPath: String? = null
        val http = mockClient { req ->
            seenPath = req.url.encodedPath
            respond(
                content = """{"candidates":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = CompletionClient(http, "http://ctrl:8080", "tok")
        client.complete("x")
        assertEquals("/api/console/complete", seenPath)
    }

    @Test
    fun `complete encodes unicode and long buffer`() = runBlocking {
        var seenBody: String? = null
        val http = mockClient { req ->
            seenBody = (req.body as io.ktor.http.content.TextContent).text
            respond(
                content = """{"candidates":["ok"]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = CompletionClient(http, "http://ctrl:8080", "tok")
        val buffer = "ünïcödé " + "x".repeat(500)
        val out = client.complete(buffer)
        assertEquals(listOf("ok"), out)
        assertFalse(seenBody.isNullOrEmpty())
    }
}
