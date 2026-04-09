const API_URL_KEY = "nimbus_api_url";
const TOKEN_KEY = "nimbus_api_token";

export function getApiUrl(): string {
  if (typeof window === "undefined") return "";
  return localStorage.getItem(API_URL_KEY) || "";
}

export function getToken(): string {
  if (typeof window === "undefined") return "";
  return localStorage.getItem(TOKEN_KEY) || "";
}

export function setCredentials(apiUrl: string, token: string) {
  localStorage.setItem(API_URL_KEY, apiUrl);
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearCredentials() {
  localStorage.removeItem(API_URL_KEY);
  localStorage.removeItem(TOKEN_KEY);
}

export function isAuthenticated(): boolean {
  return !!getApiUrl() && !!getToken();
}

/**
 * Whether requests need to go through the server-side proxy.
 * This is true when the dashboard is served over HTTPS but the controller
 * is plain HTTP — browsers block such mixed-content requests.
 */
function needsProxy(): boolean {
  if (typeof window === "undefined") return false;
  const dashboardHttps = window.location.protocol === "https:";
  const controllerHttp = getApiUrl().startsWith("http://");
  return dashboardHttps && controllerHttp;
}

/**
 * Build the fetch URL and headers, routing through the server-side proxy
 * when mixed-content would block the request.
 */
function buildRequest(
  path: string,
  options: RequestInit = {}
): { url: string; init: RequestInit } {
  const apiUrl = getApiUrl();
  const token = getToken();

  if (needsProxy()) {
    // Route through /api/proxy/... on the same origin
    const url = `/api/proxy${path}`;
    return {
      url,
      init: {
        ...options,
        headers: {
          "Content-Type": "application/json",
          "X-Nimbus-Controller": apiUrl,
          "X-Nimbus-Token": token,
          ...options.headers,
        },
      },
    };
  }

  // Direct connection (same network / development)
  return {
    url: `${apiUrl}${path}`,
    init: {
      ...options,
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
        ...options.headers,
      },
    },
  };
}

export async function apiFetch<T = unknown>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const { url, init } = buildRequest(path, options);

  const res = await fetch(url, init);

  if (res.status === 401) {
    clearCredentials();
    window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `API error: ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

/**
 * Upload a file as raw request body (streamed, no multipart buffering).
 * Parameters should be passed as query params in the path.
 */
export async function apiUpload<T = unknown>(
  path: string,
  file: File | Blob
): Promise<T> {
  const apiUrl = getApiUrl();
  const token = getToken();

  const headers: Record<string, string> = {
    "Content-Type": "application/octet-stream",
  };

  let url: string;
  if (needsProxy()) {
    url = `/api/proxy${path}`;
    headers["X-Nimbus-Controller"] = apiUrl;
    headers["X-Nimbus-Token"] = token;
  } else {
    url = `${apiUrl}${path}`;
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(url, {
    method: "POST",
    headers,
    body: file,
  });

  if (res.status === 401) {
    clearCredentials();
    window.location.href = "/login";
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || body.error || `Upload failed: ${res.status}`);
  }

  return res.json();
}

export function apiWebSocket(path: string): WebSocket {
  const apiUrl = getApiUrl();
  const token = getToken();
  const wsUrl = apiUrl.replace(/^http/, "ws");
  return new WebSocket(`${wsUrl}${path}?token=${encodeURIComponent(token)}`);
}

/**
 * EventSource-based WebSocket bridge for proxy mode.
 * Uses SSE (GET /api/proxy-ws/...) to receive and POST to send.
 * Returns an object with the same shape as apiWebSocketReconnect.
 */
export function apiProxyWebSocket(
  path: string,
  handlers: {
    onOpen?: () => void;
    onMessage?: (event: MessageEvent) => void;
    onClose?: () => void;
    onError?: (event: Event) => void;
  },
  options?: { maxRetries?: number; baseDelay?: number }
): { send: (message: string) => Promise<void>; cleanup: () => void } {
  const apiUrl = getApiUrl();
  const token = getToken();
  const maxRetries = options?.maxRetries ?? 10;
  const baseDelay = options?.baseDelay ?? 1000;
  let eventSource: EventSource | null = null;
  let retries = 0;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function connect() {
    if (stopped) return;

    // EventSource doesn't support custom headers, so we pass credentials as query params
    const params = new URLSearchParams({
      controller: apiUrl,
      token: token,
    });
    eventSource = new EventSource(`/api/proxy-ws${path}?${params.toString()}`);

    eventSource.addEventListener("open", () => {
      retries = 0;
      handlers.onOpen?.();
    });

    eventSource.onmessage = (event) => {
      handlers.onMessage?.(event);
    };

    eventSource.addEventListener("close", () => {
      handlers.onClose?.();
      eventSource?.close();
      reconnect();
    });

    eventSource.addEventListener("error", (event) => {
      handlers.onError?.(event);
      eventSource?.close();
      reconnect();
    });
  }

  function reconnect() {
    if (stopped || retries >= maxRetries) return;
    const delay = Math.min(baseDelay * Math.pow(2, retries), 30000);
    retries++;
    timer = setTimeout(connect, delay);
  }

  connect();

  return {
    send: async (message: string) => {
      await fetch(`/api/proxy-ws${path}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Nimbus-Controller": apiUrl,
          "X-Nimbus-Token": token,
        },
        body: JSON.stringify({ message }),
      });
    },
    cleanup: () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      eventSource?.close();
    },
  };
}

/**
 * WebSocket with automatic reconnection on disconnect.
 * In proxy mode, transparently uses SSE bridge instead.
 * Returns a cleanup function to stop reconnecting.
 */
export function apiWebSocketReconnect(
  path: string,
  handlers: {
    onOpen?: (ws: WebSocket) => void;
    onMessage?: (event: MessageEvent) => void;
    onClose?: () => void;
    onError?: (event: Event) => void;
  },
  options?: { maxRetries?: number; baseDelay?: number }
): { getSocket: () => WebSocket | null; send: (message: string) => void; cleanup: () => void } {
  if (needsProxy()) {
    const bridge = apiProxyWebSocket(
      path,
      {
        onOpen: () => handlers.onOpen?.(null as unknown as WebSocket),
        onMessage: handlers.onMessage,
        onClose: handlers.onClose,
        onError: handlers.onError,
      },
      options
    );
    return {
      getSocket: () => null,
      send: (msg: string) => { bridge.send(msg); },
      cleanup: bridge.cleanup,
    };
  }

  const maxRetries = options?.maxRetries ?? 10;
  const baseDelay = options?.baseDelay ?? 1000;
  let ws: WebSocket | null = null;
  let retries = 0;
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  function connect() {
    if (stopped) return;
    ws = apiWebSocket(path);

    ws.onopen = () => {
      retries = 0;
      handlers.onOpen?.(ws!);
    };

    ws.onmessage = (event) => handlers.onMessage?.(event);

    ws.onerror = (event) => handlers.onError?.(event);

    ws.onclose = () => {
      handlers.onClose?.();
      if (stopped || retries >= maxRetries) return;
      const delay = Math.min(baseDelay * Math.pow(2, retries), 30000);
      retries++;
      timer = setTimeout(connect, delay);
    };
  }

  connect();

  return {
    getSocket: () => ws,
    send: (msg: string) => { ws?.send(msg); },
    cleanup: () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      ws?.close();
    },
  };
}
