import { type NextRequest } from "next/server";
import WebSocket from "ws";

const HEADER_CONTROLLER_URL = "x-nimbus-controller";
const HEADER_CONTROLLER_TOKEN = "x-nimbus-token";

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

  const encoder = new TextEncoder();
  let ws: WebSocket | null = null;

  const stream = new ReadableStream({
    start(controller) {
      ws = new WebSocket(wsUrl);

      ws.on("open", () => {
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
        controller.enqueue(
          encoder.encode("event: close\ndata: disconnected\n\n")
        );
        controller.close();
      });

      ws.on("error", (err) => {
        controller.enqueue(
          encoder.encode(
            `event: error\ndata: ${err.message || "WebSocket error"}\n\n`
          )
        );
        controller.close();
      });

      // Close WebSocket when client disconnects
      request.signal.addEventListener("abort", () => {
        ws?.close();
      });
    },
    cancel() {
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
 * POST reads credentials from headers (normal fetch can send headers).
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
  const wsUrl = buildWsUrl(controllerUrl, controllerToken, targetPath);

  const body = await request.json();
  const message = body.message;

  if (!message && message !== "") {
    return Response.json({ error: "Missing message field" }, { status: 400 });
  }

  return new Promise<Response>((resolve) => {
    const ws = new WebSocket(wsUrl);
    const timeout = setTimeout(() => {
      ws.close();
      resolve(
        Response.json({ error: "Connection timeout" }, { status: 504 })
      );
    }, 10000);

    ws.on("open", () => {
      ws.send(typeof message === "string" ? message : JSON.stringify(message));
      clearTimeout(timeout);
      ws.close();
      resolve(Response.json({ ok: true }));
    });

    ws.on("error", (err) => {
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
