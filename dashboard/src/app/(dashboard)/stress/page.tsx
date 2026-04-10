"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { apiFetch } from "@/lib/api";
import { statusColors } from "@/lib/status";
import { toast } from "sonner";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import { Play, Square, Activity, Users, Gauge, Clock } from "lucide-react";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";

interface StressStatus {
  active: boolean;
  group: string | null;
  currentPlayers: number;
  targetPlayers: number;
  totalCapacity: number;
  overflow: number;
  elapsedSeconds: number;
  services: Record<string, number>;
  proxyServices: Record<string, number>;
}

interface GroupInfo {
  name: string;
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}m ${s}s`;
}

export default function StressPage() {
  const [status, setStatus] = useState<StressStatus | null>(null);
  const [groups, setGroups] = useState<GroupInfo[]>([]);
  const [loading, setLoading] = useState(true);

  // Start form
  const [players, setPlayers] = useState(100);
  const [group, setGroup] = useState("");
  const [rampSeconds, setRampSeconds] = useState(0);
  const [starting, setStarting] = useState(false);

  // Ramp form
  const [rampTarget, setRampTarget] = useState(200);
  const [rampDuration, setRampDuration] = useState(30);

  async function load() {
    try {
      const [s, g] = await Promise.all([
        apiFetch<StressStatus>("/api/stress"),
        apiFetch<{ groups: GroupInfo[] }>("/api/groups").catch(() => ({
          groups: [],
        })),
      ]);
      setStatus(s);
      setGroups(g.groups);
    } catch {
      // ignored
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    const interval = setInterval(load, 3000);
    return () => clearInterval(interval);
  }, []);

  async function start() {
    setStarting(true);
    try {
      await apiFetch("/api/stress/start", {
        method: "POST",
        body: JSON.stringify({
          players,
          group: group || undefined,
          rampSeconds,
        }),
      });
      toast.success("Stress test started");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to start");
    } finally {
      setStarting(false);
    }
  }

  async function stop() {
    try {
      await apiFetch("/api/stress/stop", { method: "POST" });
      toast.success("Stress test stopped");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to stop");
    }
  }

  async function ramp() {
    try {
      await apiFetch("/api/stress/ramp", {
        method: "POST",
        body: JSON.stringify({
          players: rampTarget,
          durationSeconds: rampDuration,
        }),
      });
      toast.success(`Ramping to ${rampTarget} players`);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to ramp");
    }
  }

  return (
    <>
      <PageHeader
        title="Stress Test"
        description="Simulate player load across backend groups without launching real Minecraft clients."
        actions={
          status?.active && (
            <Button variant="destructive" onClick={stop}>
              <Square className="mr-1 size-4" /> Stop test
            </Button>
          )
        }
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="Status"
              icon={Activity}
              tone={status?.active ? "primary" : "default"}
              value={
                <Badge
                  variant="outline"
                  className={
                    status?.active ? statusColors.active : statusColors.inactive
                  }
                >
                  {status?.active ? "Active" : "Inactive"}
                </Badge>
              }
              hint={status?.group ?? "all groups"}
            />
            <StatCard
              label="Players"
              icon={Users}
              value={`${status?.currentPlayers ?? 0} / ${
                status?.targetPlayers ?? 0
              }`}
              hint="current / target"
            />
            <StatCard
              label="Capacity"
              icon={Gauge}
              value={status?.totalCapacity ?? 0}
              tone={(status?.overflow ?? 0) > 0 ? "destructive" : "default"}
              hint={
                (status?.overflow ?? 0) > 0
                  ? `${status!.overflow} overflow`
                  : "across backends"
              }
            />
            <StatCard
              label="Elapsed"
              icon={Clock}
              value={status?.active ? formatDuration(status.elapsedSeconds) : "—"}
              hint={status?.active ? "running" : "idle"}
            />
          </div>

          {status?.active && Object.keys(status.services).length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>Per service</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(status.services).map(([name, count]) => (
                    <Badge key={name} variant="secondary">
                      {name}: {count}
                    </Badge>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}

          <div className="grid gap-4 md:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>Start test</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <Field>
                  <FieldLabel>Players</FieldLabel>
                  <Input
                    type="number"
                    min={1}
                    value={players}
                    onChange={(e) => setPlayers(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Group (optional)</FieldLabel>
                  <Select value={group} onValueChange={(v) => setGroup(v ?? "")}>
                    <SelectTrigger className="w-full">
                      <SelectValue placeholder="All groups" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem value=" ">All groups</SelectItem>
                        {groups.map((g) => (
                          <SelectItem key={g.name} value={g.name}>
                            {g.name}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel>Ramp duration</FieldLabel>
                  <Input
                    type="number"
                    min={0}
                    value={rampSeconds}
                    onChange={(e) => setRampSeconds(Number(e.target.value))}
                  />
                  <FieldDescription>Seconds (0 = instant)</FieldDescription>
                </Field>
                <Button
                  onClick={start}
                  disabled={starting || status?.active === true}
                  className="w-full"
                >
                  <Play className="mr-1 size-4" />
                  {starting ? "Starting…" : "Start stress test"}
                </Button>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>Ramp existing test</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <Field>
                    <FieldLabel>Target players</FieldLabel>
                    <Input
                      type="number"
                      min={0}
                      value={rampTarget}
                      onChange={(e) => setRampTarget(Number(e.target.value))}
                    />
                  </Field>
                  <Field>
                    <FieldLabel>Duration (s)</FieldLabel>
                    <Input
                      type="number"
                      min={1}
                      value={rampDuration}
                      onChange={(e) => setRampDuration(Number(e.target.value))}
                    />
                  </Field>
                </div>
                <Button
                  variant="outline"
                  onClick={ramp}
                  disabled={!status?.active}
                  className="w-full"
                >
                  Ramp to target
                </Button>
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
