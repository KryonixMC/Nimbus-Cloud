"use client";

import { useState } from "react";
import { toast } from "sonner";
import { PageShell } from "@/components/page-shell";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
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
import { useApiResource } from "@/hooks/use-api-resource";
import { apiFetch } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { CheckCircle2, CircleAlert, Shield } from "@/lib/icons";

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

function qrSrc(uri: string): string {
  return `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(uri)}`;
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

  const [enrollment, setEnrollment] = useState<TotpEnrollResponse | null>(null);
  const [confirmCode, setConfirmCode] = useState("");
  const [confirming, setConfirming] = useState(false);

  const [disableOpen, setDisableOpen] = useState(false);
  const [disableCode, setDisableCode] = useState("");
  const [disabling, setDisabling] = useState(false);

  const [enrolling, setEnrolling] = useState(false);

  if (state.kind === "api-token") {
    return (
      <PageShell title="Security" description="Two-factor authentication.">
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
      </PageShell>
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

  return (
    <PageShell
      title="Security"
      description="Two-factor authentication and active sessions."
    >
      <div className="flex flex-col gap-4">
        {renderTotpCard()}

        <Card>
          <CardHeader>
            <CardTitle>Sessions</CardTitle>
          </CardHeader>
          <CardContent className="py-2 text-sm text-muted-foreground">
            Session management coming soon — you will be able to review and
            revoke active dashboard sessions from here.
          </CardContent>
        </Card>
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
    </PageShell>
  );
}
