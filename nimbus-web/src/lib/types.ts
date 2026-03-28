export interface HealthResponse {
  status: string;
  version: string;
  uptimeSeconds: number;
  services: number;
  apiEnabled: boolean;
}

export interface ServiceInfo {
  name: string;
  groupName: string;
  port: number;
  state: "STARTING" | "READY" | "STOPPING" | "STOPPED" | "CRASHED";
  pid: number;
  playerCount: number;
  startedAt: string;
  restartCount: number;
  uptime: string;
}

export interface GroupInfo {
  name: string;
  type: "DYNAMIC" | "STATIC";
  software: string;
  version: string;
  template: string;
  resources: {
    memory: string;
    maxPlayers: number;
  };
  scaling: {
    minInstances: number;
    maxInstances: number;
    playersPerInstance: number;
    scaleThreshold: number;
    idleTimeout: number;
  };
  lifecycle: {
    stopOnEmpty: boolean;
    restartOnCrash: boolean;
    maxRestarts: number;
  };
  jvmArgs: string[];
  activeInstances: number;
}

export interface StatusResponse {
  networkName: string;
  online: boolean;
  uptimeSeconds: number;
  totalServices: number;
  totalPlayers: number;
  groups: StatusGroup[];
}

export interface StatusGroup {
  name: string;
  instances: number;
  maxInstances: number;
  players: number;
  maxPlayers: number;
  software: string;
  version: string;
}

export interface PlayerInfo {
  name: string;
  service: string;
}

export interface ConfigResponse {
  network: { name: string; bind: string };
  controller: {
    maxMemory: string;
    maxServices: number;
    heartbeatInterval: number;
  };
  console: { colored: boolean; logEvents: boolean };
  paths: { templates: string; services: string; logs: string };
  api: {
    enabled: boolean;
    bind: string;
    port: number;
    hasToken: boolean;
    allowedOrigins: string[];
  };
}

export interface FileEntry {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  lastModified: string;
}

export interface WebSocketEvent {
  type: string;
  timestamp: string;
  data: Record<string, unknown>;
}
