"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import { MoreHorizontal, Play, Square, RotateCw, Plus, Server } from "lucide-react";

interface Service {
  name: string;
  groupName: string;
  state: string;
  port: number;
  playerCount: number;
  tps: number;
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
        apiFetch<{ groups: { name: string }[] }>("/api/groups").catch(() => ({ groups: [] })),
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

  async function serviceAction(name: string, action: "start" | "stop" | "restart") {
    try {
      await apiFetch(`/api/services/${name}/${action}`, { method: "POST" });
      toast.success(`${action} sent to ${name}`);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Action failed");
    }
  }

  if (loading) {
    return <Skeleton className="h-96 rounded-xl" />;
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Services ({services.length})</CardTitle>
        <Dialog open={startOpen} onOpenChange={setStartOpen}>
          <DialogTrigger render={
            <Button>
              <Plus className="mr-1 size-4" /> Start Service
            </Button>
          } />
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Start Service</DialogTitle>
              <DialogDescription>
                Select a group to start a new service instance.
              </DialogDescription>
            </DialogHeader>
            <Select value={selectedGroup} onValueChange={(v) => v && setSelectedGroup(v)}>
              <SelectTrigger className="w-full">
                <SelectValue placeholder="Select group..." />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {groups.map((g) => (
                    <SelectItem key={g} value={g}>{g}</SelectItem>
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
      </CardHeader>
      <CardContent>
        {services.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <Server className="size-10 text-muted-foreground/50 mb-3" />
            <p className="text-sm font-medium text-muted-foreground">No services running</p>
            <p className="text-xs text-muted-foreground/70 mt-1">Start a service from one of your groups</p>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Group</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Port</TableHead>
                <TableHead className="text-right">Players</TableHead>
                <TableHead className="text-right">TPS</TableHead>
                <TableHead className="text-right">Memory</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {services.map((s) => (
                <TableRow key={s.name}>
                  <TableCell>
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
                  <TableCell>{s.isDedicated ? "—" : s.groupName}</TableCell>
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
                  <TableCell className="text-right">
                    {s.tps?.toFixed(1) ?? "-"}
                  </TableCell>
                  <TableCell className="text-right text-xs text-muted-foreground">
                    {s.memoryUsedMb}/{s.memoryMaxMb} MB
                  </TableCell>
                  <TableCell>
                    <DropdownMenu>
                      <DropdownMenuTrigger
                        render={
                          <Button variant="ghost" size="icon" className="size-8">
                            <MoreHorizontal className="size-4" />
                          </Button>
                        }
                      />
                      <DropdownMenuContent align="end">
                        <DropdownMenuItem onClick={() => serviceAction(s.name, "start")}>
                          <Play className="mr-2 size-4" />
                          Start
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => serviceAction(s.name, "restart")}>
                          <RotateCw className="mr-2 size-4" />
                          Restart
                        </DropdownMenuItem>
                        <DropdownMenuItem onClick={() => serviceAction(s.name, "stop")}>
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
        )}
      </CardContent>
    </Card>
  );
}
