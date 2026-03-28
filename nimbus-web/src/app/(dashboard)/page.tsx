"use client";

import { useStatus } from "@/lib/hooks";
import { useWebSocket } from "@/lib/websocket";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Server,
  Users,
  Clock,
  Activity,
  Wifi,
  WifiOff,
} from "lucide-react";

function formatUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export default function DashboardPage() {
  const { data: status, loading } = useStatus();
  const { events, connected } = useWebSocket();

  if (loading || !status) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {[...Array(4)].map((_, i) => (
            <Card key={i}>
              <CardHeader className="pb-2">
                <div className="h-4 w-24 animate-pulse rounded bg-muted" />
              </CardHeader>
              <CardContent>
                <div className="h-8 w-16 animate-pulse rounded bg-muted" />
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">{status.networkName}</h1>
          <p className="text-muted-foreground">Network Overview</p>
        </div>
        <Badge variant={status.online ? "default" : "destructive"} className="gap-1">
          {status.online ? <Wifi className="h-3 w-3" /> : <WifiOff className="h-3 w-3" />}
          {status.online ? "Online" : "Offline"}
        </Badge>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Services</CardTitle>
            <Server className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{status.totalServices}</div>
            <p className="text-xs text-muted-foreground">
              {status.groups.length} group(s)
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Players</CardTitle>
            <Users className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{status.totalPlayers}</div>
            <p className="text-xs text-muted-foreground">across all services</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Uptime</CardTitle>
            <Clock className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{formatUptime(status.uptimeSeconds)}</div>
            <p className="text-xs text-muted-foreground">since last restart</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-sm font-medium">Events</CardTitle>
            <Activity className="h-4 w-4 text-muted-foreground" />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{events.length}</div>
            <Badge variant={connected ? "default" : "secondary"} className="text-xs">
              {connected ? "Live" : "Disconnected"}
            </Badge>
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Groups</CardTitle>
            <CardDescription>Server group utilization</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {status.groups.map((group) => (
              <div key={group.name} className="space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{group.name}</span>
                    <Badge variant="outline" className="text-xs">
                      {group.software} {group.version}
                    </Badge>
                  </div>
                  <span className="text-muted-foreground">
                    {group.instances}/{group.maxInstances} instances
                  </span>
                </div>
                <Progress
                  value={group.maxPlayers > 0 ? (group.players / group.maxPlayers) * 100 : 0}
                />
                <p className="text-xs text-muted-foreground">
                  {group.players}/{group.maxPlayers} players
                </p>
              </div>
            ))}
            {status.groups.length === 0 && (
              <p className="text-sm text-muted-foreground">No groups configured</p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Recent Events</CardTitle>
            <CardDescription>Live event stream</CardDescription>
          </CardHeader>
          <CardContent>
            <ScrollArea className="h-[300px]">
              <div className="space-y-2">
                {events.slice(-20).reverse().map((event, i) => (
                  <div
                    key={i}
                    className="flex items-start gap-2 rounded-md border p-2 text-sm"
                  >
                    <Badge variant="outline" className="shrink-0 text-xs">
                      {event.type}
                    </Badge>
                    <span className="text-muted-foreground text-xs">
                      {new Date(event.timestamp).toLocaleTimeString()}
                    </span>
                  </div>
                ))}
                {events.length === 0 && (
                  <p className="text-sm text-muted-foreground">
                    No events yet. {connected ? "Waiting..." : "WebSocket disconnected."}
                  </p>
                )}
              </div>
            </ScrollArea>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
