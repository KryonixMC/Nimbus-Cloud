"use client";

import { useState, useEffect, useCallback } from "react";
import * as api from "@/lib/api";
import { FileEntry } from "@/lib/types";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { toast } from "sonner";
import {
  FolderOpen,
  File,
  ArrowLeft,
  Save,
  Trash2,
  RefreshCw,
} from "lucide-react";

type Scope = "templates" | "services" | "groups";

function formatSize(bytes: number): string {
  if (bytes === 0) return "-";
  const units = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

export default function FilesPage() {
  const [scope, setScope] = useState<Scope>("templates");
  const [path, setPath] = useState("/");
  const [entries, setEntries] = useState<FileEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [editDialog, setEditDialog] = useState<{ path: string; content: string } | null>(null);

  const loadEntries = useCallback(async () => {
    setLoading(true);
    try {
      const fullPath = path === "/" ? scope : `${scope}/${path}`;
      const data = await api.listFiles(fullPath);
      setEntries(data.entries ?? []);
    } catch {
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, [scope, path]);

  useEffect(() => {
    loadEntries();
  }, [loadEntries]);

  function navigate(entry: FileEntry) {
    if (entry.isDirectory) {
      setPath(path === "/" ? entry.name : `${path}/${entry.name}`);
    } else {
      openFile(entry);
    }
  }

  function goUp() {
    if (path === "/") return;
    const parts = path.split("/");
    parts.pop();
    setPath(parts.length === 0 ? "/" : parts.join("/"));
  }

  async function openFile(entry: FileEntry) {
    try {
      const fullPath = path === "/" ? `${scope}/${entry.name}` : `${scope}/${path}/${entry.name}`;
      const data = await api.readFile(fullPath);
      if (data.content !== undefined) {
        setEditDialog({ path: fullPath, content: data.content });
      } else {
        toast.info("Binary file - download not supported in browser");
      }
    } catch (e) {
      toast.error(`Failed to read file: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleSave() {
    if (!editDialog) return;
    try {
      await api.writeFile(editDialog.path, editDialog.content);
      toast.success("File saved");
      setEditDialog(null);
    } catch (e) {
      toast.error(`Failed to save: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  async function handleDelete(entry: FileEntry) {
    const fullPath = path === "/" ? `${scope}/${entry.name}` : `${scope}/${path}/${entry.name}`;
    if (!confirm(`Delete "${entry.name}"?`)) return;
    try {
      await api.deleteFile(fullPath);
      toast.success(`Deleted "${entry.name}"`);
      loadEntries();
    } catch (e) {
      toast.error(`Failed: ${e instanceof Error ? e.message : "Unknown error"}`);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Files</h1>
        <p className="text-muted-foreground">Browse and edit server files</p>
      </div>

      <Tabs value={scope} onValueChange={(v) => { if (v) { setScope(v as Scope); setPath("/"); } }}>
        <TabsList>
          <TabsTrigger value="templates">Templates</TabsTrigger>
          <TabsTrigger value="services">Services</TabsTrigger>
          <TabsTrigger value="groups">Groups</TabsTrigger>
        </TabsList>

        <TabsContent value={scope}>
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="flex items-center gap-2 text-sm font-mono">
                  <FolderOpen className="h-4 w-4" />
                  /{scope}{path !== "/" ? `/${path}` : ""}
                </CardTitle>
                <Button variant="outline" size="sm" onClick={loadEntries}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  Refresh
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              {loading ? (
                <div className="space-y-2">
                  {[...Array(5)].map((_, i) => (
                    <div key={i} className="h-10 animate-pulse rounded bg-muted" />
                  ))}
                </div>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Size</TableHead>
                      <TableHead>Modified</TableHead>
                      <TableHead className="w-[80px]">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {path !== "/" && (
                      <TableRow
                        className="cursor-pointer hover:bg-muted/50"
                        onClick={goUp}
                      >
                        <TableCell className="flex items-center gap-2">
                          <ArrowLeft className="h-4 w-4" />
                          ..
                        </TableCell>
                        <TableCell />
                        <TableCell />
                        <TableCell />
                      </TableRow>
                    )}
                    {entries.map((entry) => (
                      <TableRow
                        key={entry.name}
                        className="cursor-pointer hover:bg-muted/50"
                        onClick={() => navigate(entry)}
                      >
                        <TableCell className="flex items-center gap-2">
                          {entry.isDirectory ? (
                            <FolderOpen className="h-4 w-4 text-blue-500" />
                          ) : (
                            <File className="h-4 w-4 text-muted-foreground" />
                          )}
                          {entry.name}
                        </TableCell>
                        <TableCell className="text-muted-foreground">
                          {formatSize(entry.size)}
                        </TableCell>
                        <TableCell className="text-muted-foreground text-xs">
                          {entry.lastModified ? new Date(entry.lastModified).toLocaleString() : "-"}
                        </TableCell>
                        <TableCell>
                          {scope !== "groups" && (
                            <Button
                              variant="ghost"
                              size="icon"
                              onClick={(e) => { e.stopPropagation(); handleDelete(entry); }}
                            >
                              <Trash2 className="h-4 w-4 text-destructive" />
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                    {entries.length === 0 && (
                      <TableRow>
                        <TableCell colSpan={4} className="text-center text-muted-foreground py-8">
                          Empty directory
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={!!editDialog} onOpenChange={() => setEditDialog(null)}>
        <DialogContent className="max-w-3xl max-h-[80vh]">
          <DialogHeader>
            <DialogTitle className="font-mono text-sm">{editDialog?.path}</DialogTitle>
          </DialogHeader>
          <Textarea
            className="min-h-[400px] font-mono text-sm"
            value={editDialog?.content ?? ""}
            onChange={(e) =>
              setEditDialog(editDialog ? { ...editDialog, content: e.target.value } : null)
            }
          />
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditDialog(null)}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={scope === "groups"}>
              <Save className="mr-2 h-4 w-4" />
              Save
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
