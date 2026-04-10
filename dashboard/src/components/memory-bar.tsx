import * as React from "react";
import { cn } from "@/lib/utils";

function formatMb(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${Math.round(mb)} MB`;
}

/**
 * Inline memory bar used in service tables and detail views. Shows a thin
 * progress track plus the used/max number, color-coded for pressure.
 * Used instead of TPS on the dashboard — memory is reliable for every
 * backend software, TPS isn't.
 */
export function MemoryBar({
  usedMb,
  maxMb,
  className,
}: {
  usedMb: number;
  maxMb: number;
  className?: string;
}) {
  const pct =
    maxMb > 0 ? Math.min(100, Math.round((usedMb / maxMb) * 100)) : 0;
  const tone =
    pct > 90
      ? "bg-destructive"
      : pct > 75
      ? "bg-yellow-500"
      : "bg-primary";
  return (
    <div className={cn("flex min-w-32 flex-col gap-1", className)}>
      <div className="flex items-center justify-between text-[10px] tabular-nums text-muted-foreground">
        <span>{formatMb(usedMb)}</span>
        <span>{formatMb(maxMb)}</span>
      </div>
      <div className="h-1.5 overflow-hidden rounded-full bg-muted">
        <div
          className={cn("h-full transition-all", tone)}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
