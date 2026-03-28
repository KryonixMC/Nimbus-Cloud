package dev.nimbus.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP client for the Nimbus Core REST API.
 */
public class NimbusApiClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public NimbusApiClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CompletableFuture<ApiResult> get(String path) {
        HttpRequest request = buildRequest(path).GET().build();
        return execute(request);
    }

    public CompletableFuture<ApiResult> post(String path) {
        return post(path, null);
    }

    public CompletableFuture<ApiResult> post(String path, Object body) {
        HttpRequest.Builder builder = buildRequest(path);
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
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

    private CompletableFuture<ApiResult> execute(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new ApiResult(response.statusCode(), response.body()))
                .exceptionally(e -> new ApiResult(-1, e.getMessage()));
    }

    public record ApiResult(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public JsonObject asJson() {
            return new Gson().fromJson(body, JsonObject.class);
        }

        public JsonArray asJsonArray() {
            return new Gson().fromJson(body, JsonArray.class);
        }
    }
}
