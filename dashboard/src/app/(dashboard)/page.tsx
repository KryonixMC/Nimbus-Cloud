"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
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
import { Activity, Server, Users, Clock, CircleDot, FolderTree } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";
import { EmptyState } from "@/components/empty-state";

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
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function load() {
      try {
        const s = await apiFetch<StatusData>("/api/status");
        setStatus(s);
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

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={
          status
            ? `${status.networkName} · live status of the whole cluster`
            : "Live status of the whole cluster"
        }
      />

      {loading ? (
        <div className="space-y-4">
          <div className="grid gap-4 md:grid-cols-4">
            {[...Array(4)].map((_, i) => (
              <Skeleton key={i} className="h-28 rounded-xl" />
            ))}
          </div>
          <Skeleton className="h-64 rounded-xl" />
        </div>
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="Status"
              icon={Activity}
              tone={status?.online ? "primary" : "destructive"}
              value={
                <span className="flex items-center gap-2">
                  <CircleDot
                    className={`size-4 ${
                      status?.online ? iconColors.online : iconColors.offline
                    }`}
                  />
                  {status?.online ? "Online" : "Offline"}
                </span>
              }
              hint={status?.networkName ?? "Nimbus"}
            />
            <StatCard
              label="Services"
              icon={Server}
              value={status?.totalServices ?? 0}
              hint={`${status?.groups?.length ?? 0} groups`}
            />
            <StatCard
              label="Players"
              icon={Users}
              value={status?.totalPlayers ?? 0}
              hint="online"
            />
            <StatCard
              label="Uptime"
              icon={Clock}
              value={status ? formatUptime(status.uptimeSeconds) : "—"}
              hint="controller"
            />
          </div>

          <Card>
            <CardHeader>
              <CardTitle>Groups</CardTitle>
            </CardHeader>
            <CardContent>
              {!status?.groups?.length ? (
                <EmptyState
                  icon={FolderTree}
                  title="No groups configured"
                  description="Create your first group to start running services."
                />
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
                          <Link
                            href={`/groups/${group.name}`}
                            className="hover:underline"
                          >
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
      )}
    </>
  );
}
