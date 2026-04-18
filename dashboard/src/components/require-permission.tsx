"use client";

import type { ReactNode } from "react";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Renders children only if the current auth principal holds [node].
 * Shows [fallback] otherwise (default: an empty-state "No permission" card).
 */
export function RequirePermission({
  node,
  children,
  fallback,
}: {
  node: string;
  children: ReactNode;
  fallback?: ReactNode;
}) {
  const { hasPermission, state } = useAuth();
  if (state.kind === "loading") return null;
  if (!hasPermission(node)) {
    return (
      fallback ?? (
        <div className="flex min-h-[40vh] w-full items-center justify-center p-6">
          <div className="max-w-md space-y-2 rounded-lg border border-dashed p-6 text-center">
            <p className="text-sm font-medium">No permission</p>
            <p className="text-xs text-muted-foreground">
              You are missing <code className="font-mono">{node}</code>.
            </p>
          </div>
        </div>
      )
    );
  }
  return <>{children}</>;
}

/** Silent show/hide variant — renders nothing if the permission is missing. */
export function IfPermission({
  node,
  children,
}: {
  node: string;
  children: ReactNode;
}) {
  const { hasPermission, state } = useAuth();
  if (state.kind === "loading") return null;
  if (!hasPermission(node)) return null;
  return <>{children}</>;
}

/**
 * Button that stays rendered but is disabled + tooltip-annotated when the
 * user lacks [node]. Keeps the UI predictable (no layout shift from hidden
 * controls) while communicating *why* an action is unavailable.
 */
export function PermissionButton({
  node,
  children,
  className,
  disabled,
  title,
  ...props
}: React.ComponentProps<typeof Button> & { node: string }) {
  const { hasPermission } = useAuth();
  const allowed = hasPermission(node);
  const effectiveTitle = allowed ? title : `Requires ${node}`;
  return (
    <Button
      {...props}
      disabled={disabled || !allowed}
      title={effectiveTitle}
      className={cn(className)}
    >
      {children}
    </Button>
  );
}
