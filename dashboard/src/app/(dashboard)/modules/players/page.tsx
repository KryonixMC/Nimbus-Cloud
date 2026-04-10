"use client";

import { useEffect, useState, useCallback } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
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
import { statusColors } from "@/lib/status";
import { PlayerSheet } from "@/components/player-sheet";
import { Search, Users, UserCheck, Server } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";
import { EmptyState } from "@/components/empty-state";

interface PlayerEntry {
  uuid: string;
  name: string;
  firstSeen: string;
  lastSeen: string;
  totalPlaytimeSeconds: string;
  online: string;
}

interface PlayerStats {
  online: number;
  totalUnique: number;
  perService: Record<string, number>;
}

function formatPlaytime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString();
  } catch {
    return iso;
  }
}

export default function PlayersModulePage() {
  const [players, setPlayers] = useState<PlayerEntry[]>([]);
  const [stats, setStats] = useState<PlayerStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [selectedPlayer, setSelectedPlayer] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);

  const load = useCallback(async (query: string) => {
    try {
      const params = query ? `?q=${encodeURIComponent(query)}` : "";
      const [p, s] = await Promise.all([
        apiFetch<PlayerEntry[]>(`/api/players/all${params}`).catch(() => []),
        apiFetch<PlayerStats>("/api/players/stats").catch(() => null),
      ]);
      setPlayers(p);
      setStats(s);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load("");
  }, [load]);

  useEffect(() => {
    const timeout = setTimeout(() => load(search), 300);
    return () => clearTimeout(timeout);
  }, [search, load]);

  const searchInput = (
    <div className="relative w-64">
      <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
      <Input
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search players…"
        className="pl-9"
      />
    </div>
  );

  return (
    <>
      <PageHeader
        title="Players"
        description="Centralised player tracking, session history and per-service breakdown."
        actions={searchInput}
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-3">
            <StatCard
              label="Online now"
              icon={UserCheck}
              tone="primary"
              value={stats?.online ?? 0}
              hint="across all services"
            />
            <StatCard
              label="Total unique"
              icon={Users}
              value={stats?.totalUnique ?? 0}
              hint="ever seen"
            />
            <Card>
              <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                <CardTitle className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Per service
                </CardTitle>
                <Server className="size-4 text-muted-foreground" />
              </CardHeader>
              <CardContent>
                <div className="flex flex-wrap gap-1">
                  {stats?.perService &&
                    Object.entries(stats.perService).map(([service, count]) => (
                      <Badge
                        key={service}
                        variant="secondary"
                        className="text-xs"
                      >
                        {service}: {count}
                      </Badge>
                    ))}
                  {(!stats?.perService ||
                    Object.keys(stats.perService).length === 0) && (
                    <span className="text-sm text-muted-foreground">—</span>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>

          {players.length === 0 ? (
            <EmptyState
              icon={Users}
              title={search ? "No players found" : "No players tracked yet"}
              description={
                search
                  ? `No players match "${search}".`
                  : "Players will appear here as they join any backend."
              }
            />
          ) : (
            <Card>
              <CardContent className="p-0">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="pl-6 w-14" />
                      <TableHead>Name</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>First seen</TableHead>
                      <TableHead>Last seen</TableHead>
                      <TableHead className="text-right pr-6">Playtime</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {players.map((p) => (
                      <TableRow
                        key={p.uuid}
                        className="cursor-pointer"
                        onClick={() => {
                          setSelectedPlayer(p.uuid);
                          setSheetOpen(true);
                        }}
                      >
                        <TableCell className="pl-6">
                          <img
                            src={`https://mc-heads.net/avatar/${p.uuid}/32`}
                            alt={p.name}
                            className="size-8 min-w-8 rounded-sm"
                          />
                        </TableCell>
                        <TableCell className="font-medium">{p.name}</TableCell>
                        <TableCell>
                          <Badge
                            variant="outline"
                            className={
                              p.online === "true"
                                ? statusColors.online
                                : statusColors.inactive
                            }
                          >
                            {p.online === "true" ? "Online" : "Offline"}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-sm text-muted-foreground">
                          {formatDate(p.firstSeen)}
                        </TableCell>
                        <TableCell className="text-sm text-muted-foreground">
                          {formatDate(p.lastSeen)}
                        </TableCell>
                        <TableCell className="text-right text-sm pr-6">
                          {formatPlaytime(Number(p.totalPlaytimeSeconds))}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </div>
      )}

      <PlayerSheet
        uuid={selectedPlayer}
        open={sheetOpen}
        onOpenChange={setSheetOpen}
      />
    </>
  );
}
