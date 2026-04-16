"use client";

import { useState } from "react";
import Link from "next/link";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { apiFetch } from "@/lib/api";
import { serviceStateColors } from "@/lib/status";
import { toast } from "sonner";
import {
  MoreHorizontal,
  Play,
  Square,
  RotateCw,
  Plus,
  Server,
  TerminalIcon,
} from "@/lib/icons";
import { PageShell } from "@/components/page-shell";
import { MemoryBar } from "@/components/memory-bar";
import { useApiResource, POLL } from "@/hooks/use-api-resource";
import { ServiceConsoleSheet } from "@/components/service-console-sheet";

interface SyncHealth {
  lastPushAt: string | null;
  lastPushBytes: number;
  lastPushFiles: number;
  canonicalSizeBytes: number;
}

interface Service {
  name: string;
  groupName: string;
  state: string;
  port: number;
  playerCount: number;
  memoryUsedMb: number;
  memoryMaxMb: number;
  healthy: boolean;
  uptime: string | null;
  isDedicated?: boolean;
  proxyEnabled?: boolean;
  nodeId?: string;
  sync?: SyncHealth | null;
  backedBy?: string;
}

interface ServiceListResponse {
  services: Service[];
  total: number;
}

export default function ServicesPage() {
  const {
    data: svcResp,
    loading,
    error,
    refetch: load,
  } = useApiResource<ServiceListResponse>("/api/services", {
    poll: POLL.normal,
  });
  const services = svcResp?.services ?? [];
  const { data: grpResp } = useApiResource<{ groups: { name: string }[] }>(
    "/api/groups",
    { silent: true }
  );
  const groups = (grpResp?.groups ?? []).map((g) => g.name);

  const [startOpen, setStartOpen] = useState(false);
  const [selectedGroup, setSelectedGroup] = useState("");
  const [starting, setStarting] = useState(false);
  const [consoleService, setConsoleService] = useState<string | null>(null);

  async function startService() {
    if (!selectedGroup) return;
    setStarting(true);
    try {
      await apiFetch(`/api/services/${selectedGroup}/start`, { method: "POST" });
      toast.success(`Starting new ${selectedGroup} service`);
      setStartOpen(false);
      setSelectedGroup("");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to start service");
    } finally {
      setStarting(false);
    }
  }

  async function serviceAction(
    name: string,
    action: "start" | "stop" | "restart"
  ) {
    try {
      await apiFetch(`/api/services/${name}/${action}`, { method: "POST" });
      toast.success(`${action} sent to ${name}`);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Action failed");
    }
  }

  function formatBytes(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024)
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  }

  const startDialog = (
    <Dialog open={startOpen} onOpenChange={setStartOpen}>
      <DialogTrigger
        render={
          <Button>
            <Plus className="mr-1 size-4" /> Start service
          </Button>
        }
      />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Start service</DialogTitle>
          <DialogDescription>
            Select a group to start a new service instance.
          </DialogDescription>
        </DialogHeader>
        <Select
          value={selectedGroup}
          onValueChange={(v) => v && setSelectedGroup(v)}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder="Select group..." />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              {groups.map((g) => (
                <SelectItem key={g} value={g}>
                  {g}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
        <DialogFooter>
          <Button onClick={startService} disabled={starting || !selectedGroup}>
            {starting ? "Starting..." : "Start"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );

  return (
    <PageShell
      title="Services"
      description={`${services.length} running service${
        services.length === 1 ? "" : "s"
      } across the cluster.`}
      actions={startDialog}
      status={
        loading
          ? "loading"
          : error
          ? "error"
          : services.length === 0
          ? "empty"
          : "ready"
      }
      error={error}
      onRetry={load}
      skeleton="table"
      emptyState={{
        icon: Server,
        title: "No services running",
        description:
          "Start a service from one of your groups to see it here.",
      }}
    >
      <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-6">Name</TableHead>
                  <TableHead>Group</TableHead>
                  <TableHead>Node</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead>Port</TableHead>
                  <TableHead className="text-right">Players</TableHead>
                  <TableHead className="w-64">Memory</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {services.map((s) => (
                  <TableRow key={s.name}>
                    <TableCell className="pl-6">
                      <div className="flex items-center gap-2">
                        <Link
                          href={`/services/${s.name}`}
                          className="font-medium hover:underline"
                        >
                          {s.name}
                        </Link>
                        {s.isDedicated && (
                          <Badge variant="outline">Dedicated</Badge>
                        )}
                        {s.backedBy === "docker" && (
                          <Badge
                            variant="outline"
                            className="border-sky-500/50 text-sky-600 dark:text-sky-400"
                            title="Running as a Docker container"
                          >
                            Docker
                          </Badge>
                        )}
                        {s.sync && (
                          <Badge
                            variant="outline"
                            title={
                              s.sync.lastPushAt
                                ? `Last saved ${new Date(s.sync.lastPushAt).toLocaleString()} · canonical ${formatBytes(s.sync.canonicalSizeBytes)} (${s.sync.lastPushFiles} files)`
                                : `Canonical ${formatBytes(s.sync.canonicalSizeBytes)} · not yet pushed`
                            }
                            className="border-emerald-500/50 text-emerald-600 dark:text-emerald-400"
                          >
                            Persistent
                          </Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      {s.isDedicated ? "—" : s.groupName}
                    </TableCell>
                    <TableCell>
                      <span className="text-muted-foreground text-sm">
                        {s.nodeId && s.nodeId !== "local" ? s.nodeId : "local"}
                      </span>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={serviceStateColors[s.state] ?? ""}
                      >
                        {s.state}
                      </Badge>
                    </TableCell>
                    <TableCell>{s.port}</TableCell>
                    <TableCell className="text-right">{s.playerCount}</TableCell>
                    <TableCell>
                      <MemoryBar
                        usedMb={s.memoryUsedMb}
                        maxMb={s.memoryMaxMb}
                      />
                    </TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger
                          render={
                            <Button
                              variant="ghost"
                              size="icon"
                              className="size-8"
                            >
                              <MoreHorizontal className="size-4" />
                            </Button>
                          }
                        />
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem
                            onClick={() => setConsoleService(s.name)}
                          >
                            <TerminalIcon className="mr-2 size-4" />
                            Open Console
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={() => serviceAction(s.name, "start")}
                          >
                            <Play className="mr-2 size-4" />
                            Start
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={() => serviceAction(s.name, "restart")}
                          >
                            <RotateCw className="mr-2 size-4" />
                            Restart
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            onClick={() => serviceAction(s.name, "stop")}
                          >
                            <Square className="mr-2 size-4" />
                            Stop
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
        </CardContent>
      </Card>
      <ServiceConsoleSheet
        serviceName={consoleService}
        open={consoleService !== null}
        onOpenChange={(o) => {
          if (!o) setConsoleService(null);
        }}
      />
    </PageShell>
  );
}
