"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { apiFetch } from "@/lib/api";
import { toast } from "sonner";
import { Field, FieldLabel } from "@/components/ui/field";
import { Save } from "lucide-react";

interface DisplayResponse {
  name: string;
  sign: {
    line1: string;
    line2: string;
    line3: string;
    line4Online: string;
    line4Offline: string;
  };
  npc: { displayName: string };
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
          npc: { displayName: npcName },
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

  if (loading) return <Skeleton className="h-96 rounded-xl" />;

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Display Configurations ({displays.length})</CardTitle>
        </CardHeader>
        <CardContent>
          {displays.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No display configurations. Is the Display module loaded?
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Group</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {displays.map((d) => (
                  <TableRow
                    key={d.name}
                    className="cursor-pointer"
                    onClick={() => openEdit(d)}
                  >
                    <TableCell className="font-medium">{d.name}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Sheet open={editOpen} onOpenChange={setEditOpen}>
        <SheetContent className="w-[480px] sm:w-[600px] p-0">
          <div className="p-6 pb-0">
            <SheetHeader>
              <SheetTitle>Edit Display: {editDisplay?.name}</SheetTitle>
            </SheetHeader>
          </div>
          <ScrollArea className="h-[calc(100vh-6rem)]">
            <div className="space-y-4 p-6">
              <h3 className="text-sm font-semibold">Sign Lines</h3>
              <Field>
                <FieldLabel>Line 1</FieldLabel>
                <Input value={line1} onChange={(e) => setLine1(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel>Line 2</FieldLabel>
                <Input value={line2} onChange={(e) => setLine2(e.target.value)} />
              </Field>
              <Field>
                <FieldLabel>Line 3</FieldLabel>
                <Input value={line3} onChange={(e) => setLine3(e.target.value)} />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field>
                  <FieldLabel>Line 4 (Online)</FieldLabel>
                  <Input value={line4Online} onChange={(e) => setLine4Online(e.target.value)} />
                </Field>
                <Field>
                  <FieldLabel>Line 4 (Offline)</FieldLabel>
                  <Input value={line4Offline} onChange={(e) => setLine4Offline(e.target.value)} />
                </Field>
              </div>

              <h3 className="text-sm font-semibold pt-2">NPC</h3>
              <Field>
                <FieldLabel>Display Name</FieldLabel>
                <Input value={npcName} onChange={(e) => setNpcName(e.target.value)} />
              </Field>

              <Button onClick={save} disabled={saving} className="w-full mt-2">
                <Save className="mr-1 size-4" />
                {saving ? "Saving..." : "Save"}
              </Button>
            </div>
          </ScrollArea>
        </SheetContent>
      </Sheet>
    </>
  );
}
