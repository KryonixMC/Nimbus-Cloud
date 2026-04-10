"use client";

import { useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
} from "@/components/ui/dialog";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { Search, Download, PackageSearch } from "lucide-react";
import { Field, FieldLabel } from "@/components/ui/field";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";

interface PluginResult {
  source: string;
  name: string;
  author: string;
  slug: string;
  projectId: string;
  description: string;
  downloads: number;
}

interface GroupInfo {
  name: string;
}

function formatDownloads(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

export default function PluginsPage() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<PluginResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [searched, setSearched] = useState(false);
  const [groups, setGroups] = useState<GroupInfo[]>([]);

  const [installPlugin, setInstallPlugin] = useState<PluginResult | null>(null);
  const [installGroup, setInstallGroup] = useState("");
  const [installing, setInstalling] = useState(false);

  async function search() {
    if (!query.trim()) return;
    setSearching(true);
    setSearched(true);
    try {
      const [data, grp] = await Promise.all([
        apiFetch<{ results: PluginResult[] }>(
          `/api/plugins/search?q=${encodeURIComponent(query.trim())}`
        ),
        apiFetch<{ groups: GroupInfo[] }>("/api/groups").catch(() => ({
          groups: [],
        })),
      ]);
      setResults(data.results);
      setGroups(grp.groups);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Search failed");
    } finally {
      setSearching(false);
    }
  }

  async function install() {
    if (!installPlugin || !installGroup) return;
    setInstalling(true);
    try {
      await apiFetch("/api/plugins/install", {
        method: "POST",
        body: JSON.stringify({
          source: installPlugin.source,
          slug: installPlugin.slug,
          projectId: installPlugin.projectId,
          group: installGroup,
        }),
      });
      toast.success(
        `Plugin '${installPlugin.name}' installed to ${installGroup}`
      );
      setInstallPlugin(null);
      setInstallGroup("");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Install failed");
    } finally {
      setInstalling(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Plugins"
        description="Search Hangar + Modrinth and install plugins into any group."
      />

      <Card>
        <CardContent className="p-6 space-y-4">
          <div className="flex items-center gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search Hangar + Modrinth…"
                className="pl-9"
                onKeyDown={(e) => e.key === "Enter" && search()}
              />
            </div>
            <Button onClick={search} disabled={searching || !query.trim()}>
              {searching ? "Searching…" : "Search"}
            </Button>
          </div>

          {results.length > 0 && (
            <div className="overflow-hidden rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Name</TableHead>
                    <TableHead>Author</TableHead>
                    <TableHead>Source</TableHead>
                    <TableHead className="text-right">Downloads</TableHead>
                    <TableHead className="w-12" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {results.map((p) => (
                    <TableRow key={`${p.source}-${p.slug}`}>
                      <TableCell className="pl-4">
                        <div>
                          <div className="font-medium">{p.name}</div>
                          <div className="text-xs text-muted-foreground line-clamp-1">
                            {p.description}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className="text-sm">{p.author}</TableCell>
                      <TableCell>
                        <Badge variant="outline" className="text-xs">
                          {p.source}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right text-sm">
                        {formatDownloads(p.downloads)}
                      </TableCell>
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="icon"
                          onClick={() => {
                            setInstallPlugin(p);
                            setInstallGroup("");
                          }}
                        >
                          <Download className="size-4" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}

          {results.length === 0 && searched && !searching && (
            <EmptyState
              icon={PackageSearch}
              title="No plugins found"
              description={`Nothing matches "${query}" on Hangar or Modrinth.`}
            />
          )}

          {!searched && (
            <EmptyState
              icon={PackageSearch}
              title="Search for a plugin"
              description="Type a name above and press enter to search Hangar and Modrinth."
            />
          )}
        </CardContent>
      </Card>

      <Dialog
        open={!!installPlugin}
        onOpenChange={(open) => !open && setInstallPlugin(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Install {installPlugin?.name}</DialogTitle>
            <DialogDescription>{installPlugin?.description}</DialogDescription>
          </DialogHeader>
          <Field>
            <FieldLabel>Target group</FieldLabel>
            <Select
              value={installGroup}
              onValueChange={(v) => v && setInstallGroup(v)}
            >
              <SelectTrigger className="w-full">
                <SelectValue placeholder="Select group…" />
              </SelectTrigger>
              <SelectContent>
                <SelectGroup>
                  {groups.map((g) => (
                    <SelectItem key={g.name} value={g.name}>
                      {g.name}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
          </Field>
          <DialogFooter>
            <Button onClick={install} disabled={installing || !installGroup}>
              {installing ? "Installing…" : "Install"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
