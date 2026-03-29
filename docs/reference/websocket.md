# WebSocket API

Nimbus provides two WebSocket endpoints for real-time communication: a global event stream and per-service console access.

## Authentication

WebSocket clients cannot set HTTP headers during the handshake, so authentication uses a `token` query parameter instead of the `Authorization` header:

```
ws://host:port/api/events?token=your-secret-token
ws://host:port/api/services/Lobby-1/console?token=your-secret-token
```

If no token is configured on the server, the `?token=` parameter can be omitted.

::: warning
If authentication fails, the connection is immediately closed with code `1008` (Policy Violation) and the message: `Authentication required -- provide ?token= query parameter`.
:::

---

## Event Stream

### `ws://host:port/api/events`

Subscribes to all Nimbus events in real-time. Messages are JSON-encoded `EventMessage` objects:

```json
{
  "type": "SERVICE_READY",
  "timestamp": "2025-01-15T10:30:15.123Z",
  "data": {
    "service": "Lobby-1",
    "group": "Lobby"
  }
}
```

The connection receives every event emitted by the system. See the [Event Reference](./events) for all event types and their payloads.

### Connection Example (JavaScript)

```javascript
const token = 'your-secret-token';
const ws = new WebSocket(`ws://localhost:8080/api/events?token=${token}`);

ws.onopen = () => {
  console.log('Connected to Nimbus event stream');
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  console.log(`[${msg.type}]`, msg.data);

  switch (msg.type) {
    case 'SERVICE_READY':
      console.log(`Service ${msg.data.service} is ready`);
      break;
    case 'PLAYER_CONNECTED':
      console.log(`${msg.data.player} joined ${msg.data.service}`);
      break;
    case 'SCALE_UP':
      console.log(`Scaling ${msg.data.group}: ${msg.data.from} -> ${msg.data.to}`);
      break;
  }
};

ws.onclose = (event) => {
  console.log(`Disconnected: ${event.code} ${event.reason}`);
};
```

### Connection Example (Python)

```python
import asyncio
import json
import websockets

async def listen():
    uri = "ws://localhost:8080/api/events?token=your-secret-token"
    async with websockets.connect(uri) as ws:
        print("Connected to Nimbus event stream")
        async for message in ws:
            event = json.loads(message)
            print(f"[{event['type']}] {event['data']}")

            if event["type"] == "SERVICE_CRASHED":
                print(f"ALERT: {event['data']['service']} crashed "
                      f"(exit code {event['data']['exitCode']})")

asyncio.run(listen())
```

---

## Console Stream

### `ws://host:port/api/services/{name}/console`

Bidirectional WebSocket for interacting with a service's process. Receive stdout lines and send stdin commands.

**Receiving:** Each frame is a single line of the service's stdout output (plain text).

**Sending:** Each text frame you send is written to the service's stdin as a command.

### Connection Lifecycle

1. Connect with authentication
2. If the service doesn't exist, the connection is closed with code `1003` and message `Service '{name}' not found`
3. If no process handle is available, closed with `1003` and `No process handle for '{name}'`
4. On success, stdout lines begin streaming immediately
5. Send text frames to execute commands on the service
6. Close the connection when done

### Example (JavaScript)

```javascript
const token = 'your-secret-token';
const service = 'Lobby-1';
const ws = new WebSocket(
  `ws://localhost:8080/api/services/${service}/console?token=${token}`
);

ws.onopen = () => {
  console.log(`Attached to ${service} console`);
};

ws.onmessage = (event) => {
  // Each message is a line of server output
  console.log(event.data);
};

// Send a command to the server
function sendCommand(command) {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(command);
  }
}

// Example: whitelist a player
sendCommand('whitelist add Steve');

// Example: broadcast a message
sendCommand('say Server restarting in 5 minutes!');
```

### Example (Python)

```python
import asyncio
import websockets

async def console_session():
    uri = "ws://localhost:8080/api/services/Lobby-1/console?token=your-secret-token"
    async with websockets.connect(uri) as ws:
        # Start reading output in background
        async def read_output():
            async for line in ws:
                print(f"[Lobby-1] {line}")

        reader = asyncio.create_task(read_output())

        # Send commands
        await ws.send("list")
        await asyncio.sleep(2)
        await ws.send("say Hello from Python!")
        await asyncio.sleep(5)

        reader.cancel()

asyncio.run(console_session())
```

---

## Cluster Stream

### `ws://host:port/cluster`

WebSocket endpoint for agent node connections. Used by `nimbus-agent` to communicate with the controller in cluster mode. This endpoint is only available when `cluster.enabled = true`.

### Authentication

The first message from the agent must be an `AUTH_REQUEST`:

```json
{
  "type": "AUTH_REQUEST",
  "nodeName": "node-1",
  "token": "my-cluster-secret",
  "maxMemory": "8G",
  "maxServices": 10,
  "agentVersion": "0.1.0",
  "os": "Linux",
  "arch": "amd64"
}
```

The controller responds with:

```json
{
  "type": "AUTH_RESPONSE",
  "success": true,
  "nodeId": "node-1"
}
```

If authentication fails, the connection is closed with code `1008` (Policy Violation).

### Message Flow

After authentication, the WebSocket carries bidirectional messages between controller and agent:

**Controller to Agent:**
- `HEARTBEAT_REQUEST` -- periodic health check
- `START_SERVICE` -- start a service on the agent
- `STOP_SERVICE` -- stop a service on the agent
- `SEND_COMMAND` -- execute a command on a service's stdin

**Agent to Controller:**
- `HEARTBEAT_RESPONSE` -- CPU, memory, and service status
- `SERVICE_STATE_CHANGED` -- service lifecycle updates (STARTING, READY, STOPPED, CRASHED)
- `SERVICE_STDOUT` -- stdout lines from running services
- `SERVICE_PLAYER_COUNT` -- player count updates
- `COMMAND_RESULT` -- result of a sent command
- `TEMPLATE_REQUEST` -- request to download a template
- `LOG_MESSAGE` -- agent log messages

All messages are JSON-encoded. The protocol is defined in the `nimbus-protocol` module.

::: warning
This endpoint is not intended for external clients. It uses a custom protocol between the controller and agent. Use the `/api/events` WebSocket for monitoring cluster state from external systems.
:::

---

## Configuration

WebSocket settings are configured in Nimbus's Ktor server:

| Setting | Value | Description |
|---------|-------|-------------|
| Ping interval | 15 seconds | Server sends ping frames to detect dead connections |
| Timeout | 30 seconds | Connection closed if no pong received |
| Max frame size | 64 KB | Maximum size of a single WebSocket frame |

---

## Reconnection

Nimbus does not provide built-in reconnection. Clients should implement their own reconnection logic with exponential backoff:

```javascript
function connectWithRetry(url, maxRetries = 10) {
  let retries = 0;

  function connect() {
    const ws = new WebSocket(url);

    ws.onopen = () => {
      console.log('Connected');
      retries = 0; // Reset on successful connection
    };

    ws.onclose = (event) => {
      if (retries < maxRetries) {
        const delay = Math.min(1000 * Math.pow(2, retries), 30000);
        console.log(`Reconnecting in ${delay}ms...`);
        retries++;
        setTimeout(connect, delay);
      } else {
        console.log('Max retries reached');
      }
    };

    ws.onmessage = (event) => {
      // Handle messages
    };
  }

  connect();
}

connectWithRetry('ws://localhost:8080/api/events?token=your-secret');
```

::: tip
The `/api/health` REST endpoint can be used to check if the API is available before attempting a WebSocket connection.
:::

---

## Cluster WebSocket

### `ws://host:port/cluster`

Used by nimbus-agent instances to connect to the controller. This endpoint is only active when `cluster.enabled = true`.

::: info
This endpoint is for the agent-to-controller communication protocol. You do not need to use it directly -- the `nimbus-agent` JAR handles the connection automatically.
:::

### Protocol

1. Agent connects and sends an `AUTH_REQUEST` message with its cluster token, node name, and resource info
2. Controller validates the token and responds with `AUTH_RESPONSE`
3. If accepted, the agent enters a message loop exchanging heartbeats, service state changes, and commands
4. If the connection drops, the agent automatically attempts reconnection

### Message Types

All messages are JSON with a `type` discriminator field. Messages are defined in the `nimbus-protocol` module.

**Controller to Agent:**

| Type | Description |
|------|-------------|
| `AUTH_RESPONSE` | Accept/reject authentication |
| `START_SERVICE` | Start a service on the agent |
| `STOP_SERVICE` | Stop a service on the agent |
| `SEND_COMMAND` | Send a console command to a service |
| `HEARTBEAT_REQUEST` | Request resource usage info |
| `TEMPLATE_INFO` | Template download info |
| `SHUTDOWN_AGENT` | Request graceful agent shutdown |

**Agent to Controller:**

| Type | Description |
|------|-------------|
| `AUTH_REQUEST` | Authenticate with the controller |
| `HEARTBEAT_RESPONSE` | CPU, memory, and service status |
| `SERVICE_STATE_CHANGED` | Service state transition |
| `SERVICE_STDOUT` | Stdout line from a service |
| `SERVICE_PLAYER_COUNT` | Player count update |
| `COMMAND_RESULT` | Result of a command execution |
| `TEMPLATE_REQUEST` | Request a template download |
| `LOG_MESSAGE` | Agent log message |
