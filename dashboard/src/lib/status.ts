/** Shared status badge color classes for consistent styling across the dashboard. */

/** Service state colors (READY, STARTING, STOPPING, etc.) */
export const serviceStateColors: Record<string, string> = {
  READY: "bg-green-500/20 text-green-400 border-green-500/30",
  STARTING: "bg-yellow-500/20 text-yellow-400 border-yellow-500/30",
  STOPPING: "bg-orange-500/20 text-orange-400 border-orange-500/30",
  STOPPED: "bg-muted text-muted-foreground",
  PREPARING: "bg-blue-500/20 text-blue-400 border-blue-500/30",
  PREPARED: "bg-cyan-500/20 text-cyan-400 border-cyan-500/30",
};

export const statusColors = {
  online: "bg-green-500/20 text-green-400 border-green-500/30",
  active: "bg-yellow-500/20 text-yellow-400 border-yellow-500/30",
  maintenance: "bg-red-500/20 text-red-400 border-red-500/30",
  inactive: "bg-muted text-muted-foreground",
} as const;

/** Dot indicator for loaded/active state */
export const dotColors = {
  active: "bg-green-500",
  inactive: "bg-muted",
} as const;

/** Icon color for online/offline indicators */
export const iconColors = {
  online: "text-green-400",
  offline: "text-muted-foreground",
} as const;
