"use client";

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  deletePasskey,
  enrollPasskey,
  isPasskeySupported,
  listPasskeys,
} from "@/lib/passkeys";
import { Shield } from "@/lib/icons";

interface PasskeyDto {
  credentialId: string;
  label: string;
  createdAt: number;
  lastUsedAt: number | null;
  aaguid: string | null;
}

function formatRelative(ts: number): string {
  const diff = Date.now() - ts;
  if (diff < 0) return "just now";
  const s = Math.floor(diff / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

export function PasskeyCard() {
  const supported = isPasskeySupported();
  const [credentials, setCredentials] = useState<PasskeyDto[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [enrollOpen, setEnrollOpen] = useState(false);
  const [label, setLabel] = useState("");
  const [enrolling, setEnrolling] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await listPasskeys();
      setCredentials(list);
    } catch {
      setCredentials([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleEnroll = async () => {
    setEnrolling(true);
    try {
      await enrollPasskey(label.trim() || "Passkey");
      toast.success("Passkey added");
      setEnrollOpen(false);
      setLabel("");
      await refresh();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Failed to add passkey";
      if (!/cancel/i.test(msg)) toast.error(msg);
    } finally {
      setEnrolling(false);
    }
  };

  const handleDelete = async (id: string) => {
    setRemovingId(id);
    try {
      await deletePasskey(id);
      toast.success("Passkey removed");
      await refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Failed to remove passkey");
    } finally {
      setRemovingId(null);
    }
  };

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between gap-2">
            <span className="flex items-center gap-2">
              <Shield className="size-4" />
              Passkeys
            </span>
            {credentials && credentials.length > 0 && (
              <Badge
                variant="outline"
                className="border-[color:var(--severity-ok)] text-[color:var(--severity-ok)]"
              >
                {credentials.length} registered
              </Badge>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          {!supported && (
            <p className="text-xs text-muted-foreground">
              Your browser does not support passkeys. Try a recent version of
              Chrome, Safari, Edge, or Firefox.
            </p>
          )}
          {supported && loading && (
            <p className="text-xs text-muted-foreground">Loading…</p>
          )}
          {supported && !loading && credentials && credentials.length === 0 && (
            <p className="text-xs text-muted-foreground">
              Add a passkey to sign in with Touch ID, Windows Hello, or a
              security key — no in-game code needed after enrollment.
            </p>
          )}
          {supported && credentials && credentials.length > 0 && (
            <ul className="flex flex-col gap-2">
              {credentials.map((c) => (
                <li
                  key={c.credentialId}
                  className="flex items-center justify-between gap-3 rounded-lg border border-border/60 bg-background/40 px-3 py-2"
                >
                  <div className="min-w-0">
                    <p className="truncate font-medium">{c.label}</p>
                    <p className="text-xs text-muted-foreground">
                      Added {formatRelative(c.createdAt)}
                      {c.lastUsedAt
                        ? ` · last used ${formatRelative(c.lastUsedAt)}`
                        : " · not yet used"}
                    </p>
                  </div>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={removingId === c.credentialId}
                    onClick={() => handleDelete(c.credentialId)}
                  >
                    {removingId === c.credentialId ? "Removing…" : "Remove"}
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <div>
            <Button
              size="sm"
              variant="secondary"
              disabled={!supported}
              onClick={() => {
                setLabel("");
                setEnrollOpen(true);
              }}
            >
              Add a passkey
            </Button>
          </div>
        </CardContent>
      </Card>

      <Dialog
        open={enrollOpen}
        onOpenChange={(open) => {
          if (!enrolling) setEnrollOpen(open);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Add a passkey</DialogTitle>
            <DialogDescription>
              Your browser will prompt for Touch ID, Windows Hello, or a
              security key. Pick a label so you can tell devices apart later.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="pk-label">Label</Label>
            <Input
              id="pk-label"
              autoFocus
              value={label}
              placeholder="e.g. MacBook Touch ID"
              onChange={(e) => setLabel(e.target.value)}
              maxLength={64}
            />
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setEnrollOpen(false)}
              disabled={enrolling}
            >
              Cancel
            </Button>
            <Button onClick={handleEnroll} disabled={enrolling}>
              {enrolling ? "Waiting for authenticator…" : "Continue"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
