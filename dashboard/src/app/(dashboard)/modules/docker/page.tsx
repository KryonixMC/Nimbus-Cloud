"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { PageShell } from "@/components/page-shell";
import { StatCard } from "@/components/stat-card";
import { EmptyState } from "@/components/empty-state";
import { POLL, useApiResource } from "@/hooks/use-api-resource";
import { apiFetch } from "@/lib/api";
import { ContainerIcon, Cpu, HardDrive, RefreshCw, Server, Trash2 } from "@/lib/icons";

// ── Types (mirror modules/docker/routes/DockerRoutes.kt DTOs) ───

interface DockerStatus {
  enabled: boolean;
  reachable: boolean;
  socket: string;
  version: string | null;
  apiVersion: string | null;
  os: string | null;
  arch: string | null;
  totalContainers: number;
  runningContainers: number;
}

interface PortMapping {
  publicPort: number;
  privatePort: number;
  type: string;
}

interface ContainerSummary {
  id: string;
  service: string;
  group: string;
  image: string;
  state: string;
  status: string;
  ports: PortMapping[];
}

interface PruneResponse {
  removed: number;
  errors: string[];
}

function formatPorts(ports: PortMapping[]): string {
  if (ports.length === 0) return "—";
  return ports
    .map((p) => (p.publicPort > 0 ? `${p.publicPort}→${p.privatePort}/${p.type}` : `${p.privatePort}/${p.type}`))
    .join(", ");
}

function stateTone(state: string): "ok" | "warn" | "err" | "info" {
  switch (state) {
    case "running":
      return "ok";
    case "created":
    case "restarting":
      return "info";
    case "paused":
      return "warn";
    default:
      return "err";
  }
}

function SeverityBadge({ tone, children }: { tone: "ok" | "warn" | "err" | "info"; children: React.ReactNode }) {
  const color = `var(--severity-${tone})`;
  return (
    <Badge
      variant="outline"
      style={{ borderColor: color, color }}
      className="font-medium"
    >
      {children}
    </Badge>
  );
}

export default function DockerModulePage() {
  const statusRes = useApiResource<DockerStatus>("/api/docker/status", { poll: POLL.normal });
  const containersRes = useApiResource<ContainerSummary[]>("/api/docker/containers", {
    poll: POLL.normal,
    silent: true, // don't spam toasts on each poll when the daemon is briefly offline
  });
  const [pruning, setPruning] = useState(false);

  const status = statusRes.data;

  const handlePrune = async () => {
    setPruning(true);
    try {
      const result = await apiFetch<PruneResponse>("/api/docker/prune", { method: "POST" });
      await containersRes.refetch();
      await statusRes.refetch();
      alert(`Removed ${result.removed} container(s)${result.errors.length ? `\nErrors:\n${result.errors.join("\n")}` : ""}`);
    } finally {
      setPruning(false);
    }
  };

  const pageStatus: "loading" | "error" | "empty" | "ready" = statusRes.loading
    ? "loading"
    : statusRes.error
      ? "error"
      : "ready";

  return (
    <PageShell
      title="Docker"
      description="Opt-in Docker backend for services. Groups with [docker] enabled = true run as containers."
      status={pageStatus}
      skeleton="grid"
      error={statusRes.error}
      onRetry={statusRes.refetch}
      actions={
        <>
          <Button variant="outline" size="sm" onClick={() => { statusRes.refetch(); containersRes.refetch(); }}>
            <RefreshCw className="size-4" /> Refresh
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handlePrune}
            disabled={pruning || !status?.reachable}
          >
            <Trash2 className="size-4" /> Prune stopped
          </Button>
        </>
      }
    >
      {/* ── Status panel ────────────────────────── */}
      {status && (
        <>
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="Module"
              value={status.enabled ? "Enabled" : "Disabled"}
              hint={status.enabled ? "Services can opt in via [docker] enabled" : "Set enabled = true in docker.toml"}
              icon={ContainerIcon}
              tone={status.enabled ? "primary" : "default"}
            />
            <StatCard
              label="Daemon"
              value={status.reachable ? "Reachable" : "Unreachable"}
              hint={status.socket}
              icon={HardDrive}
              tone={status.reachable ? "primary" : "destructive"}
            />
            <StatCard
              label="Version"
              value={status.version ?? "—"}
              hint={status.apiVersion ? `API ${status.apiVersion}` : status.reachable ? "—" : "Daemon offline"}
              icon={Cpu}
            />
            <StatCard
              label="Containers"
              value={`${status.runningContainers} / ${status.totalContainers}`}
              hint="running / total (Nimbus-managed)"
              icon={Server}
            />
          </div>

          {!status.reachable && status.enabled && (
            <Card>
              <CardContent className="p-4 text-sm">
                <SeverityBadge tone="err">Daemon offline</SeverityBadge>
                <span className="ml-3 text-muted-foreground">
                  Nimbus can&apos;t reach the Docker socket at{" "}
                  <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{status.socket}</code>.
                  Services that opted into Docker fall back to a plain process until the daemon returns.
                </span>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {/* ── Containers table ────────────────────── */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0 pb-3">
          <CardTitle className="text-base font-semibold">Nimbus-managed containers</CardTitle>
          {status?.os && (
            <span className="text-xs text-muted-foreground">
              {status.os}/{status.arch}
            </span>
          )}
        </CardHeader>
        <CardContent className="pt-0">
          {containersRes.loading && !containersRes.data ? (
            <div className="py-8 text-center text-sm text-muted-foreground">Loading…</div>
          ) : !containersRes.data || containersRes.data.length === 0 ? (
            <EmptyState
              icon={ContainerIcon}
              title="No containers"
              description={
                status?.reachable
                  ? "No groups have opted into Docker yet. Add [docker] enabled = true to a group config."
                  : "Start the Docker daemon to see containers here."
              }
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Service</TableHead>
                  <TableHead>Group</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Image</TableHead>
                  <TableHead>Ports</TableHead>
                  <TableHead className="font-mono text-xs">ID</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {containersRes.data.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell className="font-medium">{c.service || "—"}</TableCell>
                    <TableCell className="text-muted-foreground">{c.group || "—"}</TableCell>
                    <TableCell>
                      <SeverityBadge tone={stateTone(c.state)}>{c.state}</SeverityBadge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{c.image}</TableCell>
                    <TableCell className="text-muted-foreground">{formatPorts(c.ports)}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {c.id.slice(0, 12)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </PageShell>
  );
}
