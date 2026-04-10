import * as React from "react";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

/**
 * Compact stat tile used in overview grids. One canonical shape so that
 * every page showing "Status / Players / Services / Uptime" (or similar)
 * looks identical.
 */
export function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  tone = "default",
  className,
}: {
  label: React.ReactNode;
  value: React.ReactNode;
  hint?: React.ReactNode;
  icon?: React.ComponentType<{ className?: string }>;
  tone?: "default" | "primary" | "destructive";
  className?: string;
}) {
  return (
    <Card className={cn("overflow-hidden", className)}>
      <CardContent className="p-5">
        <div className="flex items-center justify-between gap-3">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {label}
          </div>
          {Icon && (
            <Icon
              className={cn(
                "size-4",
                tone === "primary"
                  ? "text-primary"
                  : tone === "destructive"
                  ? "text-destructive"
                  : "text-muted-foreground"
              )}
            />
          )}
        </div>
        <div
          className={cn(
            "mt-2 text-2xl font-semibold tracking-tight",
            tone === "primary" && "text-primary",
            tone === "destructive" && "text-destructive"
          )}
        >
          {value}
        </div>
        {hint && (
          <div className="mt-1 text-xs text-muted-foreground">{hint}</div>
        )}
      </CardContent>
    </Card>
  );
}
