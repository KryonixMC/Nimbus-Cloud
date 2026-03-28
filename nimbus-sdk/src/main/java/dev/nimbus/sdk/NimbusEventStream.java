package dev.nimbus.sdk;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket-based event stream from Nimbus.
 * <p>
 * Connects to the {@code /api/events} WebSocket endpoint and dispatches
 * events to registered handlers. Supports automatic reconnection.
 *
 * <pre>{@code
 * NimbusEventStream stream = client.createEventStream();
 * stream.onEvent("SERVICE_READY", event -> {
 *     System.out.println(event.getServiceName() + " is ready!");
 * });
 * stream.onEvent("SERVICE_CUSTOM_STATE_CHANGED", event -> {
 *     System.out.println(event.get("service") + " -> " + event.get("newState"));
 * });
 * stream.connect();
 * }</pre>
 */
public class NimbusEventStream implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(NimbusEventStream.class.getName());
    private static final Gson gson = new Gson();

    private final URI uri;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, List<Consumer<NimbusEvent>>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<NimbusEvent>> globalHandlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean autoReconnect = new AtomicBoolean(true);
    private final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();

    NimbusEventStream(URI uri) {
        this.uri = uri;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Register a handler for a specific event type.
     *
     * @param eventType event type (e.g. "SERVICE_READY", "SERVICE_CUSTOM_STATE_CHANGED")
     * @param handler   callback receiving the event
     */
    public void onEvent(String eventType, Consumer<NimbusEvent> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * Register a handler that receives all events.
     */
    public void onAnyEvent(Consumer<NimbusEvent> handler) {
        globalHandlers.add(handler);
    }

    /**
     * Enable or disable automatic reconnection (enabled by default).
     */
    public void setAutoReconnect(boolean enabled) {
        autoReconnect.set(enabled);
    }

    /**
     * Connect to the WebSocket event stream.
     * This method returns immediately; events are dispatched asynchronously.
     */
    public void connect() {
        if (running.compareAndSet(false, true)) {
            doConnect();
        }
    }

    /**
     * Connect and block until the stream is closed.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit
     */
    public void connectAndWait(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        // When closed, count down
        var prevRunning = running.get();
        connect();
        latch.await(timeout, unit);
    }

    @Override
    public void close() {
        running.set(false);
        autoReconnect.set(false);
        WebSocket ws = webSocketRef.getAndSet(null);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "SDK closing").join();
        }
    }

    /** Returns true if the stream is connected and running. */
    public boolean isConnected() {
        return running.get() && webSocketRef.get() != null;
    }

    private void doConnect() {
        httpClient.newWebSocketBuilder()
                .buildAsync(uri, new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        logger.info("Connected to Nimbus event stream");
                        webSocketRef.set(webSocket);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String message = buffer.toString();
                            buffer.setLength(0);
                            dispatch(message);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        logger.info("Event stream closed: " + statusCode + " " + reason);
                        webSocketRef.set(null);
                        if (running.get() && autoReconnect.get()) {
                            scheduleReconnect();
                        }
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        logger.log(Level.WARNING, "Event stream error", error);
                        webSocketRef.set(null);
                        if (running.get() && autoReconnect.get()) {
                            scheduleReconnect();
                        }
                    }
                })
                .exceptionally(e -> {
                    logger.log(Level.WARNING, "Failed to connect to event stream", e);
                    if (running.get() && autoReconnect.get()) {
                        scheduleReconnect();
                    }
                    return null;
                });
    }

    private void scheduleReconnect() {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(3000);
                if (running.get()) {
                    logger.info("Reconnecting to event stream...");
                    doConnect();
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    private void dispatch(String message) {
        try {
            JsonObject obj = gson.fromJson(message, JsonObject.class);
            String type = obj.has("type") ? obj.get("type").getAsString() : "UNKNOWN";
            String timestamp = obj.has("timestamp") ? obj.get("timestamp").getAsString() : "";

            Map<String, String> data;
            if (obj.has("data") && obj.get("data").isJsonObject()) {
                data = gson.fromJson(obj.get("data"), new TypeToken<Map<String, String>>() {}.getType());
            } else {
                data = Map.of();
            }

            NimbusEvent event = new NimbusEvent(type, timestamp, data);

            // Dispatch to type-specific handlers
            List<Consumer<NimbusEvent>> typeHandlers = handlers.get(type);
            if (typeHandlers != null) {
                for (Consumer<NimbusEvent> handler : typeHandlers) {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error in event handler for " + type, e);
                    }
                }
            }

            // Dispatch to global handlers
            for (Consumer<NimbusEvent> handler : globalHandlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error in global event handler", e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse event message: " + message, e);
        }
    }
}
