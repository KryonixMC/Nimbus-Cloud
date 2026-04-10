"use client";

import { useEffect, useState } from "react";
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Separator } from "@/components/ui/separator";
import { SidebarMenuButton } from "@/components/ui/sidebar";
import { apiFetch } from "@/lib/api";
import { InfoIcon, ChevronDownIcon, ExternalLinkIcon, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface ControllerInfo {
  version: string;
  startedAt: string;
  uptimeSeconds: number;
  jvmMemoryUsedMb: number;
  jvmMemoryMaxMb: number;
  jvmMemoryAllocatedMb: number;
  servicesMaxMemoryMb: number;
  servicesAllocatedMemoryMb: number;
  servicesUsedMemoryMb: number;
  runningServices: number;
  updateAvailable: boolean;
  latestVersion: string | null;
  updateType: string | null;
  releaseUrl: string | null;
}

interface ChangelogEntry {
  version: string;
  title: string;
  body: string;
}

interface ChangelogResponse {
  entries: ChangelogEntry[];
}

function formatUptime(seconds: number): string {
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const parts: string[] = [];
  if (days > 0) parts.push(`${days}d`);
  if (hours > 0 || days > 0) parts.push(`${hours}h`);
  parts.push(`${minutes}m`);
  return parts.join(" ");
}

function formatMb(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${mb} MB`;
}

/**
 * Minimal markdown renderer for changelog bodies. Handles ### subheadings,
 * bullet lists, **bold**, and [text](url) links. Good enough for the structure
 * used in docs/content/docs/project/changelog.mdx.
 */
function renderMarkdown(md: string): React.ReactNode[] {
  const lines = md.split("\n");
  const nodes: React.ReactNode[] = [];
  let listBuffer: React.ReactNode[] = [];
  let key = 0;

  const flushList = () => {
    if (listBuffer.length > 0) {
      nodes.push(
        <ul key={`ul-${key++}`} className="list-disc pl-5 space-y-1 text-sm text-muted-foreground">
          {listBuffer}
        </ul>
      );
      listBuffer = [];
    }
  };

  const renderInline = (text: string, baseKey: number): React.ReactNode[] => {
    // Split on bold + links, keeping delimiters
    const parts: React.ReactNode[] = [];
    const regex = /(\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g;
    let lastIndex = 0;
    let match: RegExpExecArray | null;
    let i = 0;
    while ((match = regex.exec(text)) !== null) {
      if (match.index > lastIndex) {
        parts.push(text.slice(lastIndex, match.index));
      }
      const token = match[0];
      if (token.startsWith("**")) {
        parts.push(
          <strong key={`${baseKey}-b-${i}`} className="text-foreground font-semibold">
            {token.slice(2, -2)}
          </strong>
        );
      } else {
        const m = /\[([^\]]+)\]\(([^)]+)\)/.exec(token)!;
        parts.push(
          <a
            key={`${baseKey}-l-${i}`}
            href={m[2]}
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary underline underline-offset-2"
          >
            {m[1]}
          </a>
        );
      }
      lastIndex = match.index + token.length;
      i++;
    }
    if (lastIndex < text.length) parts.push(text.slice(lastIndex));
    return parts;
  };

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();
    if (line.startsWith("### ")) {
      flushList();
      nodes.push(
        <h4 key={`h-${key++}`} className="text-xs font-semibold uppercase tracking-wide text-foreground mt-3 mb-1">
          {line.slice(4)}
        </h4>
      );
    } else if (line.startsWith("- ")) {
      listBuffer.push(<li key={`li-${key++}`}>{renderInline(line.slice(2), key)}</li>);
    } else if (line.length === 0) {
      flushList();
    } else {
      flushList();
      nodes.push(
        <p key={`p-${key++}`} className="text-sm text-muted-foreground">
          {renderInline(line, key)}
        </p>
      );
    }
  }
  flushList();
  return nodes;
}

export function InfoSheetTrigger() {
  const [open, setOpen] = useState(false);
  const [info, setInfo] = useState<ControllerInfo | null>(null);
  const [changelog, setChangelog] = useState<ChangelogResponse | null>(null);
  const [loadingInfo, setLoadingInfo] = useState(false);
  const [loadingChangelog, setLoadingChangelog] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setLoadingInfo(true);
    apiFetch<ControllerInfo>("/api/controller/info")
      .then(setInfo)
      .catch(() => {})
      .finally(() => setLoadingInfo(false));

    setLoadingChangelog(true);
    apiFetch<ChangelogResponse>("/api/controller/changelog")
      .then((data) => {
        setChangelog(data);
        // Auto-expand first entry
        if (data.entries.length > 0) setExpanded(data.entries[0].version);
      })
      .catch(() => {})
      .finally(() => setLoadingChangelog(false));
  }, [open]);

  const servicesPct = info && info.servicesMaxMemoryMb > 0
    ? Math.min(100, Math.round((info.servicesAllocatedMemoryMb / info.servicesMaxMemoryMb) * 100))
    : 0;
  const jvmPct = info && info.jvmMemoryMaxMb > 0
    ? Math.min(100, Math.round((info.jvmMemoryUsedMb / info.jvmMemoryMaxMb) * 100))
    : 0;

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger
        render={
          <SidebarMenuButton tooltip="Controller Info">
            <InfoIcon />
            <span>Info</span>
          </SidebarMenuButton>
        }
      />
      <SheetContent size="md">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            Controller Info
            {info && <Badge variant="outline">v{info.version}</Badge>}
          </SheetTitle>
          <SheetDescription>
            Runtime stats, update status and changelog.
          </SheetDescription>
        </SheetHeader>

        <SheetBody>
          {loadingInfo && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="size-4 animate-spin" /> Loading stats...
            </div>
          )}

          {info && (
            <>
              {info.updateAvailable && info.latestVersion && (
                <div className="rounded-md border border-primary/50 bg-primary/5 p-3 space-y-2">
                  <div className="flex items-center gap-2">
                    <Badge>Update available</Badge>
                    <span className="text-sm">
                      v{info.version} → <strong>v{info.latestVersion}</strong>
                      {info.updateType && (
                        <span className="text-xs text-muted-foreground ml-1">
                          ({info.updateType.toLowerCase()})
                        </span>
                      )}
                    </span>
                  </div>
                  {info.releaseUrl && (
                    <Button
                      variant="outline"
                      size="sm"
                      render={
                        <a href={info.releaseUrl} target="_blank" rel="noopener noreferrer">
                          <ExternalLinkIcon className="mr-1 size-3.5" />
                          View release
                        </a>
                      }
                    />
                  )}
                </div>
              )}

              <div className="space-y-4">
                <div>
                  <div className="flex items-center justify-between text-sm mb-1">
                    <span className="text-muted-foreground">Services memory</span>
                    <span className="font-mono text-xs">
                      {formatMb(info.servicesAllocatedMemoryMb)} / {formatMb(info.servicesMaxMemoryMb)}
                    </span>
                  </div>
                  <div className="h-2 rounded-full bg-muted overflow-hidden">
                    <div
                      className={cn(
                        "h-full transition-all",
                        servicesPct > 90
                          ? "bg-destructive"
                          : servicesPct > 75
                          ? "bg-yellow-500"
                          : "bg-primary"
                      )}
                      style={{ width: `${servicesPct}%` }}
                    />
                  </div>
                  <div className="text-xs text-muted-foreground mt-1">
                    {info.runningServices} running · actual {formatMb(info.servicesUsedMemoryMb)} · {servicesPct}% allocated
                  </div>
                </div>

                <div>
                  <div className="flex items-center justify-between text-sm mb-1">
                    <span className="text-muted-foreground">Controller JVM heap</span>
                    <span className="font-mono text-xs">
                      {formatMb(info.jvmMemoryUsedMb)} / {formatMb(info.jvmMemoryMaxMb)}
                    </span>
                  </div>
                  <div className="h-1.5 rounded-full bg-muted overflow-hidden">
                    <div
                      className={cn(
                        "h-full transition-all",
                        jvmPct > 90
                          ? "bg-destructive"
                          : jvmPct > 75
                          ? "bg-yellow-500"
                          : "bg-muted-foreground/60"
                      )}
                      style={{ width: `${jvmPct}%` }}
                    />
                  </div>
                </div>

                <Separator />

                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div>
                    <div className="text-xs text-muted-foreground">Uptime</div>
                    <div className="font-mono">{formatUptime(info.uptimeSeconds)}</div>
                  </div>
                  <div>
                    <div className="text-xs text-muted-foreground">Started</div>
                    <div className="font-mono text-xs">
                      {new Date(info.startedAt).toLocaleString()}
                    </div>
                  </div>
                </div>
              </div>
            </>
          )}

          <Separator />

          <div>
            <div className="text-sm font-medium mb-2">Changelog</div>
            {loadingChangelog && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="size-4 animate-spin" /> Loading changelog...
              </div>
            )}
            {changelog && changelog.entries.length === 0 && (
              <div className="text-sm text-muted-foreground">No changelog entries found.</div>
            )}
            {changelog && changelog.entries.length > 0 && (
              <div className="space-y-1">
                {changelog.entries.map((entry) => {
                  const isOpen = expanded === entry.version;
                  return (
                    <Collapsible
                      key={entry.version}
                      open={isOpen}
                      onOpenChange={(o) => setExpanded(o ? entry.version : null)}
                    >
                      <CollapsibleTrigger
                        render={
                          <button
                            className={cn(
                              "w-full flex items-center justify-between rounded-md px-3 py-2 text-left text-sm",
                              "hover:bg-muted transition-colors",
                              isOpen && "bg-muted"
                            )}
                          >
                            <span className="font-medium">{entry.title}</span>
                            <ChevronDownIcon
                              className={cn(
                                "size-4 text-muted-foreground transition-transform",
                                isOpen && "rotate-180"
                              )}
                            />
                          </button>
                        }
                      />
                      <CollapsibleContent>
                        <div className="px-3 pt-2 pb-3 space-y-1">
                          {renderMarkdown(entry.body)}
                        </div>
                      </CollapsibleContent>
                    </Collapsible>
                  );
                })}
              </div>
            )}
          </div>
        </SheetBody>
      </SheetContent>
    </Sheet>
  );
}
