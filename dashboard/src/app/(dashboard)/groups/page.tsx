"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  SelectGroup,
  SelectLabel,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { Plus, Trash2, Loader2, Package, FolderTreeIcon } from "lucide-react";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";

interface ModpackInfo {
  name: string;
  version: string;
  mcVersion: string;
  modloader: string;
  modloaderVersion: string;
  totalFiles: number;
  serverFiles: number;
}

interface Group {
  name: string;
  type: string;
  software: string;
  version: string;
  resources: { memory: string; maxPlayers: number };
  scaling: { minInstances: number; maxInstances: number };
  activeInstances: number;
}

interface GroupListResponse {
  groups: Group[];
  total: number;
}

interface SoftwareInfo {
  name: string;
  needsModloaderVersion: boolean;
  needsCustomJar: boolean;
  isProxy: boolean;
}

interface VersionListResponse {
  software: string;
  stable: string[];
  snapshots: string[];
  latest: string | null;
}

export default function GroupsPage() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);

  // Create form state
  const [softwareList, setSoftwareList] = useState<SoftwareInfo[]>([]);
  const [newName, setNewName] = useState("");
  const [newType, setNewType] = useState("DYNAMIC");
  const [newSoftware, setNewSoftware] = useState("PAPER");
  const [newVersion, setNewVersion] = useState("");
  const [newModloaderVersion, setNewModloaderVersion] = useState("");
  const [newMemory, setNewMemory] = useState("1G");
  const [newMinInstances, setNewMinInstances] = useState(1);
  const [newMaxInstances, setNewMaxInstances] = useState(4);
  const [versions, setVersions] = useState<VersionListResponse | null>(null);
  const [modloaderVersions, setModloaderVersions] = useState<VersionListResponse | null>(null);
  const [loadingVersions, setLoadingVersions] = useState(false);
  const [loadingModloader, setLoadingModloader] = useState(false);
  const [creating, setCreating] = useState(false);

  const selectedSoftware = softwareList.find((s) => s.name === newSoftware);

  async function load() {
    try {
      const data = await apiFetch<GroupListResponse>("/api/groups");
      setGroups(data.groups);
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  // Fetch software list when dialog opens
  useEffect(() => {
    if (!createOpen) return;
    apiFetch<{ software: SoftwareInfo[] }>("/api/software")
      .then((data) => setSoftwareList(data.software))
      .catch(() => {});
  }, [createOpen]);

  // Fetch versions when software changes
  useEffect(() => {
    if (!createOpen || newSoftware === "CUSTOM") {
      setVersions(null);
      return;
    }
    setLoadingVersions(true);
    setNewVersion("");
    apiFetch<VersionListResponse>(`/api/software/${newSoftware}/versions`)
      .then((data) => {
        setVersions(data);
        if (data.latest) setNewVersion(data.latest);
      })
      .catch(() => {})
      .finally(() => setLoadingVersions(false));
  }, [newSoftware, createOpen]);

  // Fetch modloader versions when MC version changes (for Forge/NeoForge/Fabric)
  useEffect(() => {
    if (!selectedSoftware?.needsModloaderVersion || !newVersion) {
      setModloaderVersions(null);
      return;
    }
    setLoadingModloader(true);
    setNewModloaderVersion("");
    apiFetch<VersionListResponse>(
      `/api/software/${newSoftware}/modloader-versions?mcVersion=${newVersion}`
    )
      .then((data) => {
        setModloaderVersions(data);
        if (data.latest) setNewModloaderVersion(data.latest);
      })
      .catch(() => {})
      .finally(() => setLoadingModloader(false));
  }, [newSoftware, newVersion, selectedSoftware?.needsModloaderVersion]);

  async function createGroup() {
    if (!newName.trim()) return;
    setCreating(true);
    try {
      await apiFetch("/api/groups", {
        method: "POST",
        body: JSON.stringify({
          name: newName.trim(),
          type: newType,
          software: newSoftware,
          version: newVersion,
          modloaderVersion: newModloaderVersion,
          memory: newMemory,
          minInstances: newMinInstances,
          maxInstances: newMaxInstances,
        }),
      });
      toast.success(`Group '${newName}' created`);
      setCreateOpen(false);
      setNewName("");
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to create group");
    } finally {
      setCreating(false);
    }
  }

  // Modpack import state
  const [importOpen, setImportOpen] = useState(false);
  const [importSource, setImportSource] = useState("");
  const [importGroupName, setImportGroupName] = useState("");
  const [importMemory, setImportMemory] = useState("2G");
  const [importInfo, setImportInfo] = useState<ModpackInfo | null>(null);
  const [resolving, setResolving] = useState(false);
  const [importing, setImporting] = useState(false);

  async function resolveModpack() {
    if (!importSource.trim()) return;
    setResolving(true);
    setImportInfo(null);
    try {
      const info = await apiFetch<ModpackInfo>("/api/modpacks/resolve", {
        method: "POST",
        body: JSON.stringify({ source: importSource.trim() }),
      });
      setImportInfo(info);
      if (!importGroupName) setImportGroupName(info.name.replace(/[^a-zA-Z0-9_-]/g, ""));
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not resolve modpack");
    } finally {
      setResolving(false);
    }
  }

  async function importModpack() {
    if (!importGroupName.trim() || !importSource.trim()) return;
    setImporting(true);
    try {
      await apiFetch("/api/modpacks/import", {
        method: "POST",
        body: JSON.stringify({
          source: importSource.trim(),
          groupName: importGroupName.trim(),
          memory: importMemory,
        }),
      });
      toast.success(`Modpack imported as '${importGroupName}'`);
      setImportOpen(false);
      setImportSource("");
      setImportGroupName("");
      setImportInfo(null);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Import failed");
    } finally {
      setImporting(false);
    }
  }

  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  async function confirmDelete() {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await apiFetch(`/api/groups/${deleteTarget}`, { method: "DELETE" });
      toast.success(`Group '${deleteTarget}' deleted`);
      setDeleteTarget(null);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to delete group");
    } finally {
      setDeleting(false);
    }
  }

  if (loading) return <Skeleton className="h-96 rounded-xl" />;

  return (
    <>
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Groups ({groups.length})</CardTitle>
        <div className="flex items-center gap-2">
          <Dialog open={importOpen} onOpenChange={setImportOpen}>
            <DialogTrigger
              render={
                <Button variant="outline">
                  <Package className="mr-1 size-4" /> Import Modpack
                </Button>
              }
            />
            <DialogContent className="max-w-lg">
              <DialogHeader>
                <DialogTitle>Import Modpack</DialogTitle>
                <DialogDescription>
                  Import a Modrinth modpack (.mrpack) as a new group.
                </DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-2">
                <Field>
                  <FieldLabel>Modpack Source</FieldLabel>
                  <div className="flex items-center gap-2">
                    <Input
                      value={importSource}
                      onChange={(e) => setImportSource(e.target.value)}
                      placeholder="Modrinth slug, URL, or .mrpack path"
                      onKeyDown={(e) => e.key === "Enter" && resolveModpack()}
                    />
                    <Button variant="outline" onClick={resolveModpack} disabled={resolving || !importSource.trim()}>
                      {resolving ? <Loader2 className="size-4 animate-spin" /> : "Resolve"}
                    </Button>
                  </div>
                  <FieldDescription>e.g. &quot;adrenaserver&quot; or a Modrinth URL</FieldDescription>
                </Field>

                {importInfo && (
                  <div className="rounded-md border p-3 space-y-1 text-sm">
                    <div className="font-medium">{importInfo.name} v{importInfo.version}</div>
                    <div className="text-muted-foreground">
                      {importInfo.modloader} {importInfo.modloaderVersion} / MC {importInfo.mcVersion}
                    </div>
                    <div className="text-muted-foreground">
                      {importInfo.serverFiles} server mods ({importInfo.totalFiles} total)
                    </div>
                  </div>
                )}

                <Field>
                  <FieldLabel>Group Name</FieldLabel>
                  <Input
                    value={importGroupName}
                    onChange={(e) => setImportGroupName(e.target.value)}
                    placeholder="e.g. MyModpack"
                  />
                </Field>
                <Field>
                  <FieldLabel>Memory</FieldLabel>
                  <Input
                    value={importMemory}
                    onChange={(e) => setImportMemory(e.target.value)}
                    placeholder="2G"
                  />
                </Field>
              </div>
              <DialogFooter>
                <Button onClick={importModpack} disabled={importing || !importGroupName.trim() || !importSource.trim()}>
                  {importing ? "Importing..." : "Import"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger
              render={
                <Button>
                  <Plus className="mr-1 size-4" /> New Group
                </Button>
              }
            />
          <DialogContent className="max-w-lg max-h-[85vh] overflow-y-auto">
            <DialogHeader>
              <DialogTitle>Create Group</DialogTitle>
              <DialogDescription>
                Configure a new server group.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-2">
              <Field>
                <FieldLabel>Group Name</FieldLabel>
                <Input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="e.g. BedWars"
                />
              </Field>

              <Field>
                <FieldLabel>Type</FieldLabel>
                <Select value={newType} onValueChange={(v) => v && setNewType(v)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      <SelectItem value="DYNAMIC">Dynamic (fresh from template)</SelectItem>
                      <SelectItem value="STATIC">Static (persistent world)</SelectItem>
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>

              <Field>
                <FieldLabel>Server Software</FieldLabel>
                <Select value={newSoftware} onValueChange={(v) => v && setNewSoftware(v)}>
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {softwareList.length > 0
                        ? softwareList.map((sw) => (
                            <SelectItem key={sw.name} value={sw.name}>
                              {sw.name}{sw.isProxy ? " (Proxy)" : ""}
                            </SelectItem>
                          ))
                        : ["PAPER","VELOCITY","PURPUR","FOLIA","FORGE","NEOFORGE","FABRIC","PUFFERFISH","LEAF","CUSTOM"].map((sw) => (
                            <SelectItem key={sw} value={sw}>{sw}</SelectItem>
                          ))
                      }
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>

              <Field>
                <FieldLabel>
                  Version{" "}
                  {loadingVersions && (
                    <Loader2 className="inline size-3 animate-spin ml-1" />
                  )}
                </FieldLabel>
                {versions && versions.stable.length > 0 ? (
                  <Select value={newVersion} onValueChange={(v) => v && setNewVersion(v)}>
                    <SelectTrigger className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectLabel>Stable</SelectLabel>
                        {versions.stable.map((v) => (
                          <SelectItem key={v} value={v}>
                            {v}{v === versions.latest ? " (latest)" : ""}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                      {versions.snapshots.length > 0 && (
                        <SelectGroup>
                          <SelectLabel>Snapshots</SelectLabel>
                          {versions.snapshots.map((v) => (
                            <SelectItem key={v} value={v}>{v}</SelectItem>
                          ))}
                        </SelectGroup>
                      )}
                    </SelectContent>
                  </Select>
                ) : (
                  <Input
                    value={newVersion}
                    onChange={(e) => setNewVersion(e.target.value)}
                    placeholder="e.g. 1.21.4"
                  />
                )}
              </Field>

              {selectedSoftware?.needsModloaderVersion && (
                <Field>
                  <FieldLabel>
                    Modloader Version{" "}
                    {loadingModloader && (
                      <Loader2 className="inline size-3 animate-spin ml-1" />
                    )}
                  </FieldLabel>
                  {modloaderVersions && modloaderVersions.stable.length > 0 ? (
                    <Select value={newModloaderVersion} onValueChange={(v) => v && setNewModloaderVersion(v)}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {modloaderVersions.stable.map((v) => (
                            <SelectItem key={v} value={v}>
                              {v}{v === modloaderVersions.latest ? " (latest)" : ""}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  ) : (
                    <Input
                      value={newModloaderVersion}
                      onChange={(e) => setNewModloaderVersion(e.target.value)}
                      placeholder="Modloader version"
                    />
                  )}
                  <FieldDescription>
                    Leave empty for latest version
                  </FieldDescription>
                </Field>
              )}

              <div className="grid grid-cols-3 gap-3">
                <Field>
                  <FieldLabel>Memory</FieldLabel>
                  <Input
                    value={newMemory}
                    onChange={(e) => setNewMemory(e.target.value)}
                    placeholder="1G"
                  />
                </Field>
                <Field>
                  <FieldLabel>Min Instances</FieldLabel>
                  <Input
                    type="number"
                    min={0}
                    value={newMinInstances}
                    onChange={(e) => setNewMinInstances(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Max Instances</FieldLabel>
                  <Input
                    type="number"
                    min={1}
                    value={newMaxInstances}
                    onChange={(e) => setNewMaxInstances(Number(e.target.value))}
                  />
                </Field>
              </div>
            </div>
            <DialogFooter>
              <Button
                onClick={createGroup}
                disabled={creating || !newName.trim() || !newVersion}
              >
                {creating ? "Creating..." : "Create Group"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
        </div>
      </CardHeader>
      <CardContent>
        {groups.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <FolderTreeIcon className="size-10 text-muted-foreground/50 mb-3" />
            <p className="text-sm font-medium text-muted-foreground">No groups configured</p>
            <p className="text-xs text-muted-foreground/70 mt-1">Create a group to get started</p>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Software</TableHead>
                <TableHead>Version</TableHead>
                <TableHead>Memory</TableHead>
                <TableHead className="text-right">Instances</TableHead>
                <TableHead className="text-right">Max Players</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {groups.map((g) => (
                <TableRow key={g.name}>
                  <TableCell>
                    <Link
                      href={`/groups/${g.name}`}
                      className="font-medium hover:underline"
                    >
                      {g.name}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline">{g.type}</Badge>
                  </TableCell>
                  <TableCell>{g.software}</TableCell>
                  <TableCell>{g.version}</TableCell>
                  <TableCell>{g.resources.memory}</TableCell>
                  <TableCell className="text-right">
                    {g.activeInstances} / {g.scaling.minInstances}-
                    {g.scaling.maxInstances}
                  </TableCell>
                  <TableCell className="text-right">
                    {g.resources.maxPlayers}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="size-8 text-destructive"
                      onClick={() => setDeleteTarget(g.name)}
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>

    <Dialog open={!!deleteTarget} onOpenChange={(open) => !open && setDeleteTarget(null)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete Group</DialogTitle>
          <DialogDescription>
            Are you sure you want to delete &apos;{deleteTarget}&apos;? All running services must be stopped first. This action cannot be undone.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => setDeleteTarget(null)}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={confirmDelete} disabled={deleting}>
            {deleting ? "Deleting..." : "Delete"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
    </>
  );
}
