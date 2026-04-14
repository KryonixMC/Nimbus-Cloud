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
import { apiFetch, apiUpload } from "@/lib/api";
import { PageHeader } from "@/components/page-header";
import { StatCard } from "@/components/stat-card";
import { EmptyState } from "@/components/empty-state";
import {
  Package,
  Signpost,
  Upload,
  Trash2,
  X,
  Plus,
} from "@/lib/icons";

interface ResourcePack {
  id: number;
  packUuid: string;
  name: string;
  source: string; // "URL" | "LOCAL"
  url: string;
  sha1Hash: string;
  promptMessage: string;
  force: boolean;
  fileSize: number;
  uploadedAt: string;
  uploadedBy: string;
}

interface Assignment {
  id: number;
  packId: number;
  scope: string; // "GLOBAL" | "GROUP" | "SERVICE"
  target: string;
  priority: number;
}

interface PackListResponse {
  packs: ResourcePack[];
  total: number;
}

function formatSize(bytes: number): string {
  if (bytes === 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

// ── URL dialog state ──────────────────────────────────────────
type UrlForm = {
  name: string;
  url: string;
  sha1: string;
  prompt: string;
  force: boolean;
};
const EMPTY_URL_FORM: UrlForm = {
  name: "",
  url: "",
  sha1: "",
  prompt: "",
  force: false,
};

// ── Upload dialog state ───────────────────────────────────────
type UploadForm = {
  file: File | null;
  name: string;
  prompt: string;
  force: boolean;
};
const EMPTY_UPLOAD_FORM: UploadForm = {
  file: null,
  name: "",
  prompt: "",
  force: false,
};

export default function ResourcePacksModulePage() {
  const [packs, setPacks] = useState<ResourcePack[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAssign, setShowAssign] = useState<number | null>(null);

  // Dialog state
  const [urlOpen, setUrlOpen] = useState(false);
  const [urlForm, setUrlForm] = useState<UrlForm>(EMPTY_URL_FORM);
  const [urlError, setUrlError] = useState<string | null>(null);
  const [urlSubmitting, setUrlSubmitting] = useState(false);

  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadForm, setUploadForm] = useState<UploadForm>(EMPTY_UPLOAD_FORM);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploadSubmitting, setUploadSubmitting] = useState(false);
  const uploadFileRef = useRef<HTMLInputElement>(null);

  // Assign form state
  const [assignScope, setAssignScope] = useState<"GLOBAL" | "GROUP" | "SERVICE">(
    "GLOBAL"
  );
  const [assignTarget, setAssignTarget] = useState("");
  const [assignPriority, setAssignPriority] = useState(0);

  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [packRes, assignRes] = await Promise.all([
        apiFetch<PackListResponse>("/api/resourcepacks").catch(() => ({
          packs: [],
          total: 0,
        })),
        apiFetch<Assignment[]>("/api/resourcepacks/assignments").catch(() => []),
      ]);
      setPacks(packRes.packs);
      setAssignments(assignRes);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // ── Add URL pack ────────────────────────────────────────────

  const submitUrlPack = async () => {
    if (
      !urlForm.name.trim() ||
      !urlForm.url.trim() ||
      urlForm.sha1.trim().length !== 40
    ) {
      setUrlError(
        "Name, URL and a 40-character SHA-1 hash are required."
      );
      return;
    }
    setUrlSubmitting(true);
    setUrlError(null);
    try {
      await apiFetch<ResourcePack>("/api/resourcepacks", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: urlForm.name.trim(),
          url: urlForm.url.trim(),
          sha1Hash: urlForm.sha1.toLowerCase().trim(),
          promptMessage: urlForm.prompt,
          force: urlForm.force,
        }),
      });
      setUrlOpen(false);
      setUrlForm(EMPTY_URL_FORM);
      await load();
    } catch (e) {
      setUrlError(e instanceof Error ? e.message : "Failed to add pack");
    } finally {
      setUrlSubmitting(false);
    }
  };

  // ── Upload .zip ────────────────────────────────────────────

  const handleFileSelected = (file: File | null) => {
    setUploadForm((prev) => ({
      ...prev,
      file,
      // auto-populate name from filename if the user hasn't edited it yet
      name: prev.name.trim() === "" && file
        ? file.name.replace(/\.zip$/i, "")
        : prev.name,
    }));
  };

  const submitUpload = async () => {
    if (!uploadForm.file) {
      setUploadError("Pick a .zip file first.");
      return;
    }
    if (!uploadForm.name.trim()) {
      setUploadError("Give the pack a name.");
      return;
    }
    setUploadSubmitting(true);
    setUploadError(null);
    try {
      const qp = new URLSearchParams({
        name: uploadForm.name.trim(),
        force: String(uploadForm.force),
      });
      if (uploadForm.prompt.trim()) qp.set("prompt", uploadForm.prompt.trim());
      // apiUpload handles: bearer token, HTTPS→HTTP proxying (chunked), errors.
      // Controller accepts raw body (application/octet-stream).
      await apiUpload<ResourcePack>(
        `/api/resourcepacks/upload?${qp.toString()}`,
        uploadForm.file
      );
      setUploadOpen(false);
      setUploadForm(EMPTY_UPLOAD_FORM);
      await load();
    } catch (e) {
      setUploadError(e instanceof Error ? e.message : "Upload failed");
    } finally {
      setUploadSubmitting(false);
    }
  };

  // ── Pack row actions ────────────────────────────────────────

  const deletePack = async (id: number) => {
    if (!confirm("Delete this pack and all its assignments?")) return;
    setWorking(true);
    try {
      await apiFetch(`/api/resourcepacks/${id}`, { method: "DELETE" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Delete failed");
    } finally {
      setWorking(false);
    }
  };

  const submitAssign = async () => {
    if (showAssign === null) return;
    if (assignScope !== "GLOBAL" && !assignTarget.trim()) return;
    setWorking(true);
    setError(null);
    try {
      await apiFetch(`/api/resourcepacks/${showAssign}/assignments`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          scope: assignScope,
          target: assignScope === "GLOBAL" ? "" : assignTarget.trim(),
          priority: assignPriority,
        }),
      });
      setShowAssign(null);
      setAssignTarget("");
      setAssignPriority(0);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Assign failed");
    } finally {
      setWorking(false);
    }
  };

  const removeAssignment = async (id: number) => {
    setWorking(true);
    try {
      await apiFetch(`/api/resourcepacks/assignments/${id}`, { method: "DELETE" });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Remove failed");
    } finally {
      setWorking(false);
    }
  };

  const assignmentsByPack = assignments.reduce<Record<number, Assignment[]>>(
    (acc, a) => {
      (acc[a.packId] ??= []).push(a);
      return acc;
    },
    {}
  );

  const totalSize = packs
    .filter((p) => p.source === "LOCAL")
    .reduce((sum, p) => sum + p.fileSize, 0);

  const headerActions = (
    <div className="flex items-center gap-2">
      {/* ── Add URL dialog ── */}
      <Dialog
        open={urlOpen}
        onOpenChange={(v) => {
          setUrlOpen(v);
          if (!v) {
            setUrlForm(EMPTY_URL_FORM);
            setUrlError(null);
          }
        }}
      >
        <DialogTrigger
          render={
            <Button variant="outline">
              <Plus className="size-4 mr-1" />
              Add URL
            </Button>
          }
        />
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Add URL pack</DialogTitle>
            <DialogDescription>
              Register a pack hosted elsewhere (CDN, GitHub release, …). The
              SHA-1 hash is required so the Minecraft client can verify the
              file on download.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium block mb-1">Name</label>
              <Input
                value={urlForm.name}
                onChange={(e) =>
                  setUrlForm({ ...urlForm, name: e.target.value })
                }
                placeholder="Vanilla+ HD"
                autoFocus
              />
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">URL</label>
              <Input
                value={urlForm.url}
                onChange={(e) =>
                  setUrlForm({ ...urlForm, url: e.target.value })
                }
                placeholder="https://cdn.example.com/pack.zip"
              />
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">
                SHA-1 hash
                <span className="text-muted-foreground font-normal ml-1">
                  (40 hex chars)
                </span>
              </label>
              <Input
                value={urlForm.sha1}
                onChange={(e) =>
                  setUrlForm({ ...urlForm, sha1: e.target.value })
                }
                placeholder="a1b2c3d4…"
                maxLength={40}
                className="font-mono text-xs"
              />
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">
                Prompt message
                <span className="text-muted-foreground font-normal ml-1">
                  (optional)
                </span>
              </label>
              <Input
                value={urlForm.prompt}
                onChange={(e) =>
                  setUrlForm({ ...urlForm, prompt: e.target.value })
                }
                placeholder="Shown to the player before download"
              />
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={urlForm.force}
                onChange={(e) =>
                  setUrlForm({ ...urlForm, force: e.target.checked })
                }
              />
              Force — kick players who decline
            </label>

            {urlError && (
              <div className="rounded-md border border-red-500/30 bg-red-500/10 p-2 text-xs text-red-600 dark:text-red-400">
                {urlError}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setUrlOpen(false)}
              disabled={urlSubmitting}
            >
              Cancel
            </Button>
            <Button
              onClick={submitUrlPack}
              disabled={
                urlSubmitting ||
                !urlForm.name.trim() ||
                !urlForm.url.trim() ||
                urlForm.sha1.trim().length !== 40
              }
            >
              {urlSubmitting ? "Adding…" : "Add pack"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ── Upload .zip dialog ── */}
      <Dialog
        open={uploadOpen}
        onOpenChange={(v) => {
          setUploadOpen(v);
          if (!v) {
            setUploadForm(EMPTY_UPLOAD_FORM);
            setUploadError(null);
          }
        }}
      >
        <DialogTrigger
          render={
            <Button>
              <Upload className="size-4 mr-1" />
              Upload .zip
            </Button>
          }
        />
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Upload resource pack</DialogTitle>
            <DialogDescription>
              The file is streamed to the controller and the SHA-1 is computed
              server-side. Players fetch it from the controller at
              <code className="mx-1 text-xs">
                /api/resourcepacks/files/&lt;uuid&gt;.zip
              </code>
              with no auth (hash protects against tampering).
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium block mb-1">File</label>
              <input
                ref={uploadFileRef}
                type="file"
                accept=".zip"
                className="hidden"
                onChange={(e) =>
                  handleFileSelected(e.target.files?.[0] ?? null)
                }
              />
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => uploadFileRef.current?.click()}
                  disabled={uploadSubmitting}
                >
                  Choose .zip…
                </Button>
                <span className="text-xs text-muted-foreground truncate">
                  {uploadForm.file
                    ? `${uploadForm.file.name} — ${formatSize(
                        uploadForm.file.size
                      )}`
                    : "No file selected"}
                </span>
              </div>
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">Name</label>
              <Input
                value={uploadForm.name}
                onChange={(e) =>
                  setUploadForm({ ...uploadForm, name: e.target.value })
                }
                placeholder="Vanilla+ HD"
              />
            </div>
            <div>
              <label className="text-xs font-medium block mb-1">
                Prompt message
                <span className="text-muted-foreground font-normal ml-1">
                  (optional)
                </span>
              </label>
              <Input
                value={uploadForm.prompt}
                onChange={(e) =>
                  setUploadForm({ ...uploadForm, prompt: e.target.value })
                }
                placeholder="Shown to the player before download"
              />
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={uploadForm.force}
                onChange={(e) =>
                  setUploadForm({ ...uploadForm, force: e.target.checked })
                }
              />
              Force — kick players who decline
            </label>

            {uploadError && (
              <div className="rounded-md border border-red-500/30 bg-red-500/10 p-2 text-xs text-red-600 dark:text-red-400">
                {uploadError}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setUploadOpen(false)}
              disabled={uploadSubmitting}
            >
              Cancel
            </Button>
            <Button
              onClick={submitUpload}
              disabled={
                uploadSubmitting || !uploadForm.file || !uploadForm.name.trim()
              }
            >
              {uploadSubmitting ? "Uploading…" : "Upload"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );

  return (
    <>
      <PageHeader
        title="Resource Packs"
        description="Network-wide pack registry with GLOBAL / GROUP / SERVICE assignments."
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

      {loading ? (
        <Skeleton className="h-96 rounded-xl" />
      ) : (
        <div className="space-y-6">
          <div className="grid gap-4 md:grid-cols-3">
            <StatCard
              label="Packs registered"
              icon={Package}
              tone="primary"
              value={packs.length}
            />
            <StatCard
              label="Assignments"
              icon={Signpost}
              value={assignments.length}
              hint="across scopes"
            />
            <StatCard
              label="Local storage"
              icon={Upload}
              value={formatSize(totalSize)}
              hint="hosted files"
            />
          </div>

          {packs.length === 0 ? (
            <EmptyState
              icon={Package}
              title="No resource packs"
              description="Add a URL pack or upload a .zip — then assign it to a group, service, or globally."
            />
          ) : (
            <Card>
              <CardContent className="p-0">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="pl-6">Name</TableHead>
                      <TableHead>Source</TableHead>
                      <TableHead>Size</TableHead>
                      <TableHead>Assignments</TableHead>
                      <TableHead className="text-right pr-6">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {packs.map((p) => (
                      <TableRow key={p.id}>
                        <TableCell className="pl-6">
                          <div className="font-medium">
                            {p.name}
                            {p.force && (
                              <Badge
                                variant="outline"
                                className="ml-2 text-[10px] border-orange-500/40 text-orange-600 dark:text-orange-400"
                              >
                                FORCE
                              </Badge>
                            )}
                          </div>
                          <div
                            className="text-xs text-muted-foreground truncate max-w-xs"
                            title={p.url}
                          >
                            {p.url}
                          </div>
                        </TableCell>
                        <TableCell>
                          <Badge variant="outline">{p.source}</Badge>
                        </TableCell>
                        <TableCell className="text-sm">
                          {formatSize(p.fileSize)}
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-wrap gap-1">
                            {(assignmentsByPack[p.id] ?? []).map((a) => (
                              <Badge
                                key={a.id}
                                variant="secondary"
                                className="text-xs gap-1"
                              >
                                {a.scope}
                                {a.target && `:${a.target}`}
                                <button
                                  className="hover:text-red-500"
                                  onClick={() => removeAssignment(a.id)}
                                  type="button"
                                  aria-label="Remove"
                                >
                                  <X className="size-3" />
                                </button>
                              </Badge>
                            ))}
                            {!assignmentsByPack[p.id]?.length && (
                              <span className="text-xs text-muted-foreground">
                                unused
                              </span>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="text-right pr-6">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setShowAssign(p.id)}
                          >
                            Assign
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => deletePack(p.id)}
                            disabled={working}
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

          {showAssign !== null && (
            <Card>
              <CardContent className="p-4 space-y-3">
                <h3 className="font-medium">Assign pack #{showAssign}</h3>
                <div className="flex flex-wrap gap-3">
                  <div>
                    <label className="text-xs text-muted-foreground block mb-1">
                      Scope
                    </label>
                    <select
                      value={assignScope}
                      onChange={(e) =>
                        setAssignScope(e.target.value as typeof assignScope)
                      }
                      className="border rounded-md px-2 py-1.5 text-sm"
                    >
                      <option value="GLOBAL">GLOBAL</option>
                      <option value="GROUP">GROUP</option>
                      <option value="SERVICE">SERVICE</option>
                    </select>
                  </div>
                  {assignScope !== "GLOBAL" && (
                    <div className="flex-1 min-w-48">
                      <label className="text-xs text-muted-foreground block mb-1">
                        {assignScope === "GROUP"
                          ? "Group name"
                          : "Service name"}
                      </label>
                      <Input
                        value={assignTarget}
                        onChange={(e) => setAssignTarget(e.target.value)}
                        placeholder={
                          assignScope === "GROUP" ? "Lobby" : "Lobby-1"
                        }
                      />
                    </div>
                  )}
                  <div className="w-32">
                    <label className="text-xs text-muted-foreground block mb-1">
                      Priority
                    </label>
                    <Input
                      type="number"
                      value={assignPriority}
                      onChange={(e) =>
                        setAssignPriority(Number(e.target.value))
                      }
                    />
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" onClick={submitAssign} disabled={working}>
                    Assign
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => setShowAssign(null)}
                  >
                    Cancel
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
