"use client";

import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { requiredPermissionFor } from "@/lib/permissions";
import type { ReactNode } from "react";

/**
 * Route-level permission gate. Wraps the dashboard children and, for the
 * current pathname, looks up the matching permission node and blocks the
 * render if the caller lacks it.
 *
 * We intentionally render a "not found" shell rather than a 403 to avoid
 * leaking page existence via error copy — matches the plan's §5.3 note.
 * API-token auth always passes because `hasPermission` short-circuits true.
 */
export function RoutePermissionGuard({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { hasPermission, state } = useAuth();

  if (state.kind === "loading") return null;

  const required = requiredPermissionFor(pathname);
  if (required && !hasPermission(required)) {
    return (
      <div className="flex min-h-[60vh] w-full items-center justify-center">
        <div className="max-w-md space-y-2 rounded-lg border border-dashed p-8 text-center">
          <p className="text-base font-medium">Page not found</p>
          <p className="text-xs text-muted-foreground">
            This page doesn&apos;t exist or you don&apos;t have access.
          </p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
