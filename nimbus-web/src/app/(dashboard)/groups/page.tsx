"use client";

import { useState } from "react";
import { useGroups } from "@/lib/hooks";
import * as api from "@/lib/api";
import { GroupInfo } from "@/lib/types";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { toast } from "sonner";
import {
  Plus,
  Play,
  Trash2,
  RefreshCw,
  Server,
  Layers,
} from "lucide-react";

export default function GroupsPage() {
  const { data, loading, refetch } = useGroups();
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({
    name: "",
    type: "DYNAMIC" as "DYNAMIC" | "STATIC",
    software: "PAPER",
    version: "1.21.4",
    template: "",
    memory: "1G",
    maxPlayers: 50,
    minInstances: 1,
    maxInstances: 4,
    playersPerInstance: 40,
  });

  const groups: GroupInfo[] = data?.groups ?? [];

  async function handleCreate() {
    try {
      await api.createGroup({
        name: form.name,
        type: form.type,
        software: form.software,
        version: form.version,
        template: form.template || form.name.toLowerCase(),
        memory: form.memory,
        maxPlayers: form.maxPlayers,
        minInstances: form.minInstances,
        maxInstances: form.maxInstances,
        playersPerInstance: form.playersPerInstance,
        scaleThreshold: 0.8,
        idleTimeout: 0,
        stopOnEmpty: false,
        restartOnCrash: true,
        maxRestarts: 5,
        jvmArgs: [],
        modloaderVersion: "",
        jarName: "",
        readyPattern: "",
      });
      toast.success(`Group "${form.name}" created`);
      setCreateOpen(false);
      setForm({ name: "", type: "DYNAMIC", software: "PAPER", version: "1.21.4", template: "", memory: "1G", maxPlayers: 50, minInstances: 1, maxInstances: 4, playersPerInstance: 40 });
      refetch();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleStartInstance(groupName: string) {
    try {
      await api.startService(groupName);
      toast.success(`Starting new ${groupName} instance`);
      refetch();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleDelete(groupName: string) {
    if (!confirm(`Delete group "${groupName}"? This cannot be undone.`)) return;
    try {
      await api.deleteGroup(groupName);
      toast.success(`Group "${groupName}" deleted`);
      refetch();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Groups</h1>
          <p className="text-muted-foreground">Server group configurations</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={refetch}>
            <RefreshCw className="mr-2 h-4 w-4" />
            Refresh
          </Button>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger render={<Button size="sm" />}>
              <Plus className="mr-2 h-4 w-4" />
              New Group
            </DialogTrigger>
            <DialogContent className="max-w-lg">
              <DialogHeader>
                <DialogTitle>Create Group</DialogTitle>
                <DialogDescription>Add a new server group configuration</DialogDescription>
              </DialogHeader>
              <div className="grid gap-4 py-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>Name</Label>
                    <Input
                      value={form.name}
                      onChange={(e) => setForm({ ...form, name: e.target.value })}
                      placeholder="Lobby"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>Type</Label>
                    <Select value={form.type} onValueChange={(v) => v && setForm({ ...form, type: v as "DYNAMIC" | "STATIC" })}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="DYNAMIC">Dynamic</SelectItem>
                        <SelectItem value="STATIC">Static</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label>Software</Label>
                    <Select value={form.software} onValueChange={(v) => v && setForm({ ...form, software: v })}>
                      <SelectTrigger><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="PAPER">Paper</SelectItem>
                        <SelectItem value="PURPUR">Purpur</SelectItem>
                        <SelectItem value="SPIGOT">Spigot</SelectItem>
                        <SelectItem value="VELOCITY">Velocity</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>Version</Label>
                    <Input
                      value={form.version}
                      onChange={(e) => setForm({ ...form, version: e.target.value })}
                      placeholder="1.21.4"
                    />
                  </div>
                </div>
                <div className="grid grid-cols-3 gap-4">
                  <div className="space-y-2">
                    <Label>Memory</Label>
                    <Input
                      value={form.memory}
                      onChange={(e) => setForm({ ...form, memory: e.target.value })}
                      placeholder="1G"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>Min Instances</Label>
                    <Input
                      type="number"
                      value={form.minInstances}
                      onChange={(e) => setForm({ ...form, minInstances: parseInt(e.target.value) || 0 })}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>Max Instances</Label>
                    <Input
                      type="number"
                      value={form.maxInstances}
                      onChange={(e) => setForm({ ...form, maxInstances: parseInt(e.target.value) || 1 })}
                    />
                  </div>
                </div>
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setCreateOpen(false)}>Cancel</Button>
                <Button onClick={handleCreate} disabled={!form.name}>Create</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </div>

      {loading ? (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {[...Array(3)].map((_, i) => (
            <Card key={i}><CardContent className="h-48 animate-pulse bg-muted rounded" /></Card>
          ))}
        </div>
      ) : groups.length === 0 ? (
        <Card>
          <CardContent className="py-12 text-center">
            <Layers className="mx-auto h-12 w-12 text-muted-foreground mb-4" />
            <p className="text-muted-foreground">No groups configured</p>
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {groups.map((group) => (
            <Card key={group.name}>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="flex items-center gap-2">
                    <Server className="h-4 w-4" />
                    {group.name}
                  </CardTitle>
                  <Badge variant="outline">{group.type}</Badge>
                </div>
                <CardDescription>
                  {group.software} {group.version}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="grid grid-cols-2 gap-2 text-sm">
                  <div>
                    <span className="text-muted-foreground">Instances:</span>{" "}
                    <span className="font-medium">{group.activeInstances}/{group.scaling.maxInstances}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">Memory:</span>{" "}
                    <span className="font-medium">{group.resources.memory}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">Max Players:</span>{" "}
                    <span className="font-medium">{group.resources.maxPlayers}</span>
                  </div>
                  <div>
                    <span className="text-muted-foreground">Template:</span>{" "}
                    <span className="font-medium">{group.template}</span>
                  </div>
                </div>
                <div className="flex gap-2 pt-2">
                  <Button
                    variant="outline"
                    size="sm"
                    className="flex-1"
                    onClick={() => handleStartInstance(group.name)}
                  >
                    <Play className="mr-1 h-3 w-3" />
                    Start Instance
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    onClick={() => handleDelete(group.name)}
                  >
                    <Trash2 className="h-4 w-4 text-destructive" />
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
