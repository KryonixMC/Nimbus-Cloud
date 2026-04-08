"use client";

import { useEffect, useState } from "react";
import { use } from "react";
import { useRouter } from "next/navigation";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { Save, ArrowLeft } from "lucide-react";
import Link from "next/link";
import {
  Field,
  FieldLabel,
  FieldDescription,
} from "@/components/ui/field";

interface GroupDetail {
  name: string;
  type: string;
  software: string;
  version: string;
  template: string;
  resources: { memory: string; maxPlayers: number };
  scaling: {
    minInstances: number;
    maxInstances: number;
    playersPerInstance: number;
    scaleThreshold: number;
    idleTimeout: number;
  };
  lifecycle: {
    stopOnEmpty: boolean;
    restartOnCrash: boolean;
    maxRestarts: number;
  };
  jvmArgs: string[];
  jvmOptimize: boolean;
  activeInstances: number;
}

export default function GroupDetailPage({
  params,
}: {
  params: Promise<{ name: string }>;
}) {
  const { name } = use(params);
  const router = useRouter();
  const [group, setGroup] = useState<GroupDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [software, setSoftware] = useState("");
  const [version, setVersion] = useState("");
  const [memory, setMemory] = useState("");
  const [maxPlayers, setMaxPlayers] = useState(50);
  const [minInstances, setMinInstances] = useState(1);
  const [maxInstances, setMaxInstances] = useState(4);
  const [playersPerInstance, setPlayersPerInstance] = useState(40);
  const [scaleThreshold, setScaleThreshold] = useState(0.8);
  const [idleTimeout, setIdleTimeout] = useState(0);
  const [stopOnEmpty, setStopOnEmpty] = useState(false);
  const [restartOnCrash, setRestartOnCrash] = useState(true);
  const [maxRestarts, setMaxRestarts] = useState(5);
  const [jvmOptimize, setJvmOptimize] = useState(true);

  useEffect(() => {
    apiFetch<GroupDetail>(`/api/groups/${name}`)
      .then((g) => {
        setGroup(g);
        setSoftware(g.software);
        setVersion(g.version);
        setMemory(g.resources.memory);
        setMaxPlayers(g.resources.maxPlayers);
        setMinInstances(g.scaling.minInstances);
        setMaxInstances(g.scaling.maxInstances);
        setPlayersPerInstance(g.scaling.playersPerInstance);
        setScaleThreshold(g.scaling.scaleThreshold);
        setIdleTimeout(g.scaling.idleTimeout);
        setStopOnEmpty(g.lifecycle.stopOnEmpty);
        setRestartOnCrash(g.lifecycle.restartOnCrash);
        setMaxRestarts(g.lifecycle.maxRestarts);
        setJvmOptimize(g.jvmOptimize);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [name]);

  async function save() {
    setSaving(true);
    try {
      await apiFetch(`/api/groups/${name}`, {
        method: "PUT",
        body: JSON.stringify({
          name, software, version, memory, maxPlayers, minInstances, maxInstances,
          playersPerInstance, scaleThreshold, idleTimeout, stopOnEmpty,
          restartOnCrash, maxRestarts, jvmOptimize,
        }),
      });
      toast.success("Group updated");
      router.push("/groups");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <Skeleton className="h-96 rounded-xl" />;
  if (!group) return <p className="text-muted-foreground">Group not found</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Link href="/groups" className="inline-flex items-center justify-center size-8 rounded-md hover:bg-accent hover:text-accent-foreground transition-colors">
            <ArrowLeft className="size-4" />
          </Link>
          <h2 className="text-2xl font-bold">{group.name}</h2>
        </div>
        <Button onClick={save} disabled={saving}>
          <Save className="mr-1 size-4" />
          {saving ? "Saving..." : "Save Changes"}
        </Button>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Server</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <Field>
              <FieldLabel>Software</FieldLabel>
              <Input value={software} onChange={(e) => setSoftware(e.target.value)} />
              <FieldDescription>PAPER, VELOCITY, PURPUR, FABRIC, etc.</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>Version</FieldLabel>
              <Input value={version} onChange={(e) => setVersion(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel>Memory</FieldLabel>
              <Input value={memory} onChange={(e) => setMemory(e.target.value)} />
              <FieldDescription>e.g. 1G, 512M, 2G</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>Max Players</FieldLabel>
              <Input type="number" value={maxPlayers} onChange={(e) => setMaxPlayers(Number(e.target.value))} />
            </Field>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Scaling</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <Field>
                <FieldLabel>Min Instances</FieldLabel>
                <Input type="number" value={minInstances} onChange={(e) => setMinInstances(Number(e.target.value))} />
              </Field>
              <Field>
                <FieldLabel>Max Instances</FieldLabel>
                <Input type="number" value={maxInstances} onChange={(e) => setMaxInstances(Number(e.target.value))} />
              </Field>
            </div>
            <Field>
              <FieldLabel>Players per Instance</FieldLabel>
              <Input type="number" value={playersPerInstance} onChange={(e) => setPlayersPerInstance(Number(e.target.value))} />
            </Field>
            <Field>
              <FieldLabel>Scale Threshold</FieldLabel>
              <Input type="number" step="0.1" min="0" max="1" value={scaleThreshold} onChange={(e) => setScaleThreshold(Number(e.target.value))} />
              <FieldDescription>0.0 - 1.0, scale up when this % full</FieldDescription>
            </Field>
            <Field>
              <FieldLabel>Idle Timeout (seconds)</FieldLabel>
              <Input type="number" value={idleTimeout} onChange={(e) => setIdleTimeout(Number(e.target.value))} />
              <FieldDescription>0 = never stop idle services</FieldDescription>
            </Field>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Lifecycle</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <FieldLabel>Stop on empty (no players)</FieldLabel>
              <Switch checked={stopOnEmpty} onCheckedChange={setStopOnEmpty} />
            </div>
            <div className="flex items-center justify-between">
              <FieldLabel>Restart on crash</FieldLabel>
              <Switch checked={restartOnCrash} onCheckedChange={setRestartOnCrash} />
            </div>
            <Field>
              <FieldLabel>Max Restarts</FieldLabel>
              <Input type="number" value={maxRestarts} onChange={(e) => setMaxRestarts(Number(e.target.value))} />
            </Field>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>JVM</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <FieldLabel>JVM Optimization</FieldLabel>
                <p className="text-xs text-muted-foreground">Aikar&apos;s flags</p>
              </div>
              <Switch checked={jvmOptimize} onCheckedChange={setJvmOptimize} />
            </div>
            <div className="rounded-md bg-muted p-3 text-xs font-mono text-muted-foreground">
              <p className="font-sans text-sm font-medium text-foreground mb-1">Current JVM Args</p>
              {group.jvmArgs.length > 0
                ? group.jvmArgs.map((arg, i) => <div key={i}>{arg}</div>)
                : <span>None (using defaults)</span>
              }
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
