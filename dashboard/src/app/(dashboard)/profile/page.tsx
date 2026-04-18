"use client";

import { useTheme } from "next-themes";
import { Moon, Sun } from "lucide-react";
import { PageShell } from "@/components/page-shell";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth";
import type { UserInfo } from "@/lib/api";
import { CheckCircle2, CircleAlert } from "@/lib/icons";

function deriveRole(
  user: UserInfo,
  hasPermission: (node: string) => boolean
): string {
  if (user.isAdmin) return "Admin";
  if (
    hasPermission("nimbus.dashboard.services.view") ||
    hasPermission("nimbus.dashboard.groups.view")
  ) {
    return "Developer";
  }
  if (
    hasPermission("nimbus.dashboard.punishments.ban") ||
    hasPermission("nimbus.dashboard.punishments.tempban") ||
    hasPermission("nimbus.dashboard.punishments.revoke")
  ) {
    return "Moderator";
  }
  if (hasPermission("nimbus.dashboard.punishments.view")) {
    return "Supporter";
  }
  return "User";
}

export default function ProfileOverviewPage() {
  const { state, hasPermission } = useAuth();
  const { resolvedTheme, setTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  if (state.kind === "api-token") {
    return (
      <PageShell title="Profile" description="Account overview.">
        <Card>
          <CardContent className="py-6 text-sm text-muted-foreground">
            Profile features are only available for Minecraft-account sessions.
            Log in with{" "}
            <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">
              /dashboard login
            </code>{" "}
            on a Nimbus server to access your profile.
          </CardContent>
        </Card>
      </PageShell>
    );
  }

  if (state.kind !== "user") return null;

  const user = state.user;
  const role = deriveRole(user, hasPermission);

  return (
    <PageShell title="Profile" description="Your Nimbus account overview.">
      <div className="grid gap-4 md:grid-cols-[auto_1fr]">
        <Card className="md:w-80">
          <CardContent className="flex flex-col items-center gap-3 py-4 text-center">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={`https://mc-heads.net/avatar/${user.uuid}/128`}
              alt=""
              width={96}
              height={96}
              className="size-24 rounded-2xl"
            />
            <div className="flex flex-col items-center gap-1">
              <span className="font-heading text-lg font-medium">
                {user.name}
              </span>
              <Badge variant="secondary">{role}</Badge>
            </div>
            <code className="break-all text-xs text-muted-foreground">
              {user.uuid}
            </code>
          </CardContent>
        </Card>

        <div className="flex flex-col gap-4">
          <Card>
            <CardHeader>
              <CardTitle>Account</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3 text-sm">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Admin</span>
                {user.isAdmin ? (
                  <span className="inline-flex items-center gap-1.5 font-medium text-[color:var(--severity-ok)]">
                    <CheckCircle2 className="size-4" />
                    Yes
                  </span>
                ) : (
                  <span className="text-muted-foreground">No</span>
                )}
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Permissions</span>
                <span className="font-medium">
                  {user.permissions.length} grant
                  {user.permissions.length === 1 ? "" : "s"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Two-factor auth</span>
                {user.totpEnabled ? (
                  <span className="inline-flex items-center gap-1.5 font-medium text-[color:var(--severity-ok)]">
                    <CheckCircle2 className="size-4" />
                    Enabled
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 font-medium text-[color:var(--severity-warn)]">
                    <CircleAlert className="size-4" />
                    Disabled
                  </span>
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Appearance</CardTitle>
            </CardHeader>
            <CardContent>
              <Button
                variant="outline"
                onClick={() => setTheme(isDark ? "light" : "dark")}
              >
                {isDark ? (
                  <>
                    <Sun className="size-4" /> Switch to light
                  </>
                ) : (
                  <>
                    <Moon className="size-4" /> Switch to dark
                  </>
                )}
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    </PageShell>
  );
}
