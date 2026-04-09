import { type NextRequest } from "next/server";

const HEADER_CONTROLLER_URL = "x-nimbus-controller";
const HEADER_CONTROLLER_TOKEN = "x-nimbus-token";
const MAX_BODY_SIZE = 50 * 1024 * 1024; // 50 MB

/**
 * Server-side proxy for Nimbus controller API.
 *
 * The browser sends requests to /api/proxy/<path> with two custom headers:
 *   X-Nimbus-Controller: <controller base URL, e.g. http://152.53.124.143:8080>
 *   X-Nimbus-Token: <API bearer token>
 *
 * This route strips those headers, forwards the request to the controller
 * over plain HTTP (server-to-server, no mixed-content issue), and streams
 * the response back to the browser over HTTPS.
 */
async function proxyRequest(
  request: NextRequest,
  { params }: { params: Promise<{ path: string[] }> }
) {
  const controllerUrl = request.headers.get(HEADER_CONTROLLER_URL);
  const controllerToken = request.headers.get(HEADER_CONTROLLER_TOKEN);

  if (!controllerUrl) {
    return Response.json(
      { error: "Missing X-Nimbus-Controller header" },
      { status: 400 }
    );
  }

  const { path } = await params;
  const targetPath = "/" + path.join("/");
  const search = request.nextUrl.search;
  const targetUrl = `${controllerUrl.replace(/\/+$/, "")}${targetPath}${search}`;

  const headers = new Headers();
  if (controllerToken) {
    headers.set("Authorization", `Bearer ${controllerToken}`);
  }

  const contentType = request.headers.get("content-type");
  if (contentType) {
    headers.set("Content-Type", contentType);
  }

  const hasBody = !["GET", "HEAD", "OPTIONS"].includes(request.method);
  let body: BodyInit | null = null;
  if (hasBody && request.body) {
    const contentLength = request.headers.get("content-length");
    if (contentLength && parseInt(contentLength) > MAX_BODY_SIZE) {
      return Response.json(
        { error: "Request body too large" },
        { status: 413 }
      );
    }
    body = request.body;
  }

  try {
    const upstream = await fetch(targetUrl, {
      method: request.method,
      headers,
      body,
      // @ts-expect-error -- Node fetch supports duplex for streaming request bodies
      duplex: hasBody ? "half" : undefined,
    });

    const responseHeaders = new Headers();
    upstream.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      if (
        lower !== "transfer-encoding" &&
        lower !== "connection" &&
        lower !== "keep-alive"
      ) {
        responseHeaders.set(key, value);
      }
    });

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
    });
  } catch (err) {
    const message =
      err instanceof Error ? err.message : "Failed to reach controller";
    return Response.json({ error: message }, { status: 502 });
  }
}

export const GET = proxyRequest;
export const POST = proxyRequest;
export const PUT = proxyRequest;
export const PATCH = proxyRequest;
export const DELETE = proxyRequest;
export const HEAD = proxyRequest;
export const OPTIONS = proxyRequest;
