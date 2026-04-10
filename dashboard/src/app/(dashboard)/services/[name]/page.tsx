"use client";

import { useEffect, useRef, useState, use } from "react";
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
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { useServiceMetrics } from "@/lib/metrics";
import { Play, Square, RotateCw, Send, ArrowLeft } from "lucide-react";
import Link from "next/link";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";
import { MemoryBar } from "@/components/memory-bar";
import { Server, Plug, Users } from "lucide-react";

interface ServiceDetail {
  name: string;
  groupName: string;
  state: string;
  port: number;
  playerCount: number;
  memoryUsedMb: number;
  memoryMaxMb: number;
  healthy: boolean;
  uptime: string | null;
}

const memConfig: ChartConfig = {
  memory: { label: "Memory (MB)", color: "var(--chart-1)" },
  memoryMax: { label: "Limit (MB)", color: "var(--chart-3)" },
};

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
  const sendRef = useRef<((msg: string) => void) | null>(null);
  const consoleEndRef = useRef<HTMLDivElement>(null);
  const metrics = useServiceMetrics(name);

  useEffect(() => {
    apiFetch<ServiceDetail>(`/api/services/${name}`)
      .then(setService)
      .catch((e) =>
        toast.error(e instanceof Error ? e.message : "Failed to load service")
      )
      .finally(() => setLoading(false));
    apiFetch<{ lines: string[] }>(`/api/services/${name}/logs?lines=200`)
      .then((data) => setLogLines(data.lines))
      .catch(() => {});
    const interval = setInterval(() => {
      apiFetch<ServiceDetail>(`/api/services/${name}`)
        .then(setService)
        .catch(() => {});
    }, 5000);
    return () => clearInterval(interval);
  }, [name]);

  useEffect(() => {
    const { send, cleanup } = apiWebSocketReconnect(
      `/api/services/${name}/console`,
      {
        onOpen: () => {
          sendRef.current = send;
        },
        onMessage: (event) => {
          setConsoleLines((prev) => {
            const next = [...prev, event.data];
            return next.length > 500 ? next.slice(-500) : next;
          });
        },
        onError: () => toast.error("Console connection failed"),
        onClose: () => {
          sendRef.current = null;
          setConsoleLines((prev) => [...prev, "--- disconnected ---"]);
        },
      }
    );
    sendRef.current = send;
    return cleanup;
  }, [name]);

  useEffect(() => {
    consoleEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [consoleLines]);

  function sendCommand(e: React.FormEvent) {
    e.preventDefault();
    if (!command.trim() || !sendRef.current) return;
    sendRef.current(command);
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
  if (!service)
    return <p className="text-muted-foreground">Service not found</p>;

  return (
    <>
      <PageHeader
        title={
          <span className="flex items-center gap-3">
            <Link
              href="/services"
              className="inline-flex size-8 items-center justify-center rounded-md hover:bg-accent hover:text-accent-foreground transition-colors"
            >
              <ArrowLeft className="size-4" />
            </Link>
            <span>{service.name}</span>
            <Badge
              variant="outline"
              className={serviceStateColors[service.state] ?? ""}
            >
              {service.state}
            </Badge>
          </span>
        }
        description={`${service.groupName} · port ${service.port}`}
        actions={
          <>
            <Button variant="outline" onClick={() => serviceAction("start")}>
              <Play className="mr-1 size-4" /> Start
            </Button>
            <Button variant="outline" onClick={() => serviceAction("restart")}>
              <RotateCw className="mr-1 size-4" /> Restart
            </Button>
            <Button variant="destructive" onClick={() => serviceAction("stop")}>
              <Square className="mr-1 size-4" /> Stop
            </Button>
          </>
        }
      />

      <div className="grid gap-4 grid-cols-3">
        <StatCard
          label="Group"
          icon={Server}
          value={service.groupName}
          hint={service.healthy ? "healthy" : "unhealthy"}
        />
        <StatCard
          label="Port"
          icon={Plug}
          value={service.port}
          hint={service.uptime ? `up ${service.uptime}` : "not running"}
        />
        <StatCard
          label="Players"
          icon={Users}
          value={service.playerCount}
          hint="online"
        />
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-4 space-y-0 pb-3">
          <CardTitle className="text-sm font-medium">Memory</CardTitle>
          <MemoryBar
            usedMb={service.memoryUsedMb}
            maxMb={service.memoryMaxMb}
            className="w-80"
          />
        </CardHeader>
        <CardContent className="pb-4">
          {metrics.length < 2 ? (
            <div className="flex h-40 items-center justify-center text-xs text-muted-foreground">
              Collecting samples… (history needs at least one full sampling
              interval of ~30 s)
            </div>
          ) : (
            // ChartContainer forces `aspect-video` which, at full card width,
            // makes the chart ~500px tall. Override with !aspect-auto + a
            // fixed height so the graph stays compact.
            <ChartContainer
              config={memConfig}
              className="!aspect-auto h-40 w-full"
            >
              <AreaChart data={metrics}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="time"
                  tick={{ fontSize: 10 }}
                  interval="preserveStartEnd"
                  minTickGap={32}
                />
                <YAxis tick={{ fontSize: 10 }} width={40} />
                <ChartTooltip content={<ChartTooltipContent />} />
                <Area
                  type="monotone"
                  dataKey="memory"
                  fill="var(--color-memory)"
                  fillOpacity={0.25}
                  stroke="var(--color-memory)"
                  strokeWidth={2}
                />
              </AreaChart>
            </ChartContainer>
          )}
        </CardContent>
      </Card>

      <Tabs defaultValue="console">
        <TabsList>
          <TabsTrigger value="console">Console</TabsTrigger>
          <TabsTrigger value="logs">Logs ({logLines.length})</TabsTrigger>
        </TabsList>
        <TabsContent value="console" className="mt-4">
          <Card>
            <CardContent className="p-6">
              <div className="h-96 overflow-y-auto rounded-md bg-black p-3 font-mono text-xs text-gray-300 scrollbar-thin">
                {consoleLines.map((line, i) => (
                  <AnsiLine key={i} text={line} />
                ))}
                <div ref={consoleEndRef} />
              </div>
              <form
                onSubmit={sendCommand}
                className="mt-2 flex items-center gap-2"
              >
                <Input
                  value={command}
                  onChange={(e) => setCommand(e.target.value)}
                  placeholder="Enter command…"
                  className="font-mono"
                />
                <Button type="submit" size="icon">
                  <Send className="size-4" />
                </Button>
              </form>
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="logs" className="mt-4">
          <Card>
            <CardContent className="p-6">
              <div className="h-96 overflow-y-auto rounded-md bg-black p-3 font-mono text-xs text-gray-300 scrollbar-thin">
                {logLines.map((line, i) => (
                  <AnsiLine key={i} text={line} />
                ))}
              </div>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </>
  );
}
