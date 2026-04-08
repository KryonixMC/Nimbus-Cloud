"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { statusColors } from "@/lib/status";
import { toast } from "sonner";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import { Save, Plus, X } from "lucide-react";

// ── Types ──

interface MotdConfig {
  line1: string;
  line2: string;
  maxPlayers: number;
  playerCountOffset: number;
}

interface TabListConfig {
  header: string;
  footer: string;
  playerFormat: string;
  updateInterval: number;
}

interface ChatConfig {
  format: string;
  enabled: boolean;
}

interface GlobalMaintenance {
  enabled: boolean;
  motdLine1: string;
  motdLine2: string;
  protocolText: string;
  kickMessage: string;
  whitelist: string[];
}

interface GroupMaintenance {
  enabled: boolean;
  kickMessage: string;
}

interface MaintenanceStatus {
  global: GlobalMaintenance;
  groups: Record<string, GroupMaintenance>;
}

// ── Sub-components ──

function MotdTab() {
  const [motd, setMotd] = useState<MotdConfig | null>(null);
  const [line1, setLine1] = useState("");
  const [line2, setLine2] = useState("");
  const [maxPlayers, setMaxPlayers] = useState(-1);
  const [offset, setOffset] = useState(0);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<MotdConfig>("/api/proxy/motd").then((d) => {
      setMotd(d);
      setLine1(d.line1);
      setLine2(d.line2);
      setMaxPlayers(d.maxPlayers);
      setOffset(d.playerCountOffset);
    }).catch(() => {});
  }, []);

  async function save() {
    setSaving(true);
    try {
      const res = await apiFetch<MotdConfig>("/api/proxy/motd", {
        method: "PUT",
        body: JSON.stringify({ line1, line2, maxPlayers, playerCountOffset: offset }),
      });
      setMotd(res);
      toast.success("MOTD saved");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setSaving(false);
    }
  }

  if (!motd) return <Skeleton className="h-48 rounded-xl" />;

  return (
    <Card>
      <CardContent className="pt-6 space-y-4">
        <Field>
          <FieldLabel>Line 1</FieldLabel>
          <Input value={line1} onChange={(e) => setLine1(e.target.value)} />
          <FieldDescription>MiniMessage format supported</FieldDescription>
        </Field>
        <Field>
          <FieldLabel>Line 2</FieldLabel>
          <Input value={line2} onChange={(e) => setLine2(e.target.value)} />
          <FieldDescription>Placeholders: {"{online}"}, {"{max}"}</FieldDescription>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field>
            <FieldLabel>Max Players</FieldLabel>
            <Input type="number" value={maxPlayers} onChange={(e) => setMaxPlayers(Number(e.target.value))} />
            <FieldDescription>-1 = real count</FieldDescription>
          </Field>
          <Field>
            <FieldLabel>Player Count Offset</FieldLabel>
            <Input type="number" value={offset} onChange={(e) => setOffset(Number(e.target.value))} />
          </Field>
        </div>
        <Button onClick={save} disabled={saving}>
          <Save className="mr-1 size-4" />
          {saving ? "Saving..." : "Save"}
        </Button>
      </CardContent>
    </Card>
  );
}

function TabListTab() {
  const [config, setConfig] = useState<TabListConfig | null>(null);
  const [header, setHeader] = useState("");
  const [footer, setFooter] = useState("");
  const [playerFormat, setPlayerFormat] = useState("");
  const [interval, setInterval_] = useState(5);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<TabListConfig>("/api/proxy/tablist").then((d) => {
      setConfig(d);
      setHeader(d.header);
      setFooter(d.footer);
      setPlayerFormat(d.playerFormat);
      setInterval_(d.updateInterval);
    }).catch(() => {});
  }, []);

  async function save() {
    setSaving(true);
    try {
      const res = await apiFetch<TabListConfig>("/api/proxy/tablist", {
        method: "PUT",
        body: JSON.stringify({ header, footer, playerFormat, updateInterval: interval }),
      });
      setConfig(res);
      toast.success("Tab list saved");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setSaving(false);
    }
  }

  if (!config) return <Skeleton className="h-48 rounded-xl" />;

  return (
    <Card>
      <CardContent className="pt-6 space-y-4">
        <Field>
          <FieldLabel>Header</FieldLabel>
          <textarea
            value={header}
            onChange={(e) => setHeader(e.target.value)}
            rows={3}
            className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring font-mono"
          />
          <FieldDescription>Placeholders: {"{online}"}, {"{max}"}, {"{version}"}</FieldDescription>
        </Field>
        <Field>
          <FieldLabel>Footer</FieldLabel>
          <textarea
            value={footer}
            onChange={(e) => setFooter(e.target.value)}
            rows={3}
            className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring font-mono"
          />
          <FieldDescription>Placeholders: {"{online}"}, {"{max}"}, {"{server}"}</FieldDescription>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field>
            <FieldLabel>Player Format</FieldLabel>
            <Input value={playerFormat} onChange={(e) => setPlayerFormat(e.target.value)} className="font-mono" />
            <FieldDescription>{"{prefix}{player}{suffix}"}</FieldDescription>
          </Field>
          <Field>
            <FieldLabel>Update Interval</FieldLabel>
            <Input type="number" min={1} value={interval} onChange={(e) => setInterval_(Number(e.target.value))} />
            <FieldDescription>Seconds</FieldDescription>
          </Field>
        </div>
        <Button onClick={save} disabled={saving}>
          <Save className="mr-1 size-4" />
          {saving ? "Saving..." : "Save"}
        </Button>
      </CardContent>
    </Card>
  );
}

function ChatTab() {
  const [config, setConfig] = useState<ChatConfig | null>(null);
  const [format, setFormat] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    apiFetch<ChatConfig>("/api/proxy/chat").then((d) => {
      setConfig(d);
      setFormat(d.format);
      setEnabled(d.enabled);
    }).catch(() => {});
  }, []);

  async function save() {
    setSaving(true);
    try {
      const res = await apiFetch<ChatConfig>("/api/proxy/chat", {
        method: "PUT",
        body: JSON.stringify({ format, enabled }),
      });
      setConfig(res);
      toast.success("Chat config saved");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setSaving(false);
    }
  }

  if (!config) return <Skeleton className="h-32 rounded-xl" />;

  return (
    <Card>
      <CardContent className="pt-6 space-y-4">
        <div className="flex items-center justify-between">
          <FieldLabel>Enable chat formatting</FieldLabel>
          <Switch checked={enabled} onCheckedChange={setEnabled} />
        </div>
        <Field>
          <FieldLabel>Format</FieldLabel>
          <Input value={format} onChange={(e) => setFormat(e.target.value)} className="font-mono" />
          <FieldDescription>Placeholders: {"{prefix}"}, {"{player}"}, {"{suffix}"}, {"{message}"}</FieldDescription>
        </Field>
        <Button onClick={save} disabled={saving}>
          <Save className="mr-1 size-4" />
          {saving ? "Saving..." : "Save"}
        </Button>
      </CardContent>
    </Card>
  );
}

function MaintenanceTab() {
  const [status, setStatus] = useState<MaintenanceStatus | null>(null);
  const [motdLine1, setMotdLine1] = useState("");
  const [motdLine2, setMotdLine2] = useState("");
  const [protocolText, setProtocolText] = useState("");
  const [kickMessage, setKickMessage] = useState("");
  const [whitelistEntry, setWhitelistEntry] = useState("");
  const [saving, setSaving] = useState(false);

  async function load() {
    try {
      const s = await apiFetch<MaintenanceStatus>("/api/maintenance");
      setStatus(s);
      setMotdLine1(s.global.motdLine1);
      setMotdLine2(s.global.motdLine2);
      setProtocolText(s.global.protocolText);
      setKickMessage(s.global.kickMessage);
    } catch {}
  }

  useEffect(() => { load(); }, []);

  async function toggleGlobal() {
    if (!status) return;
    try {
      await apiFetch("/api/maintenance/global", {
        method: "POST",
        body: JSON.stringify({ enabled: !status.global.enabled }),
      });
      toast.success(status.global.enabled ? "Maintenance disabled" : "Maintenance enabled");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    }
  }

  async function saveGlobal() {
    setSaving(true);
    try {
      await apiFetch("/api/maintenance/global", {
        method: "PUT",
        body: JSON.stringify({ motdLine1, motdLine2, protocolText, kickMessage }),
      });
      toast.success("Maintenance config saved");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    } finally {
      setSaving(false);
    }
  }

  async function addWhitelist() {
    if (!whitelistEntry.trim()) return;
    try {
      await apiFetch("/api/maintenance/whitelist", {
        method: "POST",
        body: JSON.stringify({ entry: whitelistEntry.trim() }),
      });
      toast.success(`'${whitelistEntry}' added to whitelist`);
      setWhitelistEntry("");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    }
  }

  async function removeWhitelist(entry: string) {
    try {
      await apiFetch("/api/maintenance/whitelist", {
        method: "DELETE",
        body: JSON.stringify({ entry }),
      });
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed");
    }
  }

  if (!status) return <Skeleton className="h-48 rounded-xl" />;

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="pt-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold">Global Maintenance</h3>
              <p className="text-xs text-muted-foreground">
                Block all players except whitelisted
              </p>
            </div>
            <Button
              variant={status.global.enabled ? "destructive" : "default"}
              onClick={toggleGlobal}
            >
              {status.global.enabled ? "Disable" : "Enable"}
            </Button>
          </div>
          <Field>
            <FieldLabel>MOTD Line 1</FieldLabel>
            <Input value={motdLine1} onChange={(e) => setMotdLine1(e.target.value)} />
          </Field>
          <Field>
            <FieldLabel>MOTD Line 2</FieldLabel>
            <Input value={motdLine2} onChange={(e) => setMotdLine2(e.target.value)} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field>
              <FieldLabel>Protocol Text</FieldLabel>
              <Input value={protocolText} onChange={(e) => setProtocolText(e.target.value)} />
            </Field>
            <Field>
              <FieldLabel>Kick Message</FieldLabel>
              <Input value={kickMessage} onChange={(e) => setKickMessage(e.target.value)} />
            </Field>
          </div>
          <Button onClick={saveGlobal} disabled={saving}>
            <Save className="mr-1 size-4" />
            {saving ? "Saving..." : "Save"}
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="pt-6 space-y-3">
          <h3 className="text-sm font-semibold">Whitelist</h3>
          <div className="flex flex-wrap gap-1.5">
            {status.global.whitelist.map((entry) => (
              <Badge key={entry} variant="secondary" className="gap-1 pr-1">
                {entry}
                <button onClick={() => removeWhitelist(entry)} className="ml-0.5 hover:text-destructive">
                  <X className="size-3" />
                </button>
              </Badge>
            ))}
            {status.global.whitelist.length === 0 && (
              <span className="text-xs text-muted-foreground">No entries</span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Input
              value={whitelistEntry}
              onChange={(e) => setWhitelistEntry(e.target.value)}
              placeholder="Player name or UUID"
              onKeyDown={(e) => e.key === "Enter" && addWhitelist()}
            />
            <Button variant="outline" onClick={addWhitelist} disabled={!whitelistEntry.trim()}>
              <Plus className="size-4" />
            </Button>
          </div>
        </CardContent>
      </Card>

      {Object.keys(status.groups).length > 0 && (
        <Card>
          <CardContent className="pt-6 space-y-3">
            <h3 className="text-sm font-semibold">Group Maintenance</h3>
            {Object.entries(status.groups).map(([name, g]) => (
              <div key={name} className="flex items-center justify-between rounded-md border px-3 py-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">{name}</span>
                  <Badge
                    variant="outline"
                    className={g.enabled ? statusColors.maintenance : statusColors.inactive}
                  >
                    {g.enabled ? "Active" : "Off"}
                  </Badge>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

// ── Main Page ──

export default function SyncProxyPage() {
  return (
    <Tabs defaultValue="motd">
      <TabsList>
        <TabsTrigger value="motd">MOTD</TabsTrigger>
        <TabsTrigger value="tablist">Tab List</TabsTrigger>
        <TabsTrigger value="chat">Chat</TabsTrigger>
        <TabsTrigger value="maintenance">Maintenance</TabsTrigger>
      </TabsList>
      <TabsContent value="motd" className="mt-4">
        <MotdTab />
      </TabsContent>
      <TabsContent value="tablist" className="mt-4">
        <TabListTab />
      </TabsContent>
      <TabsContent value="chat" className="mt-4">
        <ChatTab />
      </TabsContent>
      <TabsContent value="maintenance" className="mt-4">
        <MaintenanceTab />
      </TabsContent>
    </Tabs>
  );
}
