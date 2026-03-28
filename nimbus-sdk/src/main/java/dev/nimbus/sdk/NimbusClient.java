package dev.nimbus.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Nimbus REST API.
 * <p>
 * Provides typed async methods for all API endpoints. Used by both
 * server plugins (Paper/Velocity) and external tools.
 *
 * <pre>{@code
 * NimbusClient client = new NimbusClient("http://127.0.0.1:8080", "your-token");
 * client.getServices().thenAccept(services -> {
 *     services.forEach(s -> System.out.println(s.getName() + " -> " + s.getState()));
 * });
 * }</pre>
 */
public class NimbusClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public NimbusClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String getBaseUrl() { return baseUrl; }
    public String getToken() { return token; }

    // ── Services ──────────────────────────────────────────────────────

    /** Get all services. */
    public CompletableFuture<List<NimbusService>> getServices() {
        return get("/api/services").thenApply(json -> parseServiceList(json.getAsJsonObject()));
    }

    /** Get services filtered by group name. */
    public CompletableFuture<List<NimbusService>> getServicesByGroup(String groupName) {
        return get("/api/services?group=" + encode(groupName))
                .thenApply(json -> parseServiceList(json.getAsJsonObject()));
    }

    /** Get services filtered by custom state. */
    public CompletableFuture<List<NimbusService>> getServicesByCustomState(String customState) {
        return get("/api/services?customState=" + encode(customState))
                .thenApply(json -> parseServiceList(json.getAsJsonObject()));
    }

    /** Get a specific service by name. */
    public CompletableFuture<NimbusService> getService(String name) {
        return get("/api/services/" + encode(name)).thenApply(json -> parseService(json.getAsJsonObject()));
    }

    /** Start a new instance of a group. */
    public CompletableFuture<Void> startService(String groupName) {
        return post("/api/services/" + encode(groupName) + "/start", null).thenApply(r -> null);
    }

    /** Stop a service. */
    public CompletableFuture<Void> stopService(String serviceName) {
        return post("/api/services/" + encode(serviceName) + "/stop", null).thenApply(r -> null);
    }

    /** Restart a service. */
    public CompletableFuture<Void> restartService(String serviceName) {
        return post("/api/services/" + encode(serviceName) + "/restart", null).thenApply(r -> null);
    }

    /** Execute a command on a service. */
    public CompletableFuture<Void> executeCommand(String serviceName, String command) {
        JsonObject body = new JsonObject();
        body.addProperty("command", command);
        return post("/api/services/" + encode(serviceName) + "/exec", body).thenApply(r -> null);
    }

    // ── Custom State ──────────────────────────────────────────────────

    /** Set a custom state on a service (e.g. "WAITING", "INGAME", "ENDING"). */
    public CompletableFuture<Void> setCustomState(String serviceName, String customState) {
        JsonObject body = new JsonObject();
        body.addProperty("customState", customState);
        return put("/api/services/" + encode(serviceName) + "/state", body).thenApply(r -> null);
    }

    /** Clear custom state on a service (set back to null = routable). */
    public CompletableFuture<Void> clearCustomState(String serviceName) {
        JsonObject body = new JsonObject();
        body.add("customState", null);
        return put("/api/services/" + encode(serviceName) + "/state", body).thenApply(r -> null);
    }

    /** Get the current custom state of a service. */
    public CompletableFuture<String> getCustomState(String serviceName) {
        return get("/api/services/" + encode(serviceName) + "/state")
                .thenApply(json -> {
                    JsonElement el = json.getAsJsonObject().get("customState");
                    return el != null && !el.isJsonNull() ? el.getAsString() : null;
                });
    }

    // ── Groups ────────────────────────────────────────────────────────

    /** Get all groups. */
    public CompletableFuture<List<NimbusGroup>> getGroups() {
        return get("/api/groups").thenApply(json -> {
            JsonArray arr = json.getAsJsonObject().getAsJsonArray("groups");
            List<NimbusGroup> groups = new ArrayList<>();
            for (JsonElement el : arr) {
                groups.add(parseGroup(el.getAsJsonObject()));
            }
            return Collections.unmodifiableList(groups);
        });
    }

    /** Get a specific group by name. */
    public CompletableFuture<NimbusGroup> getGroup(String name) {
        return get("/api/groups/" + encode(name)).thenApply(json -> parseGroup(json.getAsJsonObject()));
    }

    // ── Players ───────────────────────────────────────────────────────

    /** Send a player to another service. */
    public CompletableFuture<Void> sendPlayer(String playerName, String targetService) {
        JsonObject body = new JsonObject();
        body.addProperty("targetService", targetService);
        return post("/api/players/" + encode(playerName) + "/send", body).thenApply(r -> null);
    }

    // ── Events ────────────────────────────────────────────────────────

    /**
     * Create a new event stream connection.
     * Call {@link NimbusEventStream#connect()} to start receiving events.
     */
    public NimbusEventStream createEventStream() {
        String wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://");
        String url = wsUrl + "/api/events";
        if (token != null && !token.isEmpty()) {
            url += "?token=" + encode(token);
        }
        return new NimbusEventStream(URI.create(url));
    }

    // ── HTTP internals ────────────────────────────────────────────────

    private CompletableFuture<JsonElement> get(String path) {
        HttpRequest request = buildRequest(path).GET().build();
        return execute(request);
    }

    private CompletableFuture<JsonElement> post(String path, JsonObject body) {
        HttpRequest.Builder builder = buildRequest(path);
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return execute(builder.build());
    }

    private CompletableFuture<JsonElement> put(String path, JsonObject body) {
        HttpRequest.Builder builder = buildRequest(path);
        builder.PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        return execute(builder.build());
    }

    private HttpRequest.Builder buildRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private CompletableFuture<JsonElement> execute(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new NimbusApiException(response.statusCode(), response.body());
                    }
                    return gson.fromJson(response.body(), JsonElement.class);
                });
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── JSON parsing ──────────────────────────────────────────────────

    private List<NimbusService> parseServiceList(JsonObject root) {
        JsonArray arr = root.getAsJsonArray("services");
        List<NimbusService> services = new ArrayList<>();
        for (JsonElement el : arr) {
            services.add(parseService(el.getAsJsonObject()));
        }
        return Collections.unmodifiableList(services);
    }

    private NimbusService parseService(JsonObject obj) {
        return new NimbusService(
                getString(obj, "name"),
                getString(obj, "groupName"),
                obj.get("port").getAsInt(),
                getString(obj, "state"),
                getString(obj, "customState"),
                obj.has("pid") && !obj.get("pid").isJsonNull() ? obj.get("pid").getAsLong() : null,
                obj.has("playerCount") ? obj.get("playerCount").getAsInt() : 0,
                getString(obj, "startedAt"),
                obj.has("restartCount") ? obj.get("restartCount").getAsInt() : 0,
                getString(obj, "uptime")
        );
    }

    private NimbusGroup parseGroup(JsonObject obj) {
        JsonObject resources = obj.has("resources") ? obj.getAsJsonObject("resources") : null;
        JsonObject scaling = obj.has("scaling") ? obj.getAsJsonObject("scaling") : null;
        return new NimbusGroup(
                getString(obj, "name"),
                getString(obj, "type"),
                getString(obj, "software"),
                getString(obj, "version"),
                getString(obj, "template"),
                obj.has("activeInstances") ? obj.get("activeInstances").getAsInt() : 0,
                scaling != null && scaling.has("maxInstances") ? scaling.get("maxInstances").getAsInt() : 0,
                resources != null && resources.has("maxPlayers") ? resources.get("maxPlayers").getAsInt() : 0
        );
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }
}
