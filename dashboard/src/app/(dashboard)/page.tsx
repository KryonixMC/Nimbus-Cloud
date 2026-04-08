"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { iconColors } from "@/lib/status";
import { Activity, Server, Users, Clock, CircleDot } from "lucide-react";
import Link from "next/link";

interface GroupStatus {
  name: string;
  instances: number;
  maxInstances: number;
  players: number;
  maxPlayers: number;
  software: string;
  version: string;
}

interface StatusData {
  networkName: string;
  online: boolean;
  uptimeSeconds: number;
  totalServices: number;
  totalPlayers: number;
  groups: GroupStatus[];
}

interface HealthData {
  status: string;
  uptime: number;
  services: number;
}

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export default function OverviewPage() {
  const [status, setStatus] = useState<StatusData | null>(null);
  const [health, setHealth] = useState<HealthData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      try {
        const [s, h] = await Promise.all([
          apiFetch<StatusData>("/api/status"),
          apiFetch<HealthData>("/api/health"),
        ]);
        setStatus(s);
        setHealth(h);
      } catch {
        // handled by apiFetch redirect
      } finally {
        setLoading(false);
      }
    }
    load();
    const interval = setInterval(load, 5000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="space-y-4">
        <div className="grid gap-4 md:grid-cols-4">
          {[...Array(4)].map((_, i) => (
            <Skeleton key={i} className="h-28 rounded-xl" />
          ))}
        </div>
        <Skeleton className="h-64 rounded-xl" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-4 md:grid-cols-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Status</CardTitle>
            <Activity className="size-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-2 text-2xl font-bold">
              <CircleDot className={`size-4 ${status?.online ? iconColors.online : iconColors.offline}`} />
              {status?.online ? "Online" : "Offline"}
            </div>
            <p className="text-xs text-muted-foreground">
              {status?.networkName ?? "Nimbus"}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Services</CardTitle>
            <Server className="size-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {status?.totalServices ?? 0}
            </div>
            <p className="text-xs text-muted-foreground">
              {status?.groups?.length ?? 0} groups
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Players</CardTitle>
            <Users className="size-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {status?.totalPlayers ?? 0}
            </div>
            <p className="text-xs text-muted-foreground">online</p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Uptime</CardTitle>
            <Clock className="size-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {status ? formatUptime(status.uptimeSeconds) : "-"}
            </div>
            <p className="text-xs text-muted-foreground">controller</p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Groups</CardTitle>
        </CardHeader>
        <CardContent>
          {!status?.groups?.length ? (
            <p className="text-sm text-muted-foreground">No groups configured</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Software</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead className="text-right">Instances</TableHead>
                  <TableHead className="text-right">Players</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {status.groups.map((group) => (
                  <TableRow key={group.name} className="cursor-pointer">
                    <TableCell className="font-medium">
                      <Link href={`/groups/${group.name}`} className="hover:underline">
                        {group.name}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{group.software}</Badge>
                    </TableCell>
                    <TableCell>{group.version}</TableCell>
                    <TableCell className="text-right">
                      {group.instances}/{group.maxInstances}
                    </TableCell>
                    <TableCell className="text-right">
                      {group.players}/{group.maxPlayers}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
