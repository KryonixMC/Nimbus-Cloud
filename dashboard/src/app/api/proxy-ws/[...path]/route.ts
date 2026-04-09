import { type NextRequest } from "next/server";
import WebSocket from "ws";

const HEADER_CONTROLLER_URL = "x-nimbus-controller";
const HEADER_CONTROLLER_TOKEN = "x-nimbus-token";

/**
 * Active WebSocket connections keyed by "controllerUrl + path".
 * The GET handler registers its WS here so the POST handler can
 * send messages through the same connection instead of opening a new one.
 */
const activeConnections = new Map<string, WebSocket>();

function connectionKey(controllerUrl: string, targetPath: string): string {
  return `${controllerUrl}${targetPath}`;
}

/**
 * Extract controller URL and token from either headers (POST) or
 * query params (GET/EventSource, which can't send custom headers).
 */
function getCredentials(request: NextRequest): {
  controllerUrl: string | null;
  controllerToken: string | null;
} {
  return {
    controllerUrl:
      request.headers.get(HEADER_CONTROLLER_URL) ||
      request.nextUrl.searchParams.get("controller"),
    controllerToken:
      request.headers.get(HEADER_CONTROLLER_TOKEN) ||
      request.nextUrl.searchParams.get("token"),
  };
}

/**
 * Build the target WebSocket URL for the controller.
 */
function buildWsUrl(
  controllerUrl: string,
  controllerToken: string | null,
  targetPath: string
): string {
  const wsBase = controllerUrl.replace(/^http/, "ws").replace(/\/+$/, "");
  const tokenParam = controllerToken
    ? `?token=${encodeURIComponent(controllerToken)}`
    : "";
  return `${wsBase}${targetPath}${tokenParam}`;
}

/**
 * Server-Sent Events bridge for WebSocket connections.
 *
 * The browser can't open a WebSocket to an HTTP controller from an HTTPS page,
 * so this route acts as a bridge:
 *   Browser  --SSE (HTTPS)-->  Next.js  --WebSocket (HTTP)--> Controller
 *
 * GET reads credentials from query params (EventSource limitation).
 * The WebSocket is stored in activeConnections so POST can reuse it.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { controllerUrl, controllerToken } = getCredentials(request);

  if (!controllerUrl) {
    return Response.json(
      { error: "Missing controller URL (query param or header)" },
      { status: 400 }
    );
  }

  const { path } = await params;
  const targetPath = "/" + path.join("/");
  const wsUrl = buildWsUrl(controllerUrl, controllerToken, targetPath);
  const key = connectionKey(controllerUrl, targetPath);

  const encoder = new TextEncoder();
  let ws: WebSocket | null = null;

  const stream = new ReadableStream({
    start(controller) {
      ws = new WebSocket(wsUrl);

      ws.on("open", () => {
        activeConnections.set(key, ws!);
        controller.enqueue(encoder.encode("event: open\ndata: connected\n\n"));
      });

      ws.on("message", (data) => {
        const text = data.toString();
        // SSE requires newlines in data to be split across multiple data: lines
        const escaped = text
          .split("\n")
          .map((line) => `data: ${line}`)
          .join("\n");
        controller.enqueue(encoder.encode(`${escaped}\n\n`));
      });

      ws.on("close", () => {
        activeConnections.delete(key);
        controller.enqueue(
          encoder.encode("event: close\ndata: disconnected\n\n")
        );
        controller.close();
      });

      ws.on("error", (err) => {
        activeConnections.delete(key);
        controller.enqueue(
          encoder.encode(
            `event: error\ndata: ${err.message || "WebSocket error"}\n\n`
          )
        );
        controller.close();
      });

      // Close WebSocket when client disconnects
      request.signal.addEventListener("abort", () => {
        activeConnections.delete(key);
        ws?.close();
      });
    },
    cancel() {
      activeConnections.delete(key);
      ws?.close();
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}

/**
 * Send a message to the controller WebSocket via POST.
 * Body: { "message": "..." }
 *
 * Reuses the WebSocket opened by the GET handler so commands and responses
 * stay on the same controller session.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const { controllerUrl, controllerToken } = getCredentials(request);

  if (!controllerUrl) {
    return Response.json(
      { error: "Missing X-Nimbus-Controller header" },
      { status: 400 }
    );
  }

  const { path } = await params;
  const targetPath = "/" + path.join("/");
  const key = connectionKey(controllerUrl, targetPath);

  const body = await request.json();
  const message = body.message;

  if (!message && message !== "") {
    return Response.json({ error: "Missing message field" }, { status: 400 });
  }

  // Reuse the existing WebSocket from the GET/SSE handler
  const ws = activeConnections.get(key);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(typeof message === "string" ? message : JSON.stringify(message));
    return Response.json({ ok: true });
  }

  // Fallback: open a short-lived connection if no SSE bridge is active
  const wsUrl = buildWsUrl(controllerUrl, controllerToken, targetPath);
  return new Promise<Response>((resolve) => {
    const fallbackWs = new WebSocket(wsUrl);
    const timeout = setTimeout(() => {
      fallbackWs.close();
      resolve(
        Response.json({ error: "Connection timeout" }, { status: 504 })
      );
    }, 10000);

    fallbackWs.on("open", () => {
      fallbackWs.send(
        typeof message === "string" ? message : JSON.stringify(message)
      );
      clearTimeout(timeout);
      fallbackWs.close();
      resolve(Response.json({ ok: true }));
    });

    fallbackWs.on("error", (err) => {
      clearTimeout(timeout);
      resolve(
        Response.json(
          { error: err.message || "WebSocket error" },
          { status: 502 }
        )
      );
    });
  });
}
