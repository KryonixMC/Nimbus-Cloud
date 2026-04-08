"use client";

import { useEffect, useRef, useState } from "react";
import { use } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch, apiWebSocketReconnect } from "@/lib/api";
import { serviceStateColors } from "@/lib/status";
import { AnsiLine } from "@/components/ansi-line";
import { toast } from "sonner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig,
} from "@/components/ui/chart";
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { useServiceMetrics } from "@/lib/metrics";
import { Play, Square, RotateCw, Send, ArrowLeft } from "lucide-react";
import Link from "next/link";

interface ServiceDetail {
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
}

const tpsConfig: ChartConfig = { tps: { label: "TPS", color: "var(--chart-1)" } };
const memConfig: ChartConfig = { memory: { label: "Memory (MB)", color: "var(--chart-2)" } };

export default function ServiceDetailPage({
  params,
}: {
  params: Promise<{ name: string }>;
}) {
  const { name } = use(params);
  const [service, setService] = useState<ServiceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [consoleLines, setConsoleLines] = useState<string[]>([]);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [command, setCommand] = useState("");
  const wsRef = useRef<WebSocket | null>(null);
  const consoleEndRef = useRef<HTMLDivElement>(null);
  const metrics = useServiceMetrics(name);

  useEffect(() => {
    apiFetch<ServiceDetail>(`/api/services/${name}`)
      .then(setService)
      .catch((e) => toast.error(e instanceof Error ? e.message : "Failed to load service"))
      .finally(() => setLoading(false));
    apiFetch<{ lines: string[] }>(`/api/services/${name}/logs?lines=200`)
      .then((data) => setLogLines(data.lines))
      .catch(() => {}); // logs are optional
    const interval = setInterval(() => {
      apiFetch<ServiceDetail>(`/api/services/${name}`)
        .then(setService)
        .catch(() => {}); // silent on periodic refresh
    }, 5000);
    return () => clearInterval(interval);
  }, [name]);

  useEffect(() => {
    const { getSocket, cleanup } = apiWebSocketReconnect(
      `/api/services/${name}/console`,
      {
        onOpen: (ws) => { wsRef.current = ws; },
        onMessage: (event) => {
          setConsoleLines((prev) => {
            const next = [...prev, event.data];
            return next.length > 500 ? next.slice(-500) : next;
          });
        },
        onError: () => toast.error("Console connection failed"),
        onClose: () => setConsoleLines((prev) => [...prev, "--- disconnected ---"]),
      }
    );
    return cleanup;
  }, [name]);

  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [consoleLines]);

  function sendCommand(e: React.FormEvent) {
    e.preventDefault();
    if (!command.trim() || !wsRef.current) return;
    wsRef.current.send(command);
    setCommand("");
  }

  async function serviceAction(action: "start" | "stop" | "restart") {
    try {
      await apiFetch(`/api/services/${name}/${action}`, { method: "POST" });
      toast.success(`${action} sent`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Action failed");
    }
  }

  if (loading) return <Skeleton className="h-96 rounded-xl" />;
  if (!service) return <p className="text-muted-foreground">Service not found</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Link href="/services" className="inline-flex items-center justify-center size-8 rounded-md hover:bg-accent hover:text-accent-foreground transition-colors">
            <ArrowLeft className="size-4" />
          </Link>
          <h2 className="text-2xl font-bold">{service.name}</h2>
          <Badge variant="outline" className={serviceStateColors[service.state] ?? ""}>
            {service.state}
          </Badge>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={() => serviceAction("start")}>
            <Play className="mr-1 size-4" /> Start
          </Button>
          <Button variant="outline" onClick={() => serviceAction("restart")}>
            <RotateCw className="mr-1 size-4" /> Restart
          </Button>
          <Button variant="destructive" onClick={() => serviceAction("stop")}>
            <Square className="mr-1 size-4" /> Stop
          </Button>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-4">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">Group</CardTitle></CardHeader>
          <CardContent className="text-xl font-bold">{service.groupName}</CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">Port</CardTitle></CardHeader>
          <CardContent className="text-xl font-bold">{service.port}</CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">Players</CardTitle></CardHeader>
          <CardContent className="text-xl font-bold">{service.playerCount}</CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">TPS</CardTitle></CardHeader>
          <CardContent className="text-xl font-bold">{service.tps?.toFixed(1) ?? "-"}</CardContent>
        </Card>
      </div>

      {/* Charts from shared metrics */}
      {metrics.length >= 2 && (
        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">TPS</CardTitle></CardHeader>
            <CardContent>
              <ChartContainer config={tpsConfig} className="h-48 w-full">
                <AreaChart data={metrics}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="time" tick={{ fontSize: 9 }} interval="preserveStartEnd" />
                  <YAxis tick={{ fontSize: 9 }} domain={[0, 20]} />
                  <ChartTooltip content={<ChartTooltipContent />} />
                  <Area type="monotone" dataKey="tps" fill="var(--color-tps)" fillOpacity={0.2} stroke="var(--color-tps)" strokeWidth={2} />
                </AreaChart>
              </ChartContainer>
            </CardContent>
          </Card>
          <Card>
            <CardHeader className="pb-2"><CardTitle className="text-sm font-medium">Memory (MB)</CardTitle></CardHeader>
            <CardContent>
              <ChartContainer config={memConfig} className="h-48 w-full">
                <AreaChart data={metrics}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="time" tick={{ fontSize: 9 }} interval="preserveStartEnd" />
                  <YAxis tick={{ fontSize: 9 }} />
                  <ChartTooltip content={<ChartTooltipContent />} />
                  <Area type="monotone" dataKey="memory" fill="var(--color-memory)" fillOpacity={0.2} stroke="var(--color-memory)" strokeWidth={2} />
                </AreaChart>
              </ChartContainer>
            </CardContent>
          </Card>
        </div>
      )}

      <Tabs defaultValue="console">
        <TabsList>
          <TabsTrigger value="console">Console</TabsTrigger>
          <TabsTrigger value="logs">Logs ({logLines.length})</TabsTrigger>
        </TabsList>
        <TabsContent value="console" className="mt-4">
          <Card>
            <CardContent className="pt-6">
              <div className="h-96 overflow-y-auto rounded-md bg-black p-3 font-mono text-xs text-gray-300 scrollbar-thin">
                {consoleLines.map((line, i) => (<AnsiLine key={i} text={line} />))}
                <div ref={consoleEndRef} />
              </div>
              <form onSubmit={sendCommand} className="mt-2 flex items-center gap-2">
                <Input value={command} onChange={(e) => setCommand(e.target.value)} placeholder="Enter command..." className="font-mono" />
                <Button type="submit" size="icon"><Send className="size-4" /></Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="logs" className="mt-4">
          <Card>
            <CardContent className="pt-6">
              <div className="h-96 overflow-y-auto rounded-md bg-black p-3 font-mono text-xs text-gray-300 scrollbar-thin">
                {logLines.map((line, i) => (<AnsiLine key={i} text={line} />))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
}
