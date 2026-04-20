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
import org.junit.jupiter.api.Test

class DoctorClientTest {

    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun cleanup() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun mockClient(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val engine = MockEngine { req -> handler(req) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        clients += client
        return client
    }

    private fun okResponseBody(status: String, warn: Int = 0, fail: Int = 0): String = """
        {
          "sections": [
            {"name":"Network","findings":[{"level":"OK","message":"port open"}]},
            {"name":"Disk","findings":[{"level":"WARN","message":"80% full","hint":"prune backups"}]}
          ],
          "warnCount": $warn,
          "failCount": $fail,
          "status": "$status"
        }
    """.trimIndent()

    @Test
    fun `runDoctor returns 0 on status ok`() = runBlocking {
        val http = mockClient {
            respond(
                okResponseBody("ok"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val code = runDoctor(http, "http://ctrl:8080", "tok", asJson = false)
        assertEquals(0, code)
    }

    @Test
    fun `runDoctor returns 1 on status warn`() = runBlocking {
        val http = mockClient {
            respond(
                okResponseBody("warn", warn = 1),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        assertEquals(1, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor returns 2 on status fail`() = runBlocking {
        val http = mockClient {
            respond(
                okResponseBody("fail", warn = 1, fail = 2),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        assertEquals(2, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor returns 3 when controller returns non-200`() = runBlocking {
        val http = mockClient { respond("nope", HttpStatusCode.ServiceUnavailable) }
        assertEquals(3, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor returns 3 when body is not JSON`() = runBlocking {
        val http = mockClient {
            respond(
                "<html>not json</html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        assertEquals(3, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor returns 3 on exception reaching controller`() = runBlocking {
        val http = mockClient { throw RuntimeException("unreachable") }
        assertEquals(3, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor passes Authorization header when token set`() = runBlocking {
        var seenAuth: String? = null
        val http = mockClient { req ->
            seenAuth = req.headers["Authorization"]
            respond(
                okResponseBody("ok"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        runDoctor(http, "http://ctrl:8080", "mysecret", asJson = false)
        assertEquals("Bearer mysecret", seenAuth)
    }

    @Test
    fun `runDoctor omits Authorization when token blank`() = runBlocking {
        var seenAuth: String? = null
        val http = mockClient { req ->
            seenAuth = req.headers["Authorization"]
            respond(
                okResponseBody("ok"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        runDoctor(http, "http://ctrl:8080", "", asJson = false)
        assertEquals(null, seenAuth)
    }

    @Test
    fun `runDoctor asJson still returns correct exit code`() = runBlocking {
        val http = mockClient {
            respond(
                okResponseBody("warn", warn = 1),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        assertEquals(1, runDoctor(http, "http://ctrl:8080", "tok", asJson = true))
    }

    @Test
    fun `runDoctor unknown status defaults to 0`() = runBlocking {
        val http = mockClient {
            respond(
                """{"sections":[],"warnCount":0,"failCount":0,"status":"unknown-status"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        assertEquals(0, runDoctor(http, "http://ctrl:8080", "tok", asJson = false))
    }

    @Test
    fun `runDoctor hits correct endpoint`() = runBlocking {
        var seenPath: String? = null
        val http = mockClient { req ->
            seenPath = req.url.encodedPath
            respond(
                okResponseBody("ok"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        runDoctor(http, "http://ctrl:8080", "t", asJson = false)
        assertEquals("/api/doctor", seenPath)
    }
}
