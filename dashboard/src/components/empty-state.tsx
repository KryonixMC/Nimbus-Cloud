import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Canonical empty state. Use for "no groups configured", "no players
 * tracked yet", etc. Always icon + title + (optional) description + (optional) action,
 * always centered, always the same vertical padding.
 */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon?: React.ComponentType<{ className?: string }>;
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center rounded-lg border border-dashed bg-muted/30 px-6 py-12 text-center",
        className
      )}
    >
      {Icon && (
        <div className="mb-3 flex size-11 items-center justify-center rounded-full bg-background ring-1 ring-border">
          <Icon className="size-5 text-muted-foreground" />
        </div>
      )}
      <p className="text-sm font-medium text-foreground">{title}</p>
      {description && (
        <p className="mt-1 max-w-sm text-xs text-muted-foreground">
          {description}
        </p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
