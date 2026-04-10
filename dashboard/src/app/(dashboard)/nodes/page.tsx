"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
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
import { statusColors } from "@/lib/status";
import { toast } from "sonner";
import { Network } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";

interface Node {
  nodeId: string;
  host: string;
  maxMemory: string;
  maxServices: number;
  currentServices: number;
  cpuUsage: number;
  memoryUsedMb: number;
  memoryTotalMb: number;
  isConnected: boolean;
  agentVersion: string;
}

interface NodeListResponse {
  nodes: Node[];
  total: number;
}

export default function NodesPage() {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<NodeListResponse>("/api/nodes")
      .then((data) => setNodes(data.nodes))
      .catch((e) =>
        toast.error(e instanceof Error ? e.message : "Failed to load nodes")
      )
      .finally(() => setLoading(false));
  }, []);

  return (
    <>
      <PageHeader
        title="Nodes"
        description={`${nodes.length} node${
          nodes.length === 1 ? "" : "s"
        } · remote agents that run services on this controller's behalf.`}
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : nodes.length === 0 ? (
        <EmptyState
          icon={Network}
          title="No cluster nodes"
          description="This controller is running in single-node mode. Install an agent on another host to scale out."
        />
      ) : (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-6">Node ID</TableHead>
                  <TableHead>Host</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Version</TableHead>
                  <TableHead className="text-right">Services</TableHead>
                  <TableHead className="text-right">CPU</TableHead>
                  <TableHead className="text-right">Memory</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {nodes.map((n) => (
                  <TableRow key={n.nodeId}>
                    <TableCell className="pl-6 font-medium">
                      {n.nodeId}
                    </TableCell>
                    <TableCell className="font-mono text-sm">
                      {n.host}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant="outline"
                        className={
                          n.isConnected
                            ? statusColors.online
                            : statusColors.inactive
                        }
                      >
                        {n.isConnected ? "Connected" : "Disconnected"}
                      </Badge>
                    </TableCell>
                    <TableCell>{n.agentVersion}</TableCell>
                    <TableCell className="text-right">
                      {n.currentServices}/{n.maxServices}
                    </TableCell>
                    <TableCell className="text-right">
                      {n.cpuUsage.toFixed(0)}%
                    </TableCell>
                    <TableCell className="text-right">
                      {n.memoryUsedMb} / {n.memoryTotalMb} MB
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
