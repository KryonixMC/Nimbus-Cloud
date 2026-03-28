import type {
  HealthResponse,
  StatusResponse,
  ConfigResponse,
} from "./types";

const API_TOKEN = process.env.NEXT_PUBLIC_NIMBUS_API_TOKEN || "";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options?.headers as Record<string, string>),
  };

  if (API_TOKEN) {
    headers["Authorization"] = `Bearer ${API_TOKEN}`;
  }

  const res = await fetch(path, { ...options, headers });

  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(res.status, body?.message || `HTTP ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

// Health (public)
export const getHealth = () => apiFetch<HealthResponse>("/api/health");

// Status
export const getStatus = () => apiFetch<StatusResponse>("/api/status");

// Services
export function getServices(group?: string, state?: string) {
  const params = new URLSearchParams();
  if (group) params.set("group", group);
  if (state) params.set("state", state);
  const qs = params.toString();
  return apiFetch<{ services: any[]; total: number }>(`/api/services${qs ? `?${qs}` : ""}`);
}

export const getService = (name: string) =>
  apiFetch<any>(`/api/services/${encodeURIComponent(name)}`);

export const startService = (name: string) =>
  apiFetch<any>(`/api/services/${encodeURIComponent(name)}/start`, { method: "POST" });

export const stopService = (name: string) =>
  apiFetch<any>(`/api/services/${encodeURIComponent(name)}/stop`, { method: "POST" });

export const restartService = (name: string) =>
  apiFetch<any>(`/api/services/${encodeURIComponent(name)}/restart`, { method: "POST" });

export const execCommand = (name: string, command: string) =>
  apiFetch<any>(`/api/services/${encodeURIComponent(name)}/exec`, {
    method: "POST",
    body: JSON.stringify({ command }),
  });

export const getServiceLogs = (name: string, lines = 100) =>
  apiFetch<{ service: string; lines: string[]; total: number }>(
    `/api/services/${encodeURIComponent(name)}/logs?lines=${lines}`,
  );

// Groups
export const getGroups = () =>
  apiFetch<{ groups: any[]; total: number }>("/api/groups");

export const getGroup = (name: string) =>
  apiFetch<any>(`/api/groups/${encodeURIComponent(name)}`);

export const createGroup = (data: Record<string, any>) =>
  apiFetch<any>("/api/groups", { method: "POST", body: JSON.stringify(data) });

export const updateGroup = (name: string, data: Record<string, any>) =>
  apiFetch<any>(`/api/groups/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });

export const deleteGroup = (name: string) =>
  apiFetch<any>(`/api/groups/${encodeURIComponent(name)}`, { method: "DELETE" });

// Players
export const getPlayers = () =>
  apiFetch<{ players: any[]; total: number }>("/api/players");

export const sendPlayer = (name: string, targetService: string) =>
  apiFetch<any>(`/api/players/${encodeURIComponent(name)}/send`, {
    method: "POST",
    body: JSON.stringify({ targetService }),
  });

// System
export const reload = () =>
  apiFetch<{ success: boolean; groupsLoaded: number; message: string }>("/api/reload", {
    method: "POST",
  });

export const shutdown = () =>
  apiFetch<any>("/api/shutdown", { method: "POST" });

// Config
export const getConfig = () => apiFetch<ConfigResponse>("/api/config");

export const updateConfig = (data: {
  networkName?: string;
  consoleColored?: boolean;
  consoleLogEvents?: boolean;
}) => apiFetch<any>("/api/config", { method: "PATCH", body: JSON.stringify(data) });

// Files
export const listFiles = (scopePath: string) =>
  apiFetch<{ scope: string; path: string; entries: any[]; total: number }>(
    `/api/files/${scopePath}`,
  );

export const readFile = (scopePath: string) =>
  apiFetch<{ scope: string; path: string; content?: string; size: number }>(
    `/api/files/${scopePath}`,
  );

export const writeFile = (scopePath: string, content: string) =>
  apiFetch<any>(`/api/files/${scopePath}`, {
    method: "PUT",
    body: JSON.stringify({ content }),
  });

export const deleteFile = (scopePath: string) =>
  apiFetch<any>(`/api/files/${scopePath}`, { method: "DELETE" });
