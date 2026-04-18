/**
 * Parse a host input (IP/hostname with optional port) into a full URL.
 * Examples:
 *   "152.53.124.143"       → "http://152.53.124.143:8080"
 *   "152.53.124.143:9090"  → "http://152.53.124.143:9090"
 *   "my.server.com"        → "http://my.server.com:8080"
 *   "my.server.com:443"    → "http://my.server.com:443"
 */
export function buildControllerUrl(host: string): string {
  const trimmed = host.trim().replace(/\/+$/, "");

  // If the user already typed a full URL, use it as-is
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    return trimmed;
  }

  // Check if a port is included (handle IPv6 bracket notation too)
  const hasPort =
    trimmed.includes("]:") ||
    (!trimmed.startsWith("[") && trimmed.includes(":"));
  if (hasPort) {
    return `http://${trimmed}`;
  }

  return `http://${trimmed}:8080`;
}

/**
 * Returns true when the dashboard is served over HTTPS but the controller
 * URL is plain HTTP — in this case fetches must be routed through
 * `/api/proxy/...` on the dashboard origin to avoid mixed-content blocking.
 */
export function shouldUseProxy(controllerUrl: string): boolean {
  if (typeof window === "undefined") return false;
  return (
    window.location.protocol === "https:" &&
    controllerUrl.startsWith("http://")
  );
}

/**
 * Performs an unauthenticated fetch against the controller, transparently
 * routing through the mixed-content proxy when needed. Used during login
 * before credentials are persisted.
 */
export async function controllerFetch(
  controllerUrl: string,
  path: string,
  init: RequestInit = {}
): Promise<Response> {
  if (shouldUseProxy(controllerUrl)) {
    const headers = new Headers(init.headers);
    headers.set("X-Nimbus-Controller", controllerUrl);
    return fetch(`/api/proxy${path}`, { ...init, headers });
  }
  return fetch(`${controllerUrl}${path}`, init);
}
