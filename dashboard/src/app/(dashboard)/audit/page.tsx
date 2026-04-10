"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
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
import { Search, ScrollText } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";

interface AuditEntry {
  timestamp: string;
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

function formatTimestamp(ts: string): string {
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionFilter, actorFilter]);

  const filters = (
    <>
      <div className="relative">
        <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
        <Input
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          placeholder="Filter action…"
          className="pl-9 w-40"
        />
      </div>
      <div className="relative">
        <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
        <Input
          value={actorFilter}
          onChange={(e) => setActorFilter(e.target.value)}
          placeholder="Filter actor…"
          className="pl-9 w-40"
        />
      </div>
    </>
  );

  return (
    <>
      <PageHeader
        title="Audit Log"
        description={`${entries.length} most recent entries · every state change is recorded.`}
        actions={filters}
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : entries.length === 0 ? (
        <EmptyState
          icon={ScrollText}
          title="No audit entries"
          description="Nothing matches your current filters, or nothing has happened yet."
        />
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-6">Time</TableHead>
                  <TableHead>Actor</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Target</TableHead>
                  <TableHead>Details</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map((e, i) => (
                  <TableRow key={i}>
                    <TableCell className="pl-6 text-xs text-muted-foreground whitespace-nowrap">
                      {formatTimestamp(e.timestamp)}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="text-xs">
                        {e.actor}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {e.action}
                    </TableCell>
                    <TableCell className="text-sm">{e.target}</TableCell>
                    <TableCell className="text-xs text-muted-foreground max-w-xs truncate">
                      {e.details || "—"}
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
