package dev.nimbuspowered.nimbus.plugin;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NimbusApiClient using FakeNimbusApi as a local HTTP/1.1 server.
 */
class NimbusApiClientTest {

    private static final String TOKEN = "test-bearer-token";

    private FakeNimbusApi fakeApi;
    private NimbusApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        fakeApi  = new FakeNimbusApi();
        client   = new NimbusApiClient(fakeApi.endpoint, TOKEN);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        fakeApi.close();
    }

    // ── GET ──────────────────────────────────────────────────────────

    @Test
    void getRecordsCorrectPath() throws Exception {
        fakeApi.enqueue200("{}");

        client.get("/api/status").get();

        assertEquals(1, fakeApi.recordedRequests.size());
        assertEquals("/api/status", fakeApi.recordedRequests.get(0).path());
    }

    @Test
    void getUsesGetMethod() throws Exception {
        fakeApi.enqueue200("{}");

        client.get("/api/groups").get();

        assertEquals("GET", fakeApi.recordedRequests.get(0).method());
    }

    @Test
    void getSendsBearerAuthorization() throws Exception {
        fakeApi.enqueue200("{}");

        client.get("/api/status").get();

        String auth = fakeApi.recordedRequests.get(0).header("Authorization");
        assertEquals("Bearer " + TOKEN, auth);
    }

    @Test
    void getWith200ReturnsSuccessResult() throws Exception {
        fakeApi.enqueue200("{\"ok\":true}");

        NimbusApiClient.ApiResult result = client.get("/api/status").get();

        assertTrue(result.isSuccess());
        assertEquals(200, result.statusCode());
    }

    @Test
    void getWith200BodyIsParseable() throws Exception {
        fakeApi.enqueue200("{\"version\":\"0.13.0\"}");

        NimbusApiClient.ApiResult result = client.get("/api/status").get();

        JsonObject json = result.asJson();
        assertEquals("0.13.0", json.get("version").getAsString());
    }

    // ── 404 / error handling ─────────────────────────────────────────

    @Test
    void getWith404ReturnsNonSuccessResult() throws Exception {
        fakeApi.enqueue404();

        NimbusApiClient.ApiResult result = client.get("/api/services/Unknown").get();

        assertFalse(result.isSuccess());
        assertEquals(404, result.statusCode());
    }

    @Test
    void getWith404DoesNotThrow() {
        fakeApi.enqueue404();

        // join() rather than get() — unchecked, same semantics for our purposes
        assertDoesNotThrow(() -> client.get("/api/services/Unknown").join());
    }

    @Test
    void getWith500IsNotSuccess() throws Exception {
        fakeApi.enqueue500();

        NimbusApiClient.ApiResult result = client.get("/api/anything").get();

        assertFalse(result.isSuccess());
        assertEquals(500, result.statusCode());
    }

    // ── POST ─────────────────────────────────────────────────────────

    @Test
    void postRecordsCorrectPath() throws Exception {
        fakeApi.enqueue200("{}");

        JsonObject body = new JsonObject();
        body.addProperty("name", "TestPlayer");
        client.post("/api/proxy/events", body).get();

        assertEquals("/api/proxy/events", fakeApi.recordedRequests.get(0).path());
    }

    @Test
    void postUsesPostMethod() throws Exception {
        fakeApi.enqueue200("{}");

        client.post("/api/proxy/events").get();

        assertEquals("POST", fakeApi.recordedRequests.get(0).method());
    }

    @Test
    void postSendsBearerAuthorization() throws Exception {
        fakeApi.enqueue200("{}");

        client.post("/api/proxy/events").get();

        String auth = fakeApi.recordedRequests.get(0).header("Authorization");
        assertEquals("Bearer " + TOKEN, auth);
    }

    // ── PUT ──────────────────────────────────────────────────────────

    @Test
    void putRecordsCorrectPath() throws Exception {
        fakeApi.enqueue200("{\"effectivePermissions\":[]}");

        JsonObject body = new JsonObject();
        body.addProperty("name", "TestPlayer");
        client.put("/api/permissions/players/some-uuid", body).get();

        assertEquals("/api/permissions/players/some-uuid",
                fakeApi.recordedRequests.get(0).path());
    }

    @Test
    void putUsesPutMethod() throws Exception {
        fakeApi.enqueue200("{\"effectivePermissions\":[]}");

        client.put("/api/permissions/players/some-uuid", new JsonObject()).get();

        assertEquals("PUT", fakeApi.recordedRequests.get(0).method());
    }

    // ── No token ─────────────────────────────────────────────────────

    @Test
    void noTokenOmitsAuthorizationHeader() throws Exception {
        try (NimbusApiClient noTokenClient = new NimbusApiClient(fakeApi.endpoint, "")) {
            fakeApi.enqueue200("{}");

            noTokenClient.get("/api/status").get();

            String auth = fakeApi.recordedRequests.get(0).header("Authorization");
            assertNull(auth, "Authorization header must be absent when token is empty");
        }
    }

    // ── Trailing slash normalisation ──────────────────────────────────

    @Test
    void trailingSlashInBaseUrlIsNormalised() throws Exception {
        // Construct with a trailing slash — the client should strip it
        try (NimbusApiClient slashClient = new NimbusApiClient(fakeApi.endpoint + "/", TOKEN)) {
            fakeApi.enqueue200("{}");

            slashClient.get("/api/status").get();

            // Path must not end up as "//api/status"
            assertEquals("/api/status", fakeApi.recordedRequests.get(0).path());
        }
    }
}
