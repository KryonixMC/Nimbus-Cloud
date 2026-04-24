"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { PlayerSheet } from "@/components/player-sheet";
import { Field, FieldLabel, FieldDescription } from "@/components/ui/field";
import { Switch } from "@/components/ui/switch";
import {
  Save,
  Plus,
  Trash2,
  X,
  Search,
  Shield,
  Users as UsersIcon,
} from "@/lib/icons";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";
import { SectionLabel } from "@/components/section-label";

interface PermGroup {
  name: string;
  default: boolean;
  prefix: string;
  suffix: string;
  priority: number;
  weight: number;
  permissions: string[];
  parents: string[];
  meta: Record<string, string>;
}

interface PermsPlayer {
  uuid: string;
  name: string;
  groups: string[];
  displayGroup: string;
  prefix: string;
}

export default function PermissionsPage() {
  const [groups, setGroups] = useState<PermGroup[]>([]);
  const [players, setPlayers] = useState<PermsPlayer[]>([]);
  const [playerSearch, setPlayerSearch] = useState("");
  const [loading, setLoading] = useState(true);

  // Edit state
  const [editGroup, setEditGroup] = useState<PermGroup | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [editPrefix, setEditPrefix] = useState("");
  const [editSuffix, setEditSuffix] = useState("");
  const [editWeight, setEditWeight] = useState(0);
  const [editPriority, setEditPriority] = useState(0);
  const [editDefault, setEditDefault] = useState(false);
  const [editPermissions, setEditPermissions] = useState<string[]>([]);
  const [editParents, setEditParents] = useState<string[]>([]);
  const [newPerm, setNewPerm] = useState("");
  const [newParent, setNewParent] = useState("");
  const [saving, setSaving] = useState(false);

  // Create group
  const [createOpen, setCreateOpen] = useState(false);
  const [newGroupName, setNewGroupName] = useState("");
  const [creating, setCreating] = useState(false);

  // Player sheet
  const [selectedPlayer, setSelectedPlayer] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);

  async function load() {
    try {
      const g = await apiFetch<{ groups: PermGroup[]; total: number }>(
        "/api/permissions/groups"
      )
        .then((d) => d.groups)
        .catch(() => []);
      setGroups(g);
    } finally {
      setLoading(false);
    }
  }

  async function loadPlayers(query: string) {
    const params = query ? `?q=${encodeURIComponent(query)}` : "";
    const p = await apiFetch<PermsPlayer[]>(
      `/api/permissions/players${params}`
    ).catch(() => []);
    setPlayers(p);
  }

  useEffect(() => {
    load();
    loadPlayers("");
  }, []);

  useEffect(() => {
    const timeout = setTimeout(() => loadPlayers(playerSearch), 300);
    return () => clearTimeout(timeout);
  }, [playerSearch]);

  function openEdit(group: PermGroup) {
    setEditGroup(group);
    setEditPrefix(group.prefix);
    setEditSuffix(group.suffix);
    setEditWeight(group.weight);
    setEditPriority(group.priority);
    setEditDefault(group.default);
    setEditPermissions([...group.permissions]);
    setEditParents([...group.parents]);
    setEditOpen(true);
  }

  async function saveGroup() {
    if (!editGroup) return;
    setSaving(true);
    try {
      await apiFetch(`/api/permissions/groups/${editGroup.name}`, {
        method: "PUT",
        body: JSON.stringify({
          prefix: editPrefix,
          suffix: editSuffix,
          weight: editWeight,
          priority: editPriority,
          default: editDefault,
          permissions: editPermissions,
          parents: editParents,
        }),
      });
      toast.success(`Group '${editGroup.name}' updated`);
      setEditOpen(false);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  }

  async function createGroup() {
    if (!newGroupName.trim()) return;
    setCreating(true);
    try {
      await apiFetch("/api/permissions/groups", {
        method: "POST",
        body: JSON.stringify({ name: newGroupName.trim(), default: false }),
      });
      toast.success(`Group '${newGroupName}' created`);
      setNewGroupName("");
      setCreateOpen(false);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to create");
    } finally {
      setCreating(false);
    }
  }

  async function deleteGroup(name: string) {
    if (!confirm(`Delete permission group '${name}'?`)) return;
    try {
      await apiFetch(`/api/permissions/groups/${name}`, { method: "DELETE" });
      toast.success(`Group '${name}' deleted`);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to delete");
    }
  }

  function addPerm() {
    if (!newPerm.trim()) return;
    setEditPermissions((prev) => [...prev, newPerm.trim()]);
    setNewPerm("");
  }

  function removePerm(perm: string) {
    setEditPermissions((prev) => prev.filter((p) => p !== perm));
  }

  function addParent() {
    if (!newParent.trim()) return;
    setEditParents((prev) => [...prev, newParent.trim()]);
    setNewParent("");
  }

  function removeParent(parent: string) {
    setEditParents((prev) => prev.filter((p) => p !== parent));
  }

  return (
    <>
      <PageHeader
        title="Permissions"
        description="Manage LuckPerms-style groups, inheritance, prefixes and per-player overrides."
        actions={
          <Dialog open={createOpen} onOpenChange={setCreateOpen}>
            <DialogTrigger
              render={
                <Button>
                  <Plus className="mr-1 size-4" /> New group
                </Button>
              }
            />
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Create permission group</DialogTitle>
                <DialogDescription>
                  Give the group a unique name. You can edit prefix, weight
                  and permissions right after creating it.
                </DialogDescription>
              </DialogHeader>
              <Field>
                <FieldLabel>Group name</FieldLabel>
                <Input
                  value={newGroupName}
                  onChange={(e) => setNewGroupName(e.target.value)}
                  placeholder="e.g. admin"
                  autoFocus
                  onKeyDown={(e) => e.key === "Enter" && createGroup()}
                />
              </Field>
              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setCreateOpen(false)}
                  disabled={creating}
                >
                  Cancel
                </Button>
                <Button
                  onClick={createGroup}
                  disabled={creating || !newGroupName.trim()}
                >
                  {creating ? "Creating…" : "Create group"}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        }
      />

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : (
        <Tabs defaultValue="groups">
          <TabsList>
            <TabsTrigger value="groups">Groups ({groups.length})</TabsTrigger>
            <TabsTrigger value="players">
              Players ({players.length})
            </TabsTrigger>
          </TabsList>

          <TabsContent value="groups" className="mt-4 space-y-4">
            {groups.length === 0 ? (
              <EmptyState
                icon={Shield}
                title="No permission groups"
                description="Create your first group to start assigning permissions."
              />
            ) : (
              <Card>
                <CardContent className="p-0">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="pl-6">Name</TableHead>
                        <TableHead>Prefix</TableHead>
                        <TableHead>Suffix</TableHead>
                        <TableHead className="text-right">Weight</TableHead>
                        <TableHead className="text-right">
                          Permissions
                        </TableHead>
                        <TableHead>Parents</TableHead>
                        <TableHead>Default</TableHead>
                        <TableHead className="w-12" />
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {groups.map((g) => (
                        <TableRow
                          key={g.name}
                          className="cursor-pointer"
                          onClick={() => openEdit(g)}
                        >
                          <TableCell className="pl-6 font-medium">
                            {g.name}
                          </TableCell>
                          <TableCell className="text-xs">
                            {g.prefix || "—"}
                          </TableCell>
                          <TableCell className="text-xs">
                            {g.suffix || "—"}
                          </TableCell>
                          <TableCell className="text-right">
                            {g.weight}
                          </TableCell>
                          <TableCell className="text-right">
                            {g.permissions.length}
                          </TableCell>
                          <TableCell>
                            {g.parents.length > 0 ? (
                              <div className="flex gap-1">
                                {g.parents.map((p) => (
                                  <Badge
                                    key={p}
                                    variant="secondary"
                                    className="text-xs"
                                  >
                                    {p}
                                  </Badge>
                                ))}
                              </div>
                            ) : (
                              "—"
                            )}
                          </TableCell>
                          <TableCell>
                            {g.default && (
                              <Badge variant="outline" className="text-xs">
                                Default
                              </Badge>
                            )}
                          </TableCell>
                          <TableCell>
                            <Button
                              variant="ghost"
                              size="icon"
                              className="size-7 text-destructive"
                              onClick={(e) => {
                                e.stopPropagation();
                                deleteGroup(g.name);
                              }}
                            >
                              <Trash2 className="size-3.5" />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </CardContent>
              </Card>
            )}
          </TabsContent>

          <TabsContent value="players" className="mt-4 space-y-4">
            <div className="relative w-64">
              <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input
                value={playerSearch}
                onChange={(e) => setPlayerSearch(e.target.value)}
                placeholder="Search players…"
                className="pl-9"
              />
            </div>

            {players.length === 0 ? (
              <EmptyState
                icon={UsersIcon}
                title={playerSearch ? "No players found" : "No players known"}
                description={
                  playerSearch
                    ? `No players match "${playerSearch}".`
                    : "Players will appear here once they join and get a permission group."
                }
              />
            ) : (
              <Card>
                <CardContent className="p-0">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="pl-6 w-14" />
                        <TableHead>Name</TableHead>
                        <TableHead>Group</TableHead>
                        <TableHead>Prefix</TableHead>
                        <TableHead>All groups</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {players.map((p) => (
                        <TableRow
                          key={p.uuid}
                          className="cursor-pointer"
                          onClick={() => {
                            setSelectedPlayer(p.uuid);
                            setSheetOpen(true);
                          }}
                        >
                          <TableCell className="pl-6">
                            <img
                              src={`https://mc-heads.net/avatar/${p.uuid}/32`}
                              alt={p.name}
                              className="size-8 min-w-8 rounded-sm"
                            />
                          </TableCell>
                          <TableCell className="font-medium">
                            {p.name}
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline">{p.displayGroup}</Badge>
                          </TableCell>
                          <TableCell className="text-xs">
                            {p.prefix || "—"}
                          </TableCell>
                          <TableCell>
                            <div className="flex gap-1">
                              {p.groups.map((g) => (
                                <Badge
                                  key={g}
                                  variant="secondary"
                                  className="text-xs"
                                >
                                  {g}
                                </Badge>
                              ))}
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </CardContent>
              </Card>
            )}
          </TabsContent>
        </Tabs>
      )}

      {/* Edit Group Sheet */}
      <Sheet open={editOpen} onOpenChange={setEditOpen}>
        <SheetContent size="lg">
          <SheetHeader>
            <SheetTitle>Edit group · {editGroup?.name}</SheetTitle>
            <SheetDescription>
              Set display formatting, inheritance and the permission list.
            </SheetDescription>
          </SheetHeader>

          <SheetBody>
            <section className="space-y-3">
              <SectionLabel>Display</SectionLabel>
              <Field>
                <FieldLabel>Prefix</FieldLabel>
                <Input
                  value={editPrefix}
                  onChange={(e) => setEditPrefix(e.target.value)}
                  placeholder="e.g. &c[Admin] "
                />
                <FieldDescription>
                  Color codes supported (&amp;c, &amp;a, …)
                </FieldDescription>
              </Field>
              <Field>
                <FieldLabel>Suffix</FieldLabel>
                <Input
                  value={editSuffix}
                  onChange={(e) => setEditSuffix(e.target.value)}
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field>
                  <FieldLabel>Weight</FieldLabel>
                  <Input
                    type="number"
                    value={editWeight}
                    onChange={(e) => setEditWeight(Number(e.target.value))}
                  />
                </Field>
                <Field>
                  <FieldLabel>Priority</FieldLabel>
                  <Input
                    type="number"
                    value={editPriority}
                    onChange={(e) => setEditPriority(Number(e.target.value))}
                  />
                </Field>
              </div>
              <div className="flex items-center justify-between gap-4">
                <FieldLabel>Default group (assigned to new players)</FieldLabel>
                <Switch
                  checked={editDefault}
                  onCheckedChange={setEditDefault}
                />
              </div>
            </section>

            <section className="space-y-3">
              <SectionLabel>Parents (inheritance)</SectionLabel>
              <div className="flex flex-wrap gap-1">
                {editParents.map((p) => (
                  <Badge key={p} variant="secondary" className="gap-1 pr-1">
                    {p}
                    <button
                      onClick={() => removeParent(p)}
                      className="ml-0.5 hover:text-destructive"
                    >
                      <X className="size-3" />
                    </button>
                  </Badge>
                ))}
                {editParents.length === 0 && (
                  <span className="text-xs text-muted-foreground">None</span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <Input
                  value={newParent}
                  onChange={(e) => setNewParent(e.target.value)}
                  placeholder="Parent group name"
                  onKeyDown={(e) => e.key === "Enter" && addParent()}
                />
                <Button variant="outline" onClick={addParent}>
                  Add
                </Button>
              </div>
            </section>

            <section className="space-y-3">
              <SectionLabel right={<span>{editPermissions.length}</span>}>
                Permissions
              </SectionLabel>
              <div className="max-h-56 overflow-y-auto scrollbar-thin rounded-md border">
                {editPermissions.map((perm) => (
                  <div
                    key={perm}
                    className="flex items-center justify-between border-b px-3 py-1.5 text-xs last:border-b-0"
                  >
                    <code
                      className={
                        perm.startsWith("-") ? "text-destructive" : ""
                      }
                    >
                      {perm}
                    </code>
                    <button
                      onClick={() => removePerm(perm)}
                      className="text-muted-foreground hover:text-destructive"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
                ))}
                {editPermissions.length === 0 && (
                  <div className="px-3 py-2 text-xs text-muted-foreground">
                    No permissions
                  </div>
                )}
              </div>
              <div className="flex items-center gap-2">
                <Input
                  value={newPerm}
                  onChange={(e) => setNewPerm(e.target.value)}
                  placeholder="e.g. nimbus.cloud.audit"
                  className="font-mono"
                  onKeyDown={(e) => e.key === "Enter" && addPerm()}
                />
                <Button variant="outline" onClick={addPerm}>
                  Add
                </Button>
              </div>
            </section>
          </SheetBody>

          <SheetFooter>
            <Button
              variant="outline"
              onClick={() => setEditOpen(false)}
              disabled={saving}
            >
              Cancel
            </Button>
            <Button onClick={saveGroup} disabled={saving}>
              <Save className="mr-1 size-4" />
              {saving ? "Saving…" : "Save changes"}
            </Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>

      <PlayerSheet
        uuid={selectedPlayer}
        open={sheetOpen}
        onOpenChange={setSheetOpen}
      />
    </>
  );
}
