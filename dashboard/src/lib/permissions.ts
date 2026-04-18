/**
 * Permission-node registry for dashboard routes.
 *
 * Each top-level page maps to a `nimbus.dashboard.*` node. Keep in sync with
 * the backend's route guards (see Phase 2 `requirePermission()` calls).
 */
export const PERM = {
  OVERVIEW: "nimbus.dashboard.overview",
  SERVICES_VIEW: "nimbus.dashboard.services.view",
  SERVICES_CONSOLE: "nimbus.dashboard.services.console",
  GROUPS_VIEW: "nimbus.dashboard.groups.view",
  DEDICATED_VIEW: "nimbus.dashboard.dedicated.view",
  PLAYERS_VIEW: "nimbus.dashboard.players.view",
  PUNISHMENTS_VIEW: "nimbus.dashboard.punishments.view",
  RESOURCEPACKS_VIEW: "nimbus.dashboard.resourcepacks.view",
  BACKUPS_VIEW: "nimbus.dashboard.backups.view",
  PERMS_VIEW: "nimbus.dashboard.perms.view",
  DOCKER_VIEW: "nimbus.dashboard.docker.view",
  SCALING_VIEW: "nimbus.dashboard.scaling.view",
  NODES_VIEW: "nimbus.dashboard.nodes.view",
  AUDIT_VIEW: "nimbus.dashboard.audit.view",
  FILES_VIEW: "nimbus.dashboard.services.view",
  MAINTENANCE_TOGGLE: "nimbus.dashboard.maintenance.toggle",
  RELOAD: "nimbus.dashboard.reload",
  SHUTDOWN: "nimbus.dashboard.shutdown",
} as const;

/**
 * Route prefix → required permission. Longest prefix wins. Routes not listed
 * here are considered unrestricted (e.g. `/profile`, `/login`).
 */
export const ROUTE_PERMISSIONS: Array<[string, string]> = [
  ["/services", PERM.SERVICES_VIEW],
  ["/groups", PERM.GROUPS_VIEW],
  ["/dedicated", PERM.DEDICATED_VIEW],
  ["/files", PERM.FILES_VIEW],
  ["/nodes", PERM.NODES_VIEW],
  ["/console", PERM.SERVICES_CONSOLE],
  ["/stress", PERM.SERVICES_VIEW],
  ["/plugins", PERM.SERVICES_VIEW],
  ["/audit", PERM.AUDIT_VIEW],
  ["/doctor", PERM.OVERVIEW],
  ["/modules/perms", PERM.PERMS_VIEW],
  ["/modules/scaling", PERM.SCALING_VIEW],
  ["/modules/players", PERM.PLAYERS_VIEW],
  ["/modules/punishments", PERM.PUNISHMENTS_VIEW],
  ["/modules/resourcepacks", PERM.RESOURCEPACKS_VIEW],
  ["/modules/docker", PERM.DOCKER_VIEW],
  ["/modules/backup", PERM.BACKUPS_VIEW],
  ["/modules/syncproxy", PERM.OVERVIEW],
  ["/", PERM.OVERVIEW],
];

export function requiredPermissionFor(path: string): string | null {
  // Longest-prefix match, but anchor on "/" with exact equality so that "/"
  // doesn't shadow every other route.
  let best: [string, string] | null = null;
  for (const entry of ROUTE_PERMISSIONS) {
    const [prefix] = entry;
    if (prefix === "/") {
      if (path === "/") best = entry;
      continue;
    }
    if (path === prefix || path.startsWith(prefix + "/")) {
      if (!best || prefix.length > best[0].length) best = entry;
    }
  }
  return best?.[1] ?? null;
}
