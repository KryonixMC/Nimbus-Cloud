"use client";

import { useEffect, useState } from "react";
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
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
import { Plus, X } from "lucide-react";
import { SectionLabel } from "@/components/section-label";

interface PlayerMeta {
  uuid: string;
  name: string;
  firstSeen: string;
  lastSeen: string;
  totalPlaytimeSeconds: number;
  online: boolean;
  currentService?: string;
}

interface SessionEntry {
  service: string;
  group: string;
  connectedAt: string;
  disconnectedAt: string | null;
}

interface PlayerPerms {
  uuid: string;
  name: string;
  groups: string[];
  prefix: string;
  suffix: string;
  displayGroup: string;
}

interface PermGroupInfo {
  name: string;
}

function formatPlaytime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/** Compact key/value row used in the player sheet. */
function MetaRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right">{children}</span>
    </div>
  );
}

export function PlayerSheet({
  uuid,
  open,
  onOpenChange,
}: {
  uuid: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [meta, setMeta] = useState<PlayerMeta | null>(null);
  const [history, setHistory] = useState<SessionEntry[]>([]);
  const [perms, setPerms] = useState<PlayerPerms | null>(null);
  const [allGroups, setAllGroups] = useState<PermGroupInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [newGroup, setNewGroup] = useState("");

  function reload() {
    if (!uuid) return;
    apiFetch<PlayerPerms>(`/api/permissions/players/${uuid}`)
      .then(setPerms)
      .catch(() => {});
  }

  useEffect(() => {
    if (!uuid || !open) return;
    setLoading(true);
    setMeta(null);
    setHistory([]);
    setPerms(null);
    setNewGroup("");

    Promise.all([
      apiFetch<PlayerMeta>(`/api/players/info/${uuid}`).catch(() => null),
      apiFetch<SessionEntry[]>(
        `/api/players/history/${uuid}?limit=20`
      ).catch(() => []),
      apiFetch<PlayerPerms>(`/api/permissions/players/${uuid}`).catch(
        () => null
      ),
      apiFetch<{ groups: PermGroupInfo[]; total: number }>(
        "/api/permissions/groups"
      )
        .then((d) => d.groups)
        .catch(() => []),
    ]).then(([m, h, p, g]) => {
      setMeta(m);
      setHistory(h);
      setPerms(p);
      setAllGroups(g);
      setLoading(false);
    });
  }, [uuid, open]);

  async function addGroup() {
    if (!uuid || !newGroup.trim()) return;
    try {
      await apiFetch(`/api/permissions/players/${uuid}/groups`, {
        method: "POST",
        body: JSON.stringify({ group: newGroup.trim() }),
      });
      toast.success(`Group '${newGroup}' added`);
      setNewGroup("");
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to add group");
    }
  }

  async function removeGroup(group: string) {
    if (!uuid) return;
    try {
      await apiFetch(`/api/permissions/players/${uuid}/groups`, {
        method: "DELETE",
        body: JSON.stringify({ group }),
      });
      toast.success(`Group '${group}' removed`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to remove group");
    }
  }

  const playerName = meta?.name ?? perms?.name ?? "Unknown";
  const availableGroups = allGroups.filter(
    (g) => !perms?.groups.includes(g.name)
  );

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent size="lg">
        <SheetHeader>
          <div className="flex items-center gap-4">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={uuid ? `https://mc-heads.net/avatar/${uuid}/64` : undefined}
              alt={playerName}
              className="size-14 rounded-md ring-1 ring-border"
            />
            <div className="min-w-0 flex-1">
              <SheetTitle className="text-lg">{playerName}</SheetTitle>
              <SheetDescription className="font-mono text-xs">
                {uuid}
              </SheetDescription>
            </div>
          </div>
        </SheetHeader>

        {loading ? (
          <SheetBody>
            <Skeleton className="h-24 rounded-md" />
            <Skeleton className="h-40 rounded-md" />
          </SheetBody>
        ) : (
          <SheetBody>
            {meta && (
              <section className="space-y-2">
                <SectionLabel>Status</SectionLabel>
                <div className="divide-y">
                  <MetaRow label="Online">
                    <Badge
                      variant="outline"
                      className={
                        meta.online ? statusColors.online : statusColors.inactive
                      }
                    >
                      {meta.online ? "Online" : "Offline"}
                    </Badge>
                  </MetaRow>
                  {meta.currentService && (
                    <MetaRow label="Server">{meta.currentService}</MetaRow>
                  )}
                  <MetaRow label="First seen">
                    {formatDate(meta.firstSeen)}
                  </MetaRow>
                  <MetaRow label="Last seen">{formatDate(meta.lastSeen)}</MetaRow>
                  <MetaRow label="Playtime">
                    {formatPlaytime(meta.totalPlaytimeSeconds)}
                  </MetaRow>
                </div>
              </section>
            )}

            {perms && (
              <section className="space-y-3">
                <SectionLabel>Groups</SectionLabel>
                <div className="divide-y">
                  <MetaRow label="Display group">
                    <Badge variant="outline">{perms.displayGroup}</Badge>
                  </MetaRow>
                  {perms.prefix && <MetaRow label="Prefix">{perms.prefix}</MetaRow>}
                  {perms.suffix && <MetaRow label="Suffix">{perms.suffix}</MetaRow>}
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {perms.groups.map((g) => (
                    <Badge
                      key={g}
                      variant="secondary"
                      className="gap-1 pr-1"
                    >
                      {g}
                      <button
                        onClick={() => removeGroup(g)}
                        className="ml-0.5 hover:text-destructive"
                      >
                        <X className="size-3" />
                      </button>
                    </Badge>
                  ))}
                </div>
                <div className="flex items-center gap-2">
                  {availableGroups.length > 0 ? (
                    <Select
                      value={newGroup}
                      onValueChange={(v) => v && setNewGroup(v)}
                    >
                      <SelectTrigger className="w-full">
                        <SelectValue placeholder="Select group…" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectGroup>
                          {availableGroups.map((g) => (
                            <SelectItem key={g.name} value={g.name}>
                              {g.name}
                            </SelectItem>
                          ))}
                        </SelectGroup>
                      </SelectContent>
                    </Select>
                  ) : (
                    <Input
                      value={newGroup}
                      onChange={(e) => setNewGroup(e.target.value)}
                      placeholder="Group name"
                      onKeyDown={(e) => e.key === "Enter" && addGroup()}
                    />
                  )}
                  <Button
                    variant="outline"
                    onClick={addGroup}
                    disabled={!newGroup.trim()}
                  >
                    <Plus className="size-4" />
                  </Button>
                </div>
              </section>
            )}

            {history.length > 0 && (
              <section className="space-y-2">
                <SectionLabel>Session history</SectionLabel>
                <div className="space-y-1">
                  {history.map((entry, i) => (
                    <div
                      key={i}
                      className="flex items-center justify-between rounded-md border px-3 py-2 text-xs"
                    >
                      <div>
                        <span className="font-medium">{entry.service}</span>
                        <span className="text-muted-foreground ml-2">
                          {entry.group}
                        </span>
                      </div>
                      <div className="text-muted-foreground">
                        {formatDate(entry.connectedAt)}
                        {entry.disconnectedAt && (
                          <span> – {formatDate(entry.disconnectedAt)}</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </SheetBody>
        )}
      </SheetContent>
    </Sheet>
  );
}
