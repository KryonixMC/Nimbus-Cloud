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

export async function apiFetch<T = unknown>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const apiUrl = getApiUrl();
  const token = getToken();

  const res = await fetch(`${apiUrl}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...options.headers,
    },
  });

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

export function apiWebSocket(path: string): WebSocket {
  const apiUrl = getApiUrl().replace(/^http/, "ws");
  const token = getToken();
  return new WebSocket(`${apiUrl}${path}?token=${encodeURIComponent(token)}`);
}

/**
 * WebSocket with automatic reconnection on disconnect.
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
): { getSocket: () => WebSocket | null; cleanup: () => void } {
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
    cleanup: () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      ws?.close();
    },
  };
}
