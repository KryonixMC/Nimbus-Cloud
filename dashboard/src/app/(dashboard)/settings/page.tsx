"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { apiFetch } from "@/lib/api";
import { dotColors } from "@/lib/status";
import { toast } from "sonner";
import { Save, RefreshCw } from "lucide-react";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";

interface ConfigResponse {
  networkName: string;
  consoleColored: boolean;
  consoleLogEvents: boolean;
}

interface ModuleInfo {
  id: string;
  name: string;
  version: string;
  loaded: boolean;
}

export default function SettingsPage() {
  const [config, setConfig] = useState<ConfigResponse | null>(null);
  const [modules, setModules] = useState<ModuleInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // Edit state
  const [networkName, setNetworkName] = useState("");
  const [consoleColored, setConsoleColored] = useState(true);
  const [consoleLogEvents, setConsoleLogEvents] = useState(true);

  useEffect(() => {
    Promise.all([
      apiFetch<ConfigResponse>("/api/config").catch(() => null),
      apiFetch<{ modules: ModuleInfo[] }>("/api/modules")
        .then((d) => d.modules)
        .catch(() => []),
    ]).then(([c, m]) => {
      if (c) {
        setConfig(c);
        setNetworkName(c.networkName);
        setConsoleColored(c.consoleColored);
        setConsoleLogEvents(c.consoleLogEvents);
      }
      setModules(m);
      setLoading(false);
    });
  }, []);

  async function saveConfig() {
    setSaving(true);
    try {
      await apiFetch("/api/config", {
        method: "PATCH",
        body: JSON.stringify({
          networkName,
          consoleColored,
          consoleLogEvents,
        }),
      });
      toast.success("Config saved");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  }

  async function reloadGroups() {
    try {
      await apiFetch("/api/reload", { method: "POST" });
      toast.success("Groups reloaded");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Reload failed");
    }
  }

  if (loading) return <Skeleton className="h-96 rounded-xl" />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-2xl font-bold">Settings</h2>
        <div className="flex items-center gap-2">
          <Button variant="outline" onClick={reloadGroups}>
            <RefreshCw className="mr-1 size-4" /> Reload Groups
          </Button>
          <Button onClick={saveConfig} disabled={saving}>
            <Save className="mr-1 size-4" />
            {saving ? "Saving..." : "Save"}
          </Button>
        </div>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Network</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <Field>
              <FieldLabel>Network Name</FieldLabel>
              <Input
                value={networkName}
                onChange={(e) => setNetworkName(e.target.value)}
              />
              <FieldDescription>Shown in MOTD and dashboard</FieldDescription>
            </Field>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Console</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between">
              <FieldLabel>Colored output</FieldLabel>
              <Switch checked={consoleColored} onCheckedChange={setConsoleColored} />
            </div>
            <div className="flex items-center justify-between">
              <FieldLabel>Log events to console</FieldLabel>
              <Switch checked={consoleLogEvents} onCheckedChange={setConsoleLogEvents} />
            </div>
          </CardContent>
        </Card>

        <Card className="md:col-span-2">
          <CardHeader>
            <CardTitle>Modules</CardTitle>
          </CardHeader>
          <CardContent>
            {modules.length === 0 ? (
              <p className="text-sm text-muted-foreground">No modules installed</p>
            ) : (
              <div className="grid gap-2 md:grid-cols-2">
                {modules.map((m) => (
                  <div
                    key={m.id}
                    className="flex items-center justify-between rounded-md border px-4 py-3"
                  >
                    <div>
                      <div className="font-medium">{m.name}</div>
                      <div className="text-xs text-muted-foreground">
                        {m.id} v{m.version}
                      </div>
                    </div>
                    <div
                      className={`size-2 rounded-full ${
                        m.loaded ? dotColors.active : dotColors.inactive
                      }`}
                    />
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
