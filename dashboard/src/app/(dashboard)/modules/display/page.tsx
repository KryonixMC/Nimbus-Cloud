"use client";

import { useEffect, useState } from "react";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { Field, FieldLabel } from "@/components/ui/field";
import { Save, Signpost } from "lucide-react";
import { MinecraftSign } from "@/components/minecraft-sign";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";
import { SectionLabel } from "@/components/section-label";
import { cn } from "@/lib/utils";

interface NpcResponse {
  displayName: string;
  subtitle: string;
  subtitleOffline: string;
  floatingItem: string;
  statusItems: Record<string, string>;
  inventory: {
    title: string;
    size: number;
    itemName: string;
    itemLore: string[];
  };
}

interface DisplayResponse {
  name: string;
  sign: {
    line1: string;
    line2: string;
    line3: string;
    line4Online: string;
    line4Offline: string;
  };
  npc: NpcResponse;
}

interface DisplayListResponse {
  displays: DisplayResponse[];
  total: number;
}

export default function DisplayPage() {
  const [displays, setDisplays] = useState<DisplayResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [editDisplay, setEditDisplay] = useState<DisplayResponse | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const [line1, setLine1] = useState("");
  const [line2, setLine2] = useState("");
  const [line3, setLine3] = useState("");
  const [line4Online, setLine4Online] = useState("");
  const [line4Offline, setLine4Offline] = useState("");
  const [npcName, setNpcName] = useState("");
  const [showOffline, setShowOffline] = useState(false);

  async function load() {
    try {
      const data = await apiFetch<DisplayListResponse>("/api/displays");
      setDisplays(data.displays);
    } catch {
      setDisplays([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  function openEdit(d: DisplayResponse) {
    setEditDisplay(d);
    setLine1(d.sign.line1);
    setLine2(d.sign.line2);
    setLine3(d.sign.line3);
    setLine4Online(d.sign.line4Online);
    setLine4Offline(d.sign.line4Offline);
    setNpcName(d.npc.displayName);
    setShowOffline(false);
    setEditOpen(true);
  }

  async function save() {
    if (!editDisplay) return;
    setSaving(true);
    try {
      await apiFetch(`/api/displays/${editDisplay.name}`, {
        method: "PUT",
        body: JSON.stringify({
          sign: { line1, line2, line3, line4Online, line4Offline },
          npc: { ...editDisplay.npc, displayName: npcName },
        }),
      });
      toast.success(`'${editDisplay.name}' saved`);
      setEditOpen(false);
      load();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to save");
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Display"
        description="Server selector signs and NPCs, one configuration per backend group."
      />

      {loading ? (
        <Skeleton className="h-64 rounded-xl" />
      ) : displays.length === 0 ? (
        <EmptyState
          icon={Signpost}
          title="No display configurations"
          description="The Display module is either not loaded, or you haven't configured any signs yet."
        />
      ) : (
        <Card>
          <CardContent className="p-6">
            <div className="flex flex-wrap gap-6">
              {displays.map((d) => (
                <MinecraftSign
                  key={d.name}
                  lines={[
                    d.sign.line1,
                    d.sign.line2,
                    d.sign.line3,
                    d.sign.line4Online,
                  ]}
                  label={d.name}
                  onClick={() => openEdit(d)}
                />
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      <Sheet open={editOpen} onOpenChange={setEditOpen}>
        <SheetContent size="lg">
          <SheetHeader>
            <SheetTitle>Edit display · {editDisplay?.name}</SheetTitle>
            <SheetDescription>
              Signs support Minecraft color codes (&amp;c, &amp;a, …). Line 4
              changes automatically when the backend goes offline.
            </SheetDescription>
          </SheetHeader>

          <SheetBody>
            {/* Live preview */}
            <div className="flex flex-col items-center gap-3">
              <MinecraftSign
                lines={[
                  line1,
                  line2,
                  line3,
                  showOffline ? line4Offline : line4Online,
                ]}
              />
              <div className="inline-flex rounded-md border bg-muted/40 p-0.5 text-xs">
                <button
                  type="button"
                  onClick={() => setShowOffline(false)}
                  className={cn(
                    "rounded px-3 py-1 transition-colors",
                    !showOffline
                      ? "bg-background text-foreground shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  Online preview
                </button>
                <button
                  type="button"
                  onClick={() => setShowOffline(true)}
                  className={cn(
                    "rounded px-3 py-1 transition-colors",
                    showOffline
                      ? "bg-background text-foreground shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  Offline preview
                </button>
              </div>
            </div>

            <section className="space-y-3">
              <SectionLabel>Sign lines</SectionLabel>
              <Field>
                <FieldLabel>Line 1</FieldLabel>
                <Input
                  value={line1}
                  onChange={(e) => setLine1(e.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel>Line 2</FieldLabel>
                <Input
                  value={line2}
                  onChange={(e) => setLine2(e.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel>Line 3</FieldLabel>
                <Input
                  value={line3}
                  onChange={(e) => setLine3(e.target.value)}
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field>
                  <FieldLabel>Line 4 · online</FieldLabel>
                  <Input
                    value={line4Online}
                    onChange={(e) => setLine4Online(e.target.value)}
                  />
                </Field>
                <Field>
                  <FieldLabel>Line 4 · offline</FieldLabel>
                  <Input
                    value={line4Offline}
                    onChange={(e) => setLine4Offline(e.target.value)}
                  />
                </Field>
              </div>
            </section>

            <section className="space-y-3">
              <SectionLabel>NPC</SectionLabel>
              <Field>
                <FieldLabel>Display name</FieldLabel>
                <Input
                  value={npcName}
                  onChange={(e) => setNpcName(e.target.value)}
                />
              </Field>
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
            <Button onClick={save} disabled={saving}>
              <Save className="mr-1 size-4" />
              {saving ? "Saving…" : "Save changes"}
            </Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    </>
  );
}
