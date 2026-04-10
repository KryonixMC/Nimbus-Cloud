import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Uppercase label used to separate sections inside sheets, dialogs and
 * dense config panels. Matches the style of the Controller Info sheet
 * ("CHANGELOG", "UPTIME", ...) so that all sheet sections look identical.
 */
export function SectionLabel({
  children,
  className,
  right,
}: {
  children: React.ReactNode;
  className?: string;
  right?: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "flex items-center justify-between gap-2 border-b pb-1.5",
        className
      )}
    >
      <span className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {children}
      </span>
      {right && <div className="shrink-0">{right}</div>}
    </div>
  );
}
