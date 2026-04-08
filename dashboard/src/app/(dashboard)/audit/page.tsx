"use client";

import { useEffect, useState } from "react";
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
import { Search } from "lucide-react";

interface AuditEntry {
  timestamp: number;
  actor: string;
  action: string;
  target: string;
  details: string | null;
}

interface AuditResponse {
  entries: AuditEntry[];
  limit: number;
  offset: number;
}

function formatTimestamp(ts: number): string {
  return new Date(ts).toLocaleString();
}

export default function AuditPage() {
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionFilter, setActionFilter] = useState("");
  const [actorFilter, setActorFilter] = useState("");

  async function load() {
    try {
      const params = new URLSearchParams({ limit: "100" });
      if (actionFilter) params.set("action", actionFilter);
      if (actorFilter) params.set("actor", actorFilter);
      const data = await apiFetch<AuditResponse>(`/api/audit?${params}`);
      setEntries(data.entries);
    } catch {
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    const timeout = setTimeout(load, 300);
    return () => clearTimeout(timeout);
  }, [actionFilter, actorFilter]);

  if (loading) return <Skeleton className="h-96 rounded-xl" />;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Audit Log ({entries.length})</CardTitle>
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
            <Input
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
              placeholder="Filter action..."
              className="pl-9 w-40"
            />
          </div>
          <div className="relative">
            <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
            <Input
              value={actorFilter}
              onChange={(e) => setActorFilter(e.target.value)}
              placeholder="Filter actor..."
              className="pl-9 w-40"
            />
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">No audit entries</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Actor</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>Target</TableHead>
                <TableHead>Details</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {entries.map((e, i) => (
                <TableRow key={i}>
                  <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                    {formatTimestamp(e.timestamp)}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="text-xs">{e.actor}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{e.action}</TableCell>
                  <TableCell className="text-sm">{e.target}</TableCell>
                  <TableCell className="text-xs text-muted-foreground max-w-xs truncate">
                    {e.details || "-"}
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
