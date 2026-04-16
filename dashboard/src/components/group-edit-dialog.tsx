"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import { Loader2 } from "@/lib/icons";

const NAME_REGEX = /^[a-zA-Z0-9_-]{1,64}$/;
const MEMORY_REGEX = /^\d+[MmGg]$/;
const VERSION_REGEX = /^\d+\.\d+(\.\d+)?(-.*)?$/;

const SOFTWARE_OPTIONS = [
  "PAPER",
  "VELOCITY",
  "PURPUR",
  "FOLIA",
  "FORGE",
  "NEOFORGE",
  "FABRIC",
  "PUFFERFISH",
  "LEAF",
  "CUSTOM",
];

interface GroupResources {
  memory: string;
  maxPlayers: number;
}

interface GroupScaling {
  minInstances: number;
  maxInstances: number;
  playersPerInstance: number;
  scaleThreshold: number;
  idleTimeout: number;
}

interface GroupLifecycle {
  stopOnEmpty: boolean;
  restartOnCrash: boolean;
  maxRestarts: number;
}

interface GroupDocker {
  enabled: boolean;
  memoryLimit: string;
  cpuLimit: number;
  javaImage: string;
  network: string;
}

interface GroupDetails {
  name: string;
  type: string;
  software: string;
  version: string;
  template: string;
  resources: GroupResources;
  scaling: GroupScaling;
  lifecycle: GroupLifecycle;
  jvmArgs: string[];
  jvmOptimize: boolean;
  docker?: GroupDocker;
}

interface GroupEditDialogProps {
  groupName: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved?: () => void;
}

export function GroupEditDialog({
  groupName,
  open,
  onOpenChange,
  onSaved,
}: GroupEditDialogProps) {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [details, setDetails] = useState<GroupDetails | null>(null);

  // Form state
  const [type, setType] = useState("DYNAMIC");
  const [software, setSoftware] = useState("PAPER");
  const [version, setVersion] = useState("");
  const [template, setTemplate] = useState("");
  const [memory, setMemory] = useState("1G");
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
  const [jvmArgsRaw, setJvmArgsRaw] = useState("");
  const [dockerEnabled, setDockerEnabled] = useState(false);
  const [dockerMemoryLimit, setDockerMemoryLimit] = useState("");
  const [dockerCpuLimit, setDockerCpuLimit] = useState(0);
  const [dockerJavaImage, setDockerJavaImage] = useState("");

  useEffect(() => {
    if (!open || !groupName) return;
    setLoading(true);
    setDetails(null);
    apiFetch<GroupDetails>(`/api/groups/${groupName}`)
      .then((data) => {
        setDetails(data);
        setType(data.type);
        setSoftware(data.software);
        setVersion(data.version);
        setTemplate(data.template || groupName.toLowerCase());
        setMemory(data.resources.memory);
        setMaxPlayers(data.resources.maxPlayers);
        setMinInstances(data.scaling.minInstances);
        setMaxInstances(data.scaling.maxInstances);
        setPlayersPerInstance(data.scaling.playersPerInstance);
        setScaleThreshold(data.scaling.scaleThreshold);
        setIdleTimeout(data.scaling.idleTimeout);
        setStopOnEmpty(data.lifecycle.stopOnEmpty);
        setRestartOnCrash(data.lifecycle.restartOnCrash);
        setMaxRestarts(data.lifecycle.maxRestarts);
        setJvmOptimize(data.jvmOptimize);
        setJvmArgsRaw(data.jvmArgs.join("\n"));
        setDockerEnabled(data.docker?.enabled ?? false);
        setDockerMemoryLimit(data.docker?.memoryLimit ?? "");
        setDockerCpuLimit(data.docker?.cpuLimit ?? 0);
        setDockerJavaImage(data.docker?.javaImage ?? "");
      })
      .catch(() => {
        // toast already shown by apiFetch
      })
      .finally(() => setLoading(false));
  }, [open, groupName]);

  function validate(): string | null {
    if (!template.trim() || !NAME_REGEX.test(template))
      return "Template name must match [a-zA-Z0-9_-] (1-64 chars)";
    if (!MEMORY_REGEX.test(memory)) return "Memory must look like '512M' or '2G'";
    const memVal = parseInt(memory.slice(0, -1), 10);
    const memMb = memory.slice(-1).toUpperCase() === "G" ? memVal * 1024 : memVal;
    if (memMb < 128) return "Memory must be at least 128M";
    if (!VERSION_REGEX.test(version)) return "Version must look like '1.21.4'";
    if (maxPlayers < 1) return "Max players must be >= 1";
    if (minInstances < 0) return "Min instances must be >= 0";
    if (maxInstances < 1) return "Max instances must be >= 1";
    if (minInstances > maxInstances)
      return "Min instances cannot exceed max instances";
    if (playersPerInstance < 1) return "Players per instance must be >= 1";
    if (scaleThreshold < 0 || scaleThreshold > 1)
      return "Scale threshold must be between 0.0 and 1.0";
    if (idleTimeout < 0) return "Idle timeout must be >= 0";
    if (maxRestarts < 0) return "Max restarts must be >= 0";
    if (dockerMemoryLimit && !/^\d+[KkMmGgTt]?$/.test(dockerMemoryLimit))
      return "Docker memory limit must look like '2G', '512M', or empty (inherit default)";
    if (dockerCpuLimit < 0) return "Docker CPU limit must be >= 0";
    return null;
  }

  async function save() {
    if (!groupName) return;
    const error = validate();
    if (error) {
      toast.error(error);
      return;
    }
    setSaving(true);
    try {
      const jvmArgs = jvmArgsRaw
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter(Boolean);
      await apiFetch(`/api/groups/${groupName}`, {
        method: "PUT",
        body: JSON.stringify({
          name: groupName,
          type,
          template: template.trim(),
          software,
          version,
          memory,
          maxPlayers,
          minInstances,
          maxInstances,
          playersPerInstance,
          scaleThreshold,
          idleTimeout,
          stopOnEmpty,
          restartOnCrash,
          maxRestarts,
          jvmArgs,
          jvmOptimize,
          docker: {
            enabled: dockerEnabled,
            memoryLimit: dockerMemoryLimit,
            cpuLimit: dockerCpuLimit,
            javaImage: dockerJavaImage,
            network: "",
          },
        }),
      });
      toast.success(`Group '${groupName}' updated`);
      onOpenChange(false);
      onSaved?.();
    } catch {
      // apiFetch already toasted
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Edit Group {groupName ? `'${groupName}'` : ""}</DialogTitle>
          <DialogDescription>
            Update the group config. Changes are written to the TOML file and
            reloaded; running services keep their current config until restart.
          </DialogDescription>
        </DialogHeader>

        {loading || !details ? (
          <div className="flex items-center justify-center py-12 text-muted-foreground">
            <Loader2 className="size-5 animate-spin mr-2" /> Loading…
          </div>
        ) : (
          <div className="space-y-4 py-2">
            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel>Type</FieldLabel>
                <Select value={type} onValueChange={(v) => v && setType(v)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value="DYNAMIC">Dynamic</SelectItem>
                      <SelectItem value="STATIC">Static</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>

              <Field>
                <FieldLabel>Template</FieldLabel>
                <Input
                  value={template}
                  onChange={(e) => setTemplate(e.target.value)}
                  placeholder="lobby"
                />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel>Software</FieldLabel>
                <Select
                  value={software}
                  onValueChange={(v) => v && setSoftware(v)}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {SOFTWARE_OPTIONS.map((s) => (
                        <SelectItem key={s} value={s}>
                          {s}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>

              <Field>
                <FieldLabel>Version</FieldLabel>
                <Input
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  placeholder="1.21.4"
                />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel>Memory</FieldLabel>
                <Input
                  value={memory}
                  onChange={(e) => setMemory(e.target.value)}
                  placeholder="2G"
                />
                <FieldDescription>e.g. 512M, 2G (min 128M)</FieldDescription>
              </Field>
              <Field>
                <FieldLabel>Max Players</FieldLabel>
                <Input
                  type="number"
                  min={1}
                  value={maxPlayers}
                  onChange={(e) => setMaxPlayers(Number(e.target.value))}
                />
              </Field>
            </div>

            <div className="rounded-md border p-3 space-y-3">
              <div className="text-sm font-medium">Scaling</div>
              <div className="grid grid-cols-3 gap-3">
                <Field>
                  <FieldLabel>Min</FieldLabel>
                  <Input
                    type="number"
                    min={0}
                    value={minInstances}
                    onChange={(e) => setMinInstances(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Max</FieldLabel>
                  <Input
                    type="number"
                    min={1}
                    value={maxInstances}
                    onChange={(e) => setMaxInstances(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Players / Instance</FieldLabel>
                  <Input
                    type="number"
                    min={1}
                    value={playersPerInstance}
                    onChange={(e) =>
                      setPlayersPerInstance(Number(e.target.value))
                    }
                  />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field>
                  <FieldLabel>Scale Threshold</FieldLabel>
                  <Input
                    type="number"
                    step="0.05"
                    min={0}
                    max={1}
                    value={scaleThreshold}
                    onChange={(e) =>
                      setScaleThreshold(Number(e.target.value))
                    }
                  />
                  <FieldDescription>0.0 – 1.0 (e.g. 0.8 = 80%)</FieldDescription>
                </Field>
                <Field>
                  <FieldLabel>Idle Timeout (s)</FieldLabel>
                  <Input
                    type="number"
                    min={0}
                    value={idleTimeout}
                    onChange={(e) => setIdleTimeout(Number(e.target.value))}
                  />
                  <FieldDescription>0 = disabled</FieldDescription>
                </Field>
              </div>
            </div>

            <div className="rounded-md border p-3 space-y-3">
              <div className="text-sm font-medium">Lifecycle</div>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm">Stop on empty</div>
                  <div className="text-xs text-muted-foreground">
                    Shut down service when last player leaves
                  </div>
                </div>
                <Switch
                  checked={stopOnEmpty}
                  onCheckedChange={setStopOnEmpty}
                />
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm">Restart on crash</div>
                  <div className="text-xs text-muted-foreground">
                    Auto-restart crashed services up to max-restarts
                  </div>
                </div>
                <Switch
                  checked={restartOnCrash}
                  onCheckedChange={setRestartOnCrash}
                />
              </div>
              <Field>
                <FieldLabel>Max Restarts</FieldLabel>
                <Input
                  type="number"
                  min={0}
                  value={maxRestarts}
                  onChange={(e) => setMaxRestarts(Number(e.target.value))}
                />
              </Field>
            </div>

            <div className="rounded-md border p-3 space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium">Docker</div>
                  <div className="text-xs text-muted-foreground">
                    Run services in a container instead of a bare process.
                    Requires the Docker module + a reachable daemon.
                  </div>
                </div>
                <Switch
                  checked={dockerEnabled}
                  onCheckedChange={setDockerEnabled}
                />
              </div>
              {dockerEnabled && (
                <>
                  <div className="grid grid-cols-2 gap-3">
                    <Field>
                      <FieldLabel>Memory Limit</FieldLabel>
                      <Input
                        value={dockerMemoryLimit}
                        onChange={(e) => setDockerMemoryLimit(e.target.value)}
                        placeholder="inherit (e.g. 4G)"
                      />
                      <FieldDescription>
                        Hard container limit. Leave empty to inherit the module default.
                      </FieldDescription>
                    </Field>
                    <Field>
                      <FieldLabel>CPU Limit (cores)</FieldLabel>
                      <Input
                        type="number"
                        step="0.5"
                        min={0}
                        value={dockerCpuLimit}
                        onChange={(e) => setDockerCpuLimit(Number(e.target.value))}
                        placeholder="0 = inherit"
                      />
                      <FieldDescription>
                        0 = inherit the module default.
                      </FieldDescription>
                    </Field>
                  </div>
                  <Field>
                    <FieldLabel>Java Image</FieldLabel>
                    <Input
                      value={dockerJavaImage}
                      onChange={(e) => setDockerJavaImage(e.target.value)}
                      placeholder="eclipse-temurin:21-jre (inherit)"
                    />
                    <FieldDescription>
                      Leave empty to auto-pick based on the server&apos;s required Java version.
                    </FieldDescription>
                  </Field>
                </>
              )}
            </div>

            <div className="rounded-md border p-3 space-y-3">
              <div className="text-sm font-medium">JVM</div>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm">Optimize</div>
                  <div className="text-xs text-muted-foreground">
                    Apply Aikar&apos;s JVM flags
                  </div>
                </div>
                <Switch checked={jvmOptimize} onCheckedChange={setJvmOptimize} />
              </div>
              <Field>
                <FieldLabel>Extra JVM Args (one per line)</FieldLabel>
                <textarea
                  value={jvmArgsRaw}
                  onChange={(e) => setJvmArgsRaw(e.target.value)}
                  placeholder="-Dfile.encoding=UTF-8"
                  rows={4}
                  className="flex w-full rounded-md border bg-transparent px-3 py-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 resize-y font-mono"
                />
              </Field>
            </div>
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={save} disabled={saving || loading}>
            {saving ? (
              <>
                <Loader2 className="size-4 animate-spin mr-1" /> Saving…
              </>
            ) : (
              "Save Changes"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
