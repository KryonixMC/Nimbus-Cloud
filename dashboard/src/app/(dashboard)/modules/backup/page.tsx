"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { apiFetch, getApiUrl, getToken } from "@/lib/api";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";
import { EmptyState } from "@/components/empty-state";
import {
  Archive,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  CircleDashed,
  CircleX,
  Download,
  HardDrive,
  History,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Save,
  Trash2,
} from "@/lib/icons";

// ── Shared types (mirror backend DTOs in modules/backup) ────────

interface BackupRecord {
  id: number;
  targetType: string;
  targetName: string;
  scheduleClass: string;
  scheduleName: string;
  startedAt: string;
  completedAt: string | null;
  status: string; // RUNNING|SUCCESS|FAILED|PARTIAL
  sizeBytes: number;
  archivePath: string;
  checksum: string;
  errorMessage: string | null;
  nodeId: string;
  triggeredBy: string;
}

interface BackupListResponse {
  backups: BackupRecord[];
  total: number;
}

interface Schedule {
  name: string;
  cron: string;
  retentionClass: string;
  targets: string[];
  lastRunAt: string | null;
  nextRunAt: string | null;
  lastStatus: string | null;
}

interface VerifyResponse {
  valid: boolean;
  errors: string[];
}

// ── Config shape mirrors modules/backup BackupModuleConfig ──────

interface ScopeConfig {
  services: boolean;
  dedicated: boolean;
  templates: boolean;
  controllerConfig: boolean;
  stateSync: boolean;
  database: boolean;
}
interface RetentionConfig {
  hourlyKeep: number;
  dailyKeep: number;
  weeklyKeep: number;
  monthlyKeep: number;
  keepManual: boolean;
}
interface ScheduleDef {
  name: string;
  cron: string;
  retentionClass: string; // hourly|daily|weekly|monthly|manual
  targets: string[];
}
interface BackupModuleConfig {
  enabled: boolean;
  localDestination: string;
  maxConcurrent: number;
  compressionLevel: number;
  compressionWorkers: number;
  quiesceServices: boolean;
  quiesceWaitSeconds: number;
  scope: ScopeConfig;
  excludes: string[];
  schedules: ScheduleDef[];
  retention: RetentionConfig;
}

const SCOPE_KEYS: { key: keyof ScopeConfig; label: string; hint: string }[] = [
  { key: "services", label: "Services", hint: "Running group services' working dirs" },
  { key: "dedicated", label: "Dedicated", hint: "Dedicated service dirs" },
  { key: "templates", label: "Templates", hint: "Template library" },
  { key: "controllerConfig", label: "Controller config", hint: "config/ directory" },
  { key: "stateSync", label: "State sync", hint: "services/state/ canonical store" },
  { key: "database", label: "Database", hint: "SQLite VACUUM / mysqldump / pg_dump" },
];
const RETENTION_CLASSES = ["hourly", "daily", "weekly", "monthly", "manual"] as const;
const SCHEDULE_TARGET_OPTIONS = [
  "all",
  "services",
  "dedicated",
  "templates",
  "config",
  "database",
  "state_sync",
];

interface PruneResponse {
  deleted: number;
  freedBytes: number;
  errors: string[];
}

// ── Helpers ─────────────────────────────────────────────────────

function formatSize(bytes: number): string {
  if (!bytes) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    return d.toLocaleString();
  } catch {
    return iso;
  }
}

function formatDuration(startIso: string, endIso: string | null): string {
  if (!endIso) return "…";
  try {
    const ms = new Date(endIso).getTime() - new Date(startIso).getTime();
    if (ms < 1000) return `${ms} ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
    return `${Math.floor(ms / 60_000)}m ${Math.floor((ms % 60_000) / 1000)}s`;
  } catch {
    return "—";
  }
}

function statusBadge(status: string) {
  switch (status) {
    case "SUCCESS":
      return (
        <Badge
          variant="outline"
          className="gap-1 border-green-500/40 text-green-600 dark:text-green-400"
        >
          <CheckCircle2 className="size-3" />
          {status}
        </Badge>
      );
    case "FAILED":
      return (
        <Badge
          variant="outline"
          className="gap-1 border-red-500/40 text-red-600 dark:text-red-400"
        >
          <CircleX className="size-3" />
          {status}
        </Badge>
      );
    case "PARTIAL":
      return (
        <Badge
          variant="outline"
          className="gap-1 border-amber-500/40 text-amber-600 dark:text-amber-400"
        >
          <CircleAlert className="size-3" />
          {status}
        </Badge>
      );
    case "RUNNING":
      return (
        <Badge
          variant="outline"
          className="gap-1 border-blue-500/40 text-blue-600 dark:text-blue-400"
        >
          <CircleDashed className="size-3 animate-spin" />
          {status}
        </Badge>
      );
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

// ── Trigger dialog state ────────────────────────────────────────
const TARGET_TYPES = [
  { key: "services", label: "Services" },
  { key: "dedicated", label: "Dedicated" },
  { key: "templates", label: "Templates" },
  { key: "config", label: "Config" },
  { key: "database", label: "Database" },
  { key: "state_sync", label: "State sync" },
] as const;

type TriggerForm = {
  targets: Set<string>;
  targetName: string;
};

const EMPTY_TRIGGER_FORM: TriggerForm = {
  targets: new Set(),
  targetName: "",
};

export default function BackupModulePage() {
  const [backups, setBackups] = useState<BackupRecord[]>([]);
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [activeJobs, setActiveJobs] = useState(0);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Trigger dialog
  const [triggerOpen, setTriggerOpen] = useState(false);
  const [triggerForm, setTriggerForm] = useState<TriggerForm>(EMPTY_TRIGGER_FORM);
  const [triggerSubmitting, setTriggerSubmitting] = useState(false);

  // Active tab — controllable so empty-state CTAs can jump between tabs
  const [activeTab, setActiveTab] = useState<string>("overview");

  const load = useCallback(async () => {
    try {
      const [listRes, statusRes] = await Promise.all([
        apiFetch<BackupListResponse>("/api/backups?limit=100").catch(() => ({
          backups: [],
          total: 0,
        })),
        apiFetch<{
          activeJobs: number;
          localDestination: string;
          schedules: Schedule[];
        }>("/api/backups/status").catch(() => ({
          activeJobs: 0,
          localDestination: "",
          schedules: [],
        })),
      ]);
      setBackups(listRes.backups);
      setSchedules(statusRes.schedules);
      setActiveJobs(statusRes.activeJobs);
    } finally {
      setLoading(false);
    }
  }, []);

  // Keep a live ref to the "is anything running" flag so the polling
  // effect below doesn't need `backups`/`activeJobs` in its dep array
  // (which caused a busy-loop: every load() updated state, which
  // re-ran the effect, which kicked off another load() immediately →
  // 429 rate limiting).
  const runningRef = useRef(false);
  useEffect(() => {
    runningRef.current =
      activeJobs > 0 || backups.some((b) => b.status === "RUNNING");
  }, [activeJobs, backups]);

  useEffect(() => {
    load();
    // Slow poll while idle, fast poll while something is actively running.
    // Only the page being visible counts — avoid hitting the rate limit
    // when the user has the tab in the background.
    let timer: ReturnType<typeof setTimeout> | null = null;
    const tick = () => {
      const visible =
        typeof document === "undefined" || !document.hidden;
      if (visible) {
        if (runningRef.current) {
          load();
          timer = setTimeout(tick, 5000);
          return;
        }
        // idle refresh: once per 30s keeps new schedule runs visible
        // without hammering the controller
      }
      timer = setTimeout(tick, 30000);
    };
    timer = setTimeout(tick, runningRef.current ? 5000 : 30000);
    return () => {
      if (timer) clearTimeout(timer);
    };
  }, [load]);

  // ── Actions ────────────────────────────────────────────────────

  const submitTrigger = async () => {
    setTriggerSubmitting(true);
    setError(null);
    try {
      const body: {
        targets: string[];
        scheduleClass: string;
        target?: string;
      } = {
        targets: Array.from(triggerForm.targets),
        scheduleClass: "manual",
      };
      if (triggerForm.targetName.trim()) body.target = triggerForm.targetName.trim();

      await apiFetch<BackupListResponse>("/api/backups/trigger", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      setTriggerOpen(false);
      setTriggerForm(EMPTY_TRIGGER_FORM);
      setNotice("Backup triggered");
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Trigger failed");
    } finally {
      setTriggerSubmitting(false);
    }
  };

  const deleteBackup = async (id: number) => {
    if (!confirm(`Delete backup #${id} and its archive file?`)) return;
    setWorking(true);
    try {
      await apiFetch(`/api/backups/${id}`, { method: "DELETE" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Delete failed");
    } finally {
      setWorking(false);
    }
  };

  const verifyBackup = async (id: number) => {
    setWorking(true);
    setError(null);
    try {
      const r = await apiFetch<VerifyResponse>(`/api/backups/${id}/verify`, {
        method: "POST",
      });
      if (r.valid) {
        setNotice(`Backup #${id} verified — manifest OK`);
      } else {
        setError(
          `Backup #${id} failed verification (${r.errors.length} problem${
            r.errors.length === 1 ? "" : "s"
          }): ${r.errors.slice(0, 3).join("; ")}`
        );
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Verify failed");
    } finally {
      setWorking(false);
    }
  };

  const restoreBackup = async (record: BackupRecord) => {
    const msg = `Restore backup #${record.id} (${record.targetType}/${record.targetName}) to its original location?

The target must be STOPPED first — pass force only if you know what you're doing.`;
    if (!confirm(msg)) return;
    const force = confirm(
      "Force the restore even if the target service is running? (Dangerous — players may see corrupted state.)"
    );
    setWorking(true);
    setError(null);
    try {
      await apiFetch(`/api/backups/${record.id}/restore`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dryRun: false, force }),
      });
      setNotice(`Restored backup #${record.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Restore failed");
    } finally {
      setWorking(false);
    }
  };

  const prune = async () => {
    if (
      !confirm(
        "Apply retention rules now? Backups beyond the configured keep counts will be deleted."
      )
    )
      return;
    setWorking(true);
    setError(null);
    try {
      const r = await apiFetch<PruneResponse>("/api/backups/prune", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dryRun: false }),
      });
      setNotice(`Pruned ${r.deleted} backup(s), freed ${formatSize(r.freedBytes)}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Prune failed");
    } finally {
      setWorking(false);
    }
  };

  // The archive endpoint is ADMIN-authed, so a plain <a download> won't work
  // (can't attach a Bearer header). We raw-fetch the response, turn it into a
  // blob, and trigger a client-side download. apiFetch itself isn't suited
  // because it always tries to parse JSON.
  const downloadArchive = async (record: BackupRecord) => {
    setWorking(true);
    setError(null);
    try {
      const res = await fetch(`${getApiUrl()}/api/backups/${record.id}/download`, {
        headers: { Authorization: `Bearer ${getToken()}` },
      });
      if (!res.ok) {
        throw new Error(`Download failed: ${res.status}`);
      }
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download =
        record.archivePath.split("/").pop() ||
        `backup-${record.id}.tar.zst`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Download failed");
    } finally {
      setWorking(false);
    }
  };

  // ── Stats ──────────────────────────────────────────────────────

  const totalSize = backups
    .filter((b) => b.status === "SUCCESS" || b.status === "PARTIAL")
    .reduce((sum, b) => sum + (b.sizeBytes ?? 0), 0);
  const successCount = backups.filter((b) => b.status === "SUCCESS").length;
  const failedCount = backups.filter((b) => b.status === "FAILED").length;
  const lastBackup = backups[0];

  const toggleTriggerTarget = (key: string) => {
    setTriggerForm((prev) => {
      const next = new Set(prev.targets);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return { ...prev, targets: next };
    });
  };

  const headerActions = (
    <div className="flex items-center gap-2">
      <Button variant="outline" onClick={load} disabled={loading}>
        <RefreshCw className="size-4 mr-1" />
        Refresh
      </Button>
      <Button variant="outline" onClick={prune} disabled={working}>
        <History className="size-4 mr-1" />
        Prune
      </Button>

      <Dialog
        open={triggerOpen}
        onOpenChange={(v) => {
          setTriggerOpen(v);
          if (!v) setTriggerForm(EMPTY_TRIGGER_FORM);
        }}
      >
        <DialogTrigger
          render={
            <Button>
              <Play className="size-4 mr-1" />
              Run backup
            </Button>
          }
        />
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Run a backup now</DialogTitle>
            <DialogDescription>
              Scheduled as <code className="text-xs">manual</code> so it's
              excluded from automatic retention pruning. Leave every target
              unchecked to back up every scope enabled in{" "}
              <code className="text-xs">backup.toml</code>.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium block mb-1">
                Target types
              </label>
              <div className="flex flex-wrap gap-2">
                {TARGET_TYPES.map((t) => {
                  const active = triggerForm.targets.has(t.key);
                  return (
                    <button
                      key={t.key}
                      type="button"
                      onClick={() => toggleTriggerTarget(t.key)}
                      className={
                        "rounded-md border px-2.5 py-1 text-xs transition " +
                        (active
                          ? "bg-primary text-primary-foreground border-primary"
                          : "hover:bg-accent")
                      }
                    >
                      {t.label}
                    </button>
                  );
                })}
              </div>
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">
                Specific service/dedicated name
                <span className="text-muted-foreground font-normal ml-1">
                  (optional)
                </span>
              </label>
              <Input
                value={triggerForm.targetName}
                onChange={(e) =>
                  setTriggerForm({ ...triggerForm, targetName: e.target.value })
                }
                placeholder="Lobby-1"
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setTriggerOpen(false)}
              disabled={triggerSubmitting}
            >
              Cancel
            </Button>
            <Button onClick={submitTrigger} disabled={triggerSubmitting}>
              {triggerSubmitting ? "Running…" : "Run backup"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );

  return (
    <>
      <PageHeader
        title="Backups"
        description="Scheduled tar+zstd snapshots of services, templates, config, and the database. Restore, verify, and manage retention."
        actions={headerActions}
      />

      {error && (
        <div className="mb-4 rounded-md border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-600 dark:text-red-400">
          {error}
          <button
            onClick={() => setError(null)}
            className="ml-2 underline"
            type="button"
          >
            dismiss
          </button>
        </div>
      )}
      {notice && (
        <div className="mb-4 rounded-md border border-green-500/30 bg-green-500/10 p-3 text-sm text-green-600 dark:text-green-400">
          {notice}
          <button
            onClick={() => setNotice(null)}
            className="ml-2 underline"
            type="button"
          >
            dismiss
          </button>
        </div>
      )}

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : (
        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as string)}>
          <TabsList>
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="settings">Settings</TabsTrigger>
          </TabsList>
          <TabsContent value="overview" className="mt-4 space-y-6">
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="Backups total"
              icon={Archive}
              tone="primary"
              value={backups.length}
              hint={`${successCount} success, ${failedCount} failed`}
            />
            <StatCard
              label="Storage used"
              icon={HardDrive}
              value={formatSize(totalSize)}
              hint="archives on disk"
            />
            <StatCard
              label="Schedules"
              icon={CalendarClock}
              value={schedules.length}
              hint={activeJobs > 0 ? `${activeJobs} job(s) running` : "idle"}
            />
            <StatCard
              label="Last backup"
              icon={History}
              value={lastBackup ? formatTimestamp(lastBackup.startedAt) : "—"}
              hint={
                lastBackup
                  ? `${lastBackup.targetType}/${lastBackup.targetName}`
                  : "no backups yet"
              }
            />
          </div>

          {/* ── Schedules ──────────────────────────────────────── */}
          <div>
            <h3 className="text-sm font-medium mb-2 text-muted-foreground">
              Schedules
            </h3>
            {schedules.length === 0 ? (
              <EmptyState
                icon={CalendarClock}
                title="No automatic backups yet"
                description="Add a schedule to back up your services on a recurring cron (hourly, daily, weekly). Without one, backups only run when triggered manually."
                action={
                  <Button
                    size="sm"
                    onClick={() => setActiveTab("settings")}
                  >
                    <Plus className="size-3.5 mr-1" />
                    Configure schedules
                  </Button>
                }
              />
            ) : (
              <Card>
                <CardContent className="p-0">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="pl-6">Name</TableHead>
                        <TableHead>Cron</TableHead>
                        <TableHead>Retention</TableHead>
                        <TableHead>Targets</TableHead>
                        <TableHead>Last run</TableHead>
                        <TableHead>Next run</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {schedules.map((s) => (
                        <TableRow key={s.name}>
                          <TableCell className="pl-6 font-medium">
                            {s.name}
                          </TableCell>
                          <TableCell className="font-mono text-xs">
                            {s.cron}
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline">{s.retentionClass}</Badge>
                          </TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1">
                              {s.targets.map((t) => (
                                <Badge
                                  key={t}
                                  variant="secondary"
                                  className="text-xs"
                                >
                                  {t}
                                </Badge>
                              ))}
                            </div>
                          </TableCell>
                          <TableCell className="text-xs">
                            {s.lastStatus ? (
                              <div className="flex items-center gap-1.5">
                                {statusBadge(s.lastStatus)}
                                <span className="text-muted-foreground">
                                  {formatTimestamp(s.lastRunAt)}
                                </span>
                              </div>
                            ) : (
                              <span className="text-muted-foreground">—</span>
                            )}
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {formatTimestamp(s.nextRunAt)}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </CardContent>
              </Card>
            )}
          </div>

          {/* ── Backup history ───────────────────────────────── */}
          <div>
            <h3 className="text-sm font-medium mb-2 text-muted-foreground">
              History
            </h3>
            {backups.length === 0 ? (
              <EmptyState
                icon={Archive}
                title="Nothing backed up yet"
                description={
                  schedules.length === 0
                    ? "You don't have any schedules and haven't run a manual backup yet. Kick one off now or set up a schedule to automate it."
                    : "Your first scheduled backup hasn't fired yet. You can also run one manually right now."
                }
                action={
                  <Button size="sm" onClick={() => setTriggerOpen(true)}>
                    <Play className="size-3.5 mr-1" />
                    Run backup now
                  </Button>
                }
              />
            ) : (
              <Card>
                <CardContent className="p-0">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="pl-6">ID</TableHead>
                        <TableHead>Target</TableHead>
                        <TableHead>Class</TableHead>
                        <TableHead>Started</TableHead>
                        <TableHead>Duration</TableHead>
                        <TableHead>Size</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead className="text-right pr-6">
                          Actions
                        </TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {backups.map((b) => (
                        <TableRow key={b.id}>
                          <TableCell className="pl-6 font-mono text-xs text-muted-foreground">
                            #{b.id}
                          </TableCell>
                          <TableCell>
                            <div className="text-sm">
                              <span className="font-medium">
                                {b.targetType}
                              </span>
                              {b.targetName && b.targetName !== "all" && (
                                <>
                                  <span className="text-muted-foreground">
                                    {" / "}
                                  </span>
                                  {b.targetName}
                                </>
                              )}
                            </div>
                            {b.errorMessage && (
                              <div
                                className="text-xs text-red-500 truncate max-w-xs"
                                title={b.errorMessage}
                              >
                                {b.errorMessage}
                              </div>
                            )}
                          </TableCell>
                          <TableCell>
                            <Badge variant="outline" className="text-xs">
                              {b.scheduleClass}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {formatTimestamp(b.startedAt)}
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {formatDuration(b.startedAt, b.completedAt)}
                          </TableCell>
                          <TableCell className="text-sm">
                            {formatSize(b.sizeBytes)}
                          </TableCell>
                          <TableCell>{statusBadge(b.status)}</TableCell>
                          <TableCell className="text-right pr-6">
                            {(b.status === "SUCCESS" ||
                              b.status === "PARTIAL") && (
                              <>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => verifyBackup(b.id)}
                                  disabled={working}
                                  title="Verify checksum manifest"
                                >
                                  <CheckCircle2 className="size-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => downloadArchive(b)}
                                  disabled={working}
                                  title="Download archive"
                                >
                                  <Download className="size-3.5" />
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => restoreBackup(b)}
                                  disabled={working}
                                  title="Restore"
                                >
                                  <Play className="size-3.5" />
                                </Button>
                              </>
                            )}
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => deleteBackup(b.id)}
                              disabled={working}
                              title="Delete"
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
          </div>
          </TabsContent>

          <TabsContent value="settings" className="mt-4">
            <BackupSettingsTab
              onSaved={(msg) => {
                setNotice(msg);
                load();
              }}
              onError={setError}
            />
          </TabsContent>
        </Tabs>
      )}
    </>
  );
}

// ────────────────────────────────────────────────────────────────
//  Settings tab — full editor for config/modules/backup/backup.toml
// ────────────────────────────────────────────────────────────────

interface BackupSettingsTabProps {
  onSaved: (message: string) => void;
  onError: (message: string) => void;
}

function BackupSettingsTab({ onSaved, onError }: BackupSettingsTabProps) {
  const [config, setConfig] = useState<BackupModuleConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [excludesText, setExcludesText] = useState("");
  const [editingSchedule, setEditingSchedule] = useState<{
    index: number;
    draft: ScheduleDef;
  } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const cfg = await apiFetch<BackupModuleConfig>("/api/backups/config");
      setConfig(cfg);
      setExcludesText(cfg.excludes.join("\n"));
    } catch (e) {
      onError(e instanceof Error ? e.message : "Failed to load config");
    } finally {
      setLoading(false);
    }
  }, [onError]);

  useEffect(() => {
    load();
  }, [load]);

  const save = async () => {
    if (!config) return;
    setSaving(true);
    try {
      const body: BackupModuleConfig = {
        ...config,
        excludes: excludesText
          .split("\n")
          .map((l) => l.trim())
          .filter((l) => l.length > 0),
      };
      const next = await apiFetch<BackupModuleConfig>("/api/backups/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      setConfig(next);
      setExcludesText(next.excludes.join("\n"));
      onSaved("Settings saved — schedule reloaded");
    } catch (e) {
      onError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  const patchConfig = (patch: Partial<BackupModuleConfig>) =>
    setConfig((c) => (c ? { ...c, ...patch } : c));
  const patchScope = (patch: Partial<ScopeConfig>) =>
    setConfig((c) => (c ? { ...c, scope: { ...c.scope, ...patch } } : c));
  const patchRetention = (patch: Partial<RetentionConfig>) =>
    setConfig((c) => (c ? { ...c, retention: { ...c.retention, ...patch } } : c));

  // ── Schedule editor ──

  const openAddSchedule = () =>
    setEditingSchedule({
      index: -1,
      draft: {
        name: "",
        cron: "0 * * * *",
        retentionClass: "hourly",
        targets: ["all"],
      },
    });
  const openEditSchedule = (i: number) => {
    if (!config) return;
    setEditingSchedule({ index: i, draft: { ...config.schedules[i] } });
  };
  const commitSchedule = () => {
    if (!config || !editingSchedule) return;
    const { index, draft } = editingSchedule;
    const schedules = [...config.schedules];
    if (index < 0) schedules.push(draft);
    else schedules[index] = draft;
    patchConfig({ schedules });
    setEditingSchedule(null);
  };
  const deleteSchedule = (i: number) => {
    if (!config) return;
    if (!confirm(`Delete schedule '${config.schedules[i].name}'?`)) return;
    patchConfig({ schedules: config.schedules.filter((_, idx) => idx !== i) });
  };
  const toggleScheduleTarget = (target: string) => {
    if (!editingSchedule) return;
    const next = new Set(editingSchedule.draft.targets);
    if (next.has(target)) next.delete(target);
    else next.add(target);
    setEditingSchedule({
      ...editingSchedule,
      draft: { ...editingSchedule.draft, targets: Array.from(next) },
    });
  };

  if (loading || !config) {
    return <Skeleton className="h-96 rounded-xl" />;
  }

  return (
    <div className="space-y-6">
      {/* ── General ───────────────────────────── */}
      <Card>
        <CardContent className="p-4 space-y-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h3 className="font-medium">General</h3>
              <p className="text-xs text-muted-foreground">
                Global toggles and performance tuning. Changes are written to{" "}
                <code className="text-xs">config/modules/backup/backup.toml</code>{" "}
                and hot-reloaded.
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">Enabled</span>
              <Switch
                checked={config.enabled}
                onCheckedChange={(v) => patchConfig({ enabled: v })}
              />
            </div>
          </div>

          <div className="grid gap-4 md:grid-cols-2">
            <LabeledInput
              label="Local destination"
              hint="Directory where archives are written (relative to Nimbus base)."
              value={config.localDestination}
              onChange={(v) => patchConfig({ localDestination: v })}
            />
            <LabeledInput
              label="Max concurrent jobs"
              hint="1..32 — bounded by a semaphore."
              type="number"
              min={1}
              max={32}
              value={config.maxConcurrent}
              onChange={(v) => patchConfig({ maxConcurrent: Number(v) || 1 })}
            />
            <LabeledInput
              label="Compression level"
              hint="zstd 1 (fastest) .. 22 (smallest)."
              type="number"
              min={1}
              max={22}
              value={config.compressionLevel}
              onChange={(v) =>
                patchConfig({
                  compressionLevel: Math.min(22, Math.max(1, Number(v) || 3)),
                })
              }
            />
            <LabeledInput
              label="Compression workers"
              hint="0 = auto (CPU/2). zstd-jni native multi-threading — the 3–5× speedup."
              type="number"
              min={0}
              max={64}
              value={config.compressionWorkers}
              onChange={(v) =>
                patchConfig({
                  compressionWorkers: Math.min(64, Math.max(0, Number(v) || 0)),
                })
              }
            />
          </div>

          <div className="flex items-center gap-6 pt-2 border-t">
            <div className="flex items-center gap-2">
              <Switch
                checked={config.quiesceServices}
                onCheckedChange={(v) => patchConfig({ quiesceServices: v })}
              />
              <span className="text-sm">
                Quiesce services
                <span className="block text-xs text-muted-foreground">
                  Send save-off/save-all before archiving
                </span>
              </span>
            </div>
            <LabeledInput
              label="Quiesce wait (s)"
              type="number"
              min={0}
              max={60}
              value={config.quiesceWaitSeconds}
              onChange={(v) =>
                patchConfig({
                  quiesceWaitSeconds: Math.min(60, Math.max(0, Number(v) || 0)),
                })
              }
              className="max-w-40"
            />
          </div>
        </CardContent>
      </Card>

      {/* ── Scope ──────────────────────────────── */}
      <Card>
        <CardContent className="p-4 space-y-3">
          <div>
            <h3 className="font-medium">Scope</h3>
            <p className="text-xs text-muted-foreground">
              Which types of data are eligible for backup. Schedules further
              narrow this per-run.
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            {SCOPE_KEYS.map((s) => (
              <label
                key={s.key}
                className="flex items-start gap-3 rounded-md border p-3 hover:bg-accent/40 cursor-pointer"
              >
                <Switch
                  checked={config.scope[s.key]}
                  onCheckedChange={(v) => patchScope({ [s.key]: v })}
                />
                <div className="min-w-0">
                  <div className="text-sm font-medium">{s.label}</div>
                  <div className="text-xs text-muted-foreground">{s.hint}</div>
                </div>
              </label>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* ── Schedules ─────────────────────────── */}
      <Card>
        <CardContent className="p-4 space-y-3">
          <div className="flex items-start justify-between">
            <div>
              <h3 className="font-medium">Schedules</h3>
              <p className="text-xs text-muted-foreground">
                Cron-based recurring backups. Syntax:{" "}
                <code className="text-xs">minute hour day-of-month month day-of-week</code>
                {" "}— e.g. <code className="text-xs">0 3 * * *</code> for 03:00 daily.
              </p>
            </div>
            <Button size="sm" variant="outline" onClick={openAddSchedule}>
              <Plus className="size-3.5 mr-1" />
              Add schedule
            </Button>
          </div>

          {config.schedules.length === 0 ? (
            <EmptyState
              icon={CalendarClock}
              title="No schedules defined"
              description="Add at least one cron entry to run backups automatically. Without a schedule, backups only happen when manually triggered."
              action={
                <Button size="sm" onClick={openAddSchedule}>
                  <Plus className="size-3.5 mr-1" />
                  Add schedule
                </Button>
              }
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Cron</TableHead>
                  <TableHead>Retention</TableHead>
                  <TableHead>Targets</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {config.schedules.map((s, i) => (
                  <TableRow key={`${s.name}-${i}`}>
                    <TableCell className="font-medium">{s.name}</TableCell>
                    <TableCell className="font-mono text-xs">{s.cron}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{s.retentionClass}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {s.targets.map((t) => (
                          <Badge key={t} variant="secondary" className="text-xs">
                            {t}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => openEditSchedule(i)}
                      >
                        <Pencil className="size-3.5" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => deleteSchedule(i)}
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* ── Retention ─────────────────────────── */}
      <Card>
        <CardContent className="p-4 space-y-3">
          <div>
            <h3 className="font-medium">Retention (GFS)</h3>
            <p className="text-xs text-muted-foreground">
              Keep N most recent successful backups per target, per retention
              class. FAILED backups don&apos;t count against the budget.
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-4">
            <LabeledInput
              label="Hourly keep"
              type="number"
              min={0}
              value={config.retention.hourlyKeep}
              onChange={(v) => patchRetention({ hourlyKeep: Math.max(0, Number(v) || 0) })}
            />
            <LabeledInput
              label="Daily keep"
              type="number"
              min={0}
              value={config.retention.dailyKeep}
              onChange={(v) => patchRetention({ dailyKeep: Math.max(0, Number(v) || 0) })}
            />
            <LabeledInput
              label="Weekly keep"
              type="number"
              min={0}
              value={config.retention.weeklyKeep}
              onChange={(v) => patchRetention({ weeklyKeep: Math.max(0, Number(v) || 0) })}
            />
            <LabeledInput
              label="Monthly keep"
              type="number"
              min={0}
              value={config.retention.monthlyKeep}
              onChange={(v) => patchRetention({ monthlyKeep: Math.max(0, Number(v) || 0) })}
            />
          </div>
          <label className="flex items-center gap-2 text-sm pt-2 border-t">
            <Switch
              checked={config.retention.keepManual}
              onCheckedChange={(v) => patchRetention({ keepManual: v })}
            />
            Never prune manual backups
          </label>
        </CardContent>
      </Card>

      {/* ── Excludes ──────────────────────────── */}
      <Card>
        <CardContent className="p-4 space-y-3">
          <div>
            <h3 className="font-medium">Exclude patterns</h3>
            <p className="text-xs text-muted-foreground">
              Glob patterns matched against each file&apos;s path relative to
              its backup source root. One pattern per line.
            </p>
          </div>
          <textarea
            value={excludesText}
            onChange={(e) => setExcludesText(e.target.value)}
            rows={Math.max(6, Math.min(16, excludesText.split("\n").length + 1))}
            className="w-full rounded-md border bg-background p-2 font-mono text-xs"
            spellCheck={false}
            placeholder={"logs/**\n*.log\ncache/**"}
          />
        </CardContent>
      </Card>

      {/* ── Actions ──────────────────────────── */}
      <div className="flex items-center justify-end gap-2 sticky bottom-0 pb-2">
        <Button variant="outline" onClick={load} disabled={saving}>
          <RefreshCw className="size-4 mr-1" />
          Reset
        </Button>
        <Button onClick={save} disabled={saving}>
          <Save className="size-4 mr-1" />
          {saving ? "Saving…" : "Save changes"}
        </Button>
      </div>

      {/* ── Schedule edit dialog ───────────────── */}
      <Dialog
        open={editingSchedule !== null}
        onOpenChange={(v) => {
          if (!v) setEditingSchedule(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editingSchedule?.index === -1 ? "Add schedule" : "Edit schedule"}
            </DialogTitle>
            <DialogDescription>
              Cron is a 5-field POSIX expression. Day-of-week: 0 or 7 = Sunday.
            </DialogDescription>
          </DialogHeader>

          {editingSchedule && (
            <div className="space-y-3">
              <LabeledInput
                label="Name"
                hint="A–Z, a–z, 0–9, _, - only. Used in filenames and logs."
                value={editingSchedule.draft.name}
                onChange={(v) =>
                  setEditingSchedule({
                    ...editingSchedule,
                    draft: { ...editingSchedule.draft, name: v },
                  })
                }
              />
              <LabeledInput
                label="Cron"
                hint="e.g. '0 * * * *' (hourly), '0 3 * * *' (daily 03:00), '0 4 * * 0' (Sunday 04:00)"
                value={editingSchedule.draft.cron}
                onChange={(v) =>
                  setEditingSchedule({
                    ...editingSchedule,
                    draft: { ...editingSchedule.draft, cron: v },
                  })
                }
                className="font-mono text-xs"
              />
              <div>
                <label className="text-xs font-medium block mb-1">
                  Retention class
                </label>
                <div className="flex flex-wrap gap-2">
                  {RETENTION_CLASSES.map((c) => {
                    const active = editingSchedule.draft.retentionClass === c;
                    return (
                      <button
                        key={c}
                        type="button"
                        onClick={() =>
                          setEditingSchedule({
                            ...editingSchedule,
                            draft: {
                              ...editingSchedule.draft,
                              retentionClass: c,
                            },
                          })
                        }
                        className={
                          "rounded-md border px-2.5 py-1 text-xs transition " +
                          (active
                            ? "bg-primary text-primary-foreground border-primary"
                            : "hover:bg-accent")
                        }
                      >
                        {c}
                      </button>
                    );
                  })}
                </div>
              </div>
              <div>
                <label className="text-xs font-medium block mb-1">Targets</label>
                <div className="flex flex-wrap gap-2">
                  {SCHEDULE_TARGET_OPTIONS.map((t) => {
                    const active = editingSchedule.draft.targets.includes(t);
                    return (
                      <button
                        key={t}
                        type="button"
                        onClick={() => toggleScheduleTarget(t)}
                        className={
                          "rounded-md border px-2.5 py-1 text-xs transition " +
                          (active
                            ? "bg-primary text-primary-foreground border-primary"
                            : "hover:bg-accent")
                        }
                      >
                        {t}
                      </button>
                    );
                  })}
                </div>
                <p className="text-[11px] text-muted-foreground mt-1">
                  <code>all</code> means &ldquo;every scope enabled above&rdquo;.
                </p>
              </div>
            </div>
          )}

          <DialogFooter>
            <Button variant="outline" onClick={() => setEditingSchedule(null)}>
              Cancel
            </Button>
            <Button
              onClick={commitSchedule}
              disabled={
                !editingSchedule?.draft.name.trim() ||
                !editingSchedule?.draft.cron.trim() ||
                editingSchedule?.draft.targets.length === 0
              }
            >
              {editingSchedule?.index === -1 ? "Add" : "Update"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

// ── Small labelled input helper ────────────────────────────────

interface LabeledInputProps {
  label: string;
  hint?: string;
  value: string | number;
  onChange: (value: string) => void;
  type?: string;
  min?: number;
  max?: number;
  className?: string;
}

function LabeledInput({
  label,
  hint,
  value,
  onChange,
  type = "text",
  min,
  max,
  className,
}: LabeledInputProps) {
  return (
    <div>
      <label className="text-xs font-medium block mb-1">{label}</label>
      <Input
        type={type}
        min={min}
        max={max}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={className}
      />
      {hint && (
        <p className="text-[11px] text-muted-foreground mt-1">{hint}</p>
      )}
    </div>
  );
}
