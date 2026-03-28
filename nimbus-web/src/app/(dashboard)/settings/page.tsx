"use client";

import { useState } from "react";
import { useConfig } from "@/lib/hooks";
import * as api from "@/lib/api";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { toast } from "sonner";
import { Settings, Save, RotateCcw, Power, AlertTriangle } from "lucide-react";

export default function SettingsPage() {
  const { data: config, loading, refetch } = useConfig();
  const [networkName, setNetworkName] = useState("");
  const [consoleColored, setConsoleColored] = useState(true);
  const [consoleLogEvents, setConsoleLogEvents] = useState(true);
  const [initialized, setInitialized] = useState(false);

  if (config && !initialized) {
    setNetworkName(config.network.name);
    setConsoleColored(config.console.colored);
    setConsoleLogEvents(config.console.logEvents);
    setInitialized(true);
  }

  async function handleSave() {
    try {
      await api.updateConfig({
        networkName,
        consoleColored,
        consoleLogEvents,
      });
      toast.success("Configuration updated");
      refetch();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleReload() {
    try {
      const result = await api.reload();
      toast.success(result.message || "Configuration reloaded");
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleShutdown() {
    if (!confirm("Are you sure you want to shutdown Nimbus? All services will be stopped.")) return;
    try {
      await api.shutdown();
      toast.success("Shutdown initiated");
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="text-muted-foreground">Nimbus configuration</p>
      </div>

      {loading || !config ? (
        <div className="space-y-4">
          {[...Array(3)].map((_, i) => (
            <Card key={i}><CardContent className="h-32 animate-pulse bg-muted rounded" /></Card>
          ))}
        </div>
      ) : (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Settings className="h-5 w-5" />
                General
              </CardTitle>
              <CardDescription>Network and console settings</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>Network Name</Label>
                <Input
                  value={networkName}
                  onChange={(e) => setNetworkName(e.target.value)}
                />
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <Label>Colored Console</Label>
                  <p className="text-xs text-muted-foreground">Enable ANSI colors in console output</p>
                </div>
                <Switch checked={consoleColored} onCheckedChange={setConsoleColored} />
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <Label>Log Events</Label>
                  <p className="text-xs text-muted-foreground">Show events in console output</p>
                </div>
                <Switch checked={consoleLogEvents} onCheckedChange={setConsoleLogEvents} />
              </div>
              <Button onClick={handleSave}>
                <Save className="mr-2 h-4 w-4" />
                Save Changes
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>System Info</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div>
                  <span className="text-muted-foreground">Bind Address:</span>{" "}
                  <span className="font-mono">{config.network.bind}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Max Memory:</span>{" "}
                  <span className="font-mono">{config.controller.maxMemory}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Max Services:</span>{" "}
                  <span className="font-mono">{config.controller.maxServices}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">API Port:</span>{" "}
                  <span className="font-mono">{config.api.port}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Auth:</span>{" "}
                  <Badge variant={config.api.hasToken ? "default" : "destructive"}>
                    {config.api.hasToken ? "Token Set" : "No Auth"}
                  </Badge>
                </div>
                <div>
                  <span className="text-muted-foreground">Paths:</span>{" "}
                  <span className="font-mono text-xs">
                    {config.paths.templates}, {config.paths.services}, {config.paths.logs}
                  </span>
                </div>
              </div>
            </CardContent>
          </Card>

          <Separator />

          <Card>
            <CardHeader>
              <CardTitle>Actions</CardTitle>
              <CardDescription>System management operations</CardDescription>
            </CardHeader>
            <CardContent className="flex gap-4">
              <Button variant="outline" onClick={handleReload}>
                <RotateCcw className="mr-2 h-4 w-4" />
                Reload Configs
              </Button>
              <Button variant="destructive" onClick={handleShutdown}>
                <Power className="mr-2 h-4 w-4" />
                Shutdown Nimbus
              </Button>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
