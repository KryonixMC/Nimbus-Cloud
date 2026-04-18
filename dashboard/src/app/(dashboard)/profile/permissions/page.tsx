"use client";

import { useMemo } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/lib/auth";
import { Shield, CheckCircle2 } from "@/lib/icons";

/**
 * Group a flat permission list by the second dotted segment — e.g. every
 * `nimbus.dashboard.services.*` lands in the "services" bucket. Keeps the
 * UI scannable when a user has dozens of grants.
 */
function groupPermissions(nodes: string[]): Record<string, string[]> {
  const groups: Record<string, string[]> = {};
  for (const n of nodes) {
    const segments = n.split(".");
    const key = segments.length >= 3 ? segments[2] : segments[0] ?? "other";
    (groups[key] ??= []).push(n);
  }
  for (const k of Object.keys(groups)) groups[k].sort();
  return groups;
}

export default function ProfilePermissionsPage() {
  const { state } = useAuth();

  const grouped = useMemo(() => {
    if (state.kind !== "user") return {};
    return groupPermissions([...state.user.permissions].sort());
  }, [state]);

  if (state.kind === "api-token") {
    return (
      <Card>
        <CardContent className="py-6 text-sm text-muted-foreground">
          Profile features are only available for Minecraft-account sessions.
          Log in with{" "}
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">
            /dashboard login
          </code>{" "}
          on a Nimbus server to review your permissions.
        </CardContent>
      </Card>
    );
  }

  if (state.kind !== "user") return null;

  const user = state.user;
  const groupKeys = Object.keys(grouped).sort();

  return (
    <div className="flex flex-col gap-4">
      {user.isAdmin && (
        <Card>
          <CardContent className="flex items-center gap-3 py-4">
            <Shield className="size-5 text-[color:var(--severity-ok)]" />
            <div className="flex flex-col">
              <span className="font-medium">Admin</span>
              <span className="text-xs text-muted-foreground">
                You have full access to every dashboard feature. Permission
                checks always succeed.
              </span>
            </div>
            <CheckCircle2 className="ml-auto size-5 text-[color:var(--severity-ok)]" />
          </CardContent>
        </Card>
      )}

      {groupKeys.length === 0 ? (
        <Card>
          <CardContent className="py-6 text-sm text-muted-foreground">
            No explicit permission grants.
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {groupKeys.map((key) => (
            <Card key={key}>
              <CardHeader>
                <CardTitle className="flex items-center justify-between">
                  <span className="capitalize">{key}</span>
                  <Badge variant="outline">{grouped[key].length}</Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-1.5">
                {grouped[key].map((node) => (
                  <code
                    key={node}
                    className="rounded-md bg-muted px-2 py-1 font-mono text-xs break-all"
                  >
                    {node}
                  </code>
                ))}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
