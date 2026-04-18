"use client";

import { useState } from "react";
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
import { useApiResource, POLL } from "@/hooks/use-api-resource";
import { apiFetch } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { CheckCircle2, CircleAlert, Shield } from "@/lib/icons";
import { PasskeyCard } from "@/components/passkey-card";

interface TotpStatus {
  enabled: boolean;
  pendingEnrollment: boolean;
  recoveryCodesRemaining: number;
}

interface TotpEnrollResponse {
  secret: string;
  otpauthUri: string;
  recoveryCodes: string[];
}

interface SessionSummaryDto {
  sessionId: string;
  name: string;
  createdAt: number;
  expiresAt: number;
  lastUsedAt: number;
  ip: string | null;
  userAgent: string | null;
  loginMethod: string;
}

interface MySessionsResponse {
  sessions: SessionSummaryDto[];
  currentSessionId: string;
}

function qrSrc(uri: string): string {
  return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(uri)}`;
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

function formatAbsolute(ts: number): string {
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return String(ts);
  }
}

function shortUserAgent(ua: string | null): string {
  if (!ua) return "unknown client";
  return ua.length > 50 ? `${ua.slice(0, 50)}…` : ua;
}

function loginMethodLabel(m: string): string {
  switch (m) {
    case "code":
      return "code";
    case "magic_link":
      return "magic link";
    case "totp":
      return "totp";
    default:
      return m;
  }
}

export default function ProfileSecurityPage() {
  const { state, refresh } = useAuth();

  const {
    data: status,
    loading,
    error,
    refetch,
  } = useApiResource<TotpStatus>(
    state.kind === "user" ? "/api/profile/totp/status" : null,
    { silent: true }
  );

  const {
    data: sessionsData,
    loading: sessionsLoading,
    error: sessionsError,
    refetch: refetchSessions,
  } = useApiResource<MySessionsResponse>(
    state.kind === "user" ? "/api/auth/my-sessions" : null,
    { poll: POLL.slow, silent: true }
  );

  const [enrollment, setEnrollment] = useState<TotpEnrollResponse | null>(null);
  const [confirmCode, setConfirmCode] = useState("");
  const [confirming, setConfirming] = useState(false);

  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState("");
  const [disabling, setDisabling] = useState(false);

  const [enrolling, setEnrolling] = useState(false);

  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [revokingOthers, setRevokingOthers] = useState(false);

  if (state.kind === "api-token") {
    return (
      <Card>
        <CardContent className="py-6 text-sm text-muted-foreground">
          Profile features are only available for Minecraft-account sessions.
          Log in with{" "}
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">
            /dashboard login
          </code>{" "}
          on a Nimbus server to access 2FA.
        </CardContent>
      </Card>
    );
  }

  if (state.kind !== "user") return null;

  const startEnroll = async () => {
    setEnrolling(true);
    try {
      const res = await apiFetch<TotpEnrollResponse>(
        "/api/profile/totp/enroll",
        { method: "POST" }
      );
      setEnrollment(res);
      setConfirmCode("");
    } catch {
      // apiFetch surfaces toast
    } finally {
      setEnrolling(false);
    }
  };

  const confirmEnroll = async () => {
    if (!/^\d{6}$/.test(confirmCode)) {
      toast.error("Enter the 6-digit code from your authenticator app.");
      return;
    }
    setConfirming(true);
    try {
      await apiFetch("/api/profile/totp/confirm", {
        method: "POST",
        body: JSON.stringify({ code: confirmCode }),
      });
      toast.success("Two-factor authentication enabled");
      setEnrollment(null);
      setConfirmCode("");
      await refetch();
      await refresh();
    } catch {
      // apiFetch surfaces toast
    } finally {
      setConfirming(false);
    }
  };

  const disableTotp = async () => {
    if (!disableCode.trim()) {
      toast.error("Enter a TOTP code or a recovery code.");
      return;
    }
    setDisabling(true);
    try {
      await apiFetch("/api/profile/totp/disable", {
        method: "POST",
        body: JSON.stringify({ code: disableCode.trim() }),
      });
      toast.success("Two-factor authentication disabled");
      setDisableOpen(false);
      setDisableCode("");
      await refetch();
      await refresh();
    } catch {
      // apiFetch surfaces toast
    } finally {
      setDisabling(false);
    }
  };

  const copy = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${label} copied`);
    } catch {
      toast.error("Clipboard copy failed");
    }
  };

  const revokeSession = async (sessionId: string) => {
    setRevokingId(sessionId);
    try {
      await apiFetch(`/api/auth/my-sessions/${sessionId}`, {
        method: "DELETE",
      });
      toast.success("Session revoked");
      await refetchSessions();
    } catch {
      // apiFetch surfaces toast
    } finally {
      setRevokingId(null);
    }
  };

  const revokeOthers = async () => {
    setRevokingOthers(true);
    try {
      const res = await apiFetch<{ revoked: number }>(
        "/api/auth/my-sessions/revoke-others",
        { method: "POST" }
      );
      toast.success(`Revoked ${res.revoked} other session${res.revoked === 1 ? "" : "s"}`);
      await refetchSessions();
    } catch {
      // apiFetch surfaces toast
    } finally {
      setRevokingOthers(false);
    }
  };

  const renderTotpCard = () => {
    if (loading) {
      return (
        <Card>
          <CardContent className="py-6 text-sm text-muted-foreground">
            Loading 2FA status…
          </CardContent>
        </Card>
      );
    }
    if (error || !status) {
      return (
        <Card>
          <CardContent className="py-6 text-sm text-[color:var(--severity-err)]">
            Failed to load 2FA status.{" "}
            <button
              className="underline underline-offset-4"
              onClick={() => refetch()}
            >
              Retry
            </button>
          </CardContent>
        </Card>
      );
    }

    if (status.enabled) {
      return (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Shield className="size-4 text-[color:var(--severity-ok)]" />
              Two-factor authentication
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4 text-sm">
            <div className="inline-flex w-fit items-center gap-2 rounded-full bg-[color:var(--severity-ok)]/10 px-3 py-1 text-xs font-medium text-[color:var(--severity-ok)]">
              <CheckCircle2 className="size-3.5" />
              Enabled
            </div>
            <p className="text-muted-foreground">
              Recovery codes remaining:{" "}
              <span className="font-medium text-foreground">
                {status.recoveryCodesRemaining}
              </span>
            </p>
            <div>
              <Button
                variant="destructive"
                onClick={() => setDisableOpen(true)}
              >
                Disable 2FA
              </Button>
            </div>
          </CardContent>
        </Card>
      );
    }

    if (status.pendingEnrollment && !enrollment) {
      return (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CircleAlert className="size-4 text-[color:var(--severity-warn)]" />
              Finish pending 2FA setup
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4 text-sm">
            <p className="text-muted-foreground">
              A 2FA enrollment was started but never confirmed. Enter the code
              from your authenticator, or start over to generate a fresh secret
              and recovery codes.
            </p>
            <div className="flex flex-col gap-2 sm:max-w-sm">
              <Label htmlFor="pending-confirm">6-digit code</Label>
              <Input
                id="pending-confirm"
                inputMode="numeric"
                maxLength={6}
                value={confirmCode}
                onChange={(e) =>
                  setConfirmCode(e.target.value.replace(/\D/g, "").slice(0, 6))
                }
                placeholder="123456"
              />
            </div>
            <div className="flex flex-wrap gap-2">
              <Button onClick={confirmEnroll} disabled={confirming}>
                {confirming ? "Confirming…" : "Confirm"}
              </Button>
              <Button
                variant="outline"
                onClick={startEnroll}
                disabled={enrolling}
              >
                {enrolling ? "Generating…" : "Start over"}
              </Button>
            </div>
          </CardContent>
        </Card>
      );
    }

    // Not enabled, not pending — offer to enable.
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Shield className="size-4" />
            Two-factor authentication
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 text-sm">
          <div className="inline-flex w-fit items-center gap-2 rounded-full bg-[color:var(--severity-warn)]/10 px-3 py-1 text-xs font-medium text-[color:var(--severity-warn)]">
            <CircleAlert className="size-3.5" />
            Disabled
          </div>
          <p className="text-muted-foreground">
            Protect your account by requiring a time-based one-time code on
            every dashboard login.
          </p>
          <div>
            <Button onClick={startEnroll} disabled={enrolling}>
              {enrolling ? "Generating…" : "Enable 2FA"}
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  };

  const renderSessionsCard = () => {
    const hasOthers =
      sessionsData !== null &&
      sessionsData.sessions.some(
        (s) => s.sessionId !== sessionsData.currentSessionId
      );

    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center justify-between gap-2">
            <span>Sessions</span>
            {hasOthers && (
              <Button
                variant="outline"
                size="sm"
                onClick={revokeOthers}
                disabled={revokingOthers}
              >
                {revokingOthers ? "Signing out…" : "Sign out everywhere else"}
              </Button>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          {sessionsLoading && !sessionsData && (
            <p className="text-muted-foreground">Loading sessions…</p>
          )}
          {sessionsError && !sessionsData && (
            <p className="text-[color:var(--severity-err)]">
              Failed to load sessions.{" "}
              <button
                className="underline underline-offset-4"
                onClick={() => refetchSessions()}
              >
                Retry
              </button>
            </p>
          )}
          {sessionsData && sessionsData.sessions.length === 0 && (
            <p className="text-muted-foreground">No active sessions.</p>
          )}
          {sessionsData &&
            sessionsData.sessions.map((s) => {
              const isCurrent = s.sessionId === sessionsData.currentSessionId;
              return (
                <div
                  key={s.sessionId}
                  className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-start sm:justify-between"
                >
                  <div className="flex min-w-0 flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{s.name}</span>
                      {isCurrent && (
                        <span className="text-xs text-[color:var(--severity-ok)]">
                          (this session)
                        </span>
                      )}
                      <Badge variant="secondary">
                        {loginMethodLabel(s.loginMethod)}
                      </Badge>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {s.ip ?? "unknown"} · {shortUserAgent(s.userAgent)}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Last used: {formatRelative(s.lastUsedAt)} · Expires:{" "}
                      {formatAbsolute(s.expiresAt)}
                    </div>
                  </div>
                  {!isCurrent && (
                    <div className="shrink-0">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => revokeSession(s.sessionId)}
                        disabled={revokingId === s.sessionId}
                      >
                        {revokingId === s.sessionId ? "Revoking…" : "Revoke"}
                      </Button>
                    </div>
                  )}
                </div>
              );
            })}
        </CardContent>
      </Card>
    );
  };

  return (
    <>
      <div className="flex flex-col gap-4">
        {renderTotpCard()}
        <PasskeyCard />
        {renderSessionsCard()}
      </div>

      {/* Enrollment modal */}
      <Dialog
        open={enrollment !== null}
        onOpenChange={(open) => {
          if (!open) {
            setEnrollment(null);
            setConfirmCode("");
          }
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Set up two-factor authentication</DialogTitle>
            <DialogDescription>
              Scan the QR code with your authenticator app, then enter the
              6-digit code to finish.
            </DialogDescription>
          </DialogHeader>

          {enrollment && (
            <div className="flex flex-col gap-4 text-sm">
              <div className="flex justify-center">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={qrSrc(enrollment.otpauthUri)}
                  alt="TOTP QR code"
                  width={220}
                  height={220}
                  className="rounded-xl bg-white p-2"
                />
              </div>

              <div className="flex flex-col gap-1.5">
                <Label>Secret (base32)</Label>
                <div className="flex items-center gap-2">
                  <code className="flex-1 overflow-x-auto rounded-md bg-muted px-3 py-2 font-mono text-xs">
                    {enrollment.secret}
                  </code>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => copy(enrollment.secret, "Secret")}
                  >
                    Copy
                  </Button>
                </div>
              </div>

              <div className="flex flex-col gap-1.5">
                <div className="flex items-center justify-between">
                  <Label>Recovery codes</Label>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() =>
                      copy(
                        enrollment.recoveryCodes.join("\n"),
                        "Recovery codes"
                      )
                    }
                  >
                    Copy all
                  </Button>
                </div>
                <div className="grid grid-cols-2 gap-2 rounded-md bg-muted p-3 font-mono text-xs">
                  {enrollment.recoveryCodes.map((c) => (
                    <code key={c}>{c}</code>
                  ))}
                </div>
                <p className="text-xs text-[color:var(--severity-warn)]">
                  Save these now — they will NOT be shown again. Each code
                  works exactly once.
                </p>
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="enroll-confirm">6-digit code</Label>
                <Input
                  id="enroll-confirm"
                  inputMode="numeric"
                  maxLength={6}
                  value={confirmCode}
                  onChange={(e) =>
                    setConfirmCode(
                      e.target.value.replace(/\D/g, "").slice(0, 6)
                    )
                  }
                  placeholder="123456"
                />
              </div>
            </div>
          )}

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setEnrollment(null);
                setConfirmCode("");
              }}
            >
              Cancel
            </Button>
            <Button onClick={confirmEnroll} disabled={confirming}>
              {confirming ? "Confirming…" : "Confirm"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Disable modal */}
      <Dialog
        open={disableOpen}
        onOpenChange={(open) => {
          setDisableOpen(open);
          if (!open) setDisableCode("");
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Disable two-factor authentication</DialogTitle>
            <DialogDescription>
              Enter a current TOTP code or one of your recovery codes to
              confirm.
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1.5 text-sm">
            <Label htmlFor="disable-code">Code</Label>
            <Input
              id="disable-code"
              value={disableCode}
              onChange={(e) => setDisableCode(e.target.value)}
              placeholder="123456 or recovery code"
              autoComplete="one-time-code"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDisableOpen(false)}>
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={disableTotp}
              disabled={disabling}
            >
              {disabling ? "Disabling…" : "Disable 2FA"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
