package dev.nimbuspowered.nimbus.plugin;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HTTP/1.1 server for testing NimbusApiClient.
 * Call {@link #enqueue200} / {@link #enqueue404} before each request,
 * then read {@link #recordedRequests} to assert what was sent.
 */
class FakeNimbusApi implements AutoCloseable {

    /** Recorded HTTP request from the client under test. */
    record RecordedRequest(String method, String path, String rawHeaders, String body) {
        /** Returns the value of the given header name (case-insensitive), or null if absent. */
        String header(String name) {
            Pattern p = Pattern.compile("(?im)^" + Pattern.quote(name) + ":\\s*(.+)$");
            Matcher m = p.matcher(rawHeaders);
            return m.find() ? m.group(1).strip() : null;
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-nimbus-api");
        t.setDaemon(true);
        return t;
    });

    /** Queued raw HTTP responses (popped in FIFO order). */
    private final ConcurrentLinkedQueue<String> responseQueue = new ConcurrentLinkedQueue<>();

    /** All requests received since this instance was created. */
    final List<RecordedRequest> recordedRequests =
            Collections.synchronizedList(new ArrayList<>());

    /** Base URL to pass to NimbusApiClient, e.g. {@code "http://127.0.0.1:54321"}. */
    final String endpoint;

    FakeNimbusApi() throws Exception {
        serverSocket = new ServerSocket(0);
        endpoint = "http://127.0.0.1:" + serverSocket.getLocalPort();
        executor.submit(this::acceptLoop);
    }

    // ── Response helpers ─────────────────────────────────────────────

    void enqueue200(String jsonBody) {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        responseQueue.add(
                "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                jsonBody);
    }

    void enqueue404() {
        responseQueue.add(
                "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");
    }

    void enqueue500() {
        String body = "{\"error\":\"INTERNAL_ERROR\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        responseQueue.add(
                "HTTP/1.1 500 Internal Server Error\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body);
    }

    // ── Accept loop ──────────────────────────────────────────────────

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handle(client));
            } catch (Exception e) {
                // Closed — exit loop
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();

            // Read headers (terminated by \r\n\r\n)
            StringBuilder headerBuf = new StringBuilder();
            int b;
            int last4 = 0;
            while ((b = in.read()) >= 0) {
                headerBuf.append((char) b);
                last4 = ((last4 << 8) | (b & 0xFF)) & 0x7FFFFFFF;
                if (last4 == 0x0D0A0D0A) break; // \r\n\r\n
            }

            String rawHeaders = headerBuf.toString();
            String requestLine = rawHeaders.lines().findFirst().orElse("");
            String[] parts = requestLine.split(" ", 3);
            String method = parts.length > 0 ? parts[0] : "";
            String path   = parts.length > 1 ? parts[1] : "";

            // Read body if Content-Length is present
            String bodyStr = "";
            Matcher clMatcher = Pattern.compile("(?im)^Content-Length:\\s*(\\d+)$").matcher(rawHeaders);
            if (clMatcher.find()) {
                int len = Integer.parseInt(clMatcher.group(1));
                byte[] bodyBytes = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(bodyBytes, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
                bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            }

            recordedRequests.add(new RecordedRequest(method, path, rawHeaders, bodyStr));

            // Send the next queued response (or a default 500 if nothing queued)
            String response = responseQueue.poll();
            if (response == null) {
                response = "HTTP/1.1 500 No Response Queued\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            }

            client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (Exception ignored) {
            // Ignore socket errors during test teardown
        }
    }

    // ── AutoCloseable ────────────────────────────────────────────────

    @Override
    public void close() throws Exception {
        serverSocket.close();
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }
}
