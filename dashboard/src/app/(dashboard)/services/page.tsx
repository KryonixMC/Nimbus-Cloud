"use client";

import { useEffect, useState } from "react";
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
import { Skeleton } from "@/components/ui/skeleton";
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
} from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";
import { MemoryBar } from "@/components/memory-bar";

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
}

interface ServiceListResponse {
  services: Service[];
  total: number;
}

export default function ServicesPage() {
  const [services, setServices] = useState<Service[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [startOpen, setStartOpen] = useState(false);
  const [selectedGroup, setSelectedGroup] = useState("");
  const [starting, setStarting] = useState(false);

  async function load() {
    try {
      const [svc, grp] = await Promise.all([
        apiFetch<ServiceListResponse>("/api/services"),
        apiFetch<{ groups: { name: string }[] }>("/api/groups").catch(() => ({
          groups: [],
        })),
      ]);
      setServices(svc.services);
      setGroups(grp.groups.map((g) => g.name));
    } catch {
      // handled by apiFetch
    } finally {
      setLoading(false);
    }
  }

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

  useEffect(() => {
    load();
    const interval = setInterval(load, 5000);
    return () => clearInterval(interval);
  }, []);

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
    <>
      <PageHeader
        title="Services"
        description={`${services.length} running service${
          services.length === 1 ? "" : "s"
        } across the cluster.`}
        actions={startDialog}
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : services.length === 0 ? (
        <EmptyState
          icon={Server}
          title="No services running"
          description="Start a service from one of your groups to see it here."
        />
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-6">Name</TableHead>
                  <TableHead>Group</TableHead>
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
                      </div>
                    </TableCell>
                    <TableCell>
                      {s.isDedicated ? "—" : s.groupName}
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
      )}
    </>
  );
}
