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
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <Skeleton className="h-96 rounded-xl" />;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Cluster Nodes ({nodes.length})</CardTitle>
      </CardHeader>
      <CardContent>
        {nodes.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No cluster nodes (single-node mode)
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Node ID</TableHead>
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
                  <TableCell className="font-medium">{n.nodeId}</TableCell>
                  <TableCell className="font-mono text-sm">{n.host}</TableCell>
                  <TableCell>
                    <Badge
                      variant="outline"
                      className={
                        n.isConnected
                          ? "bg-green-500/20 text-green-400 border-green-500/30"
                          : "bg-muted text-muted-foreground"
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
        )}
      </CardContent>
    </Card>
  );
}
