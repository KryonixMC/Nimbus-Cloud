"use client";

import Link from "next/link";
import { useTheme } from "next-themes";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import {
  EllipsisVerticalIcon,
  LogOutIcon,
  Shield,
  UserCheck,
  Gauge,
} from "@/lib/icons";
import { Moon, Sun } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { getApiUrl, type UserInfo } from "@/lib/api";

/**
 * Derive a human-readable role label from the user's permission grants.
 *
 * We don't surface group/track info on the user object today, so this falls
 * back to a coarse category based on which dashboard permission namespaces
 * the user has been granted.
 */
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

export function NavUser() {
  const { isMobile } = useSidebar();
  const { state, logout, hasPermission } = useAuth();
  const { resolvedTheme, setTheme } = useTheme();

  if (state.kind === "api-token") {
    const apiUrl = typeof window !== "undefined" ? getApiUrl() : "";
    return (
      <SidebarMenu>
        <SidebarMenuItem>
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <SidebarMenuButton
                  size="lg"
                  className="aria-expanded:bg-muted"
                />
              }
            >
              {/* Command-block head — same asset used on the login-screen
                  API-token MethodCard so the two views stay visually tied. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src="https://mc-heads.net/avatar/eb6cee8fda7ef0b3ae0eb0579d5676ce36af7efc574d88728f3894f6b166538/32"
                alt=""
                width={32}
                height={32}
                className="size-8 rounded-lg"
              />
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-medium">API Admin</span>
                <span className="truncate text-xs text-foreground/70">
                  {apiUrl || "Not connected"}
                </span>
              </div>
              <EllipsisVerticalIcon className="ml-auto size-4" />
            </DropdownMenuTrigger>
            <DropdownMenuContent
              className="min-w-56"
              side={isMobile ? "bottom" : "right"}
              align="end"
              sideOffset={4}
            >
              <DropdownMenuGroup>
                <DropdownMenuLabel className="p-0 font-normal">
                  <div className="flex items-center gap-2 px-1 py-1.5 text-left text-sm">
                    <div className="grid flex-1 text-left text-sm leading-tight">
                      <span className="truncate font-medium">Connected to</span>
                      <span className="truncate text-xs text-muted-foreground">
                        {apiUrl}
                      </span>
                    </div>
                  </div>
                </DropdownMenuLabel>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={logout}>
                <LogOutIcon />
                Disconnect
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </SidebarMenuItem>
      </SidebarMenu>
    );
  }

  if (state.kind !== "user") return null;

  const user = state.user;
  const role = deriveRole(user, hasPermission);
  const isDark = resolvedTheme === "dark";

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <SidebarMenuButton size="lg" className="aria-expanded:bg-muted" />
            }
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={`https://mc-heads.net/avatar/${user.uuid}/32`}
              alt=""
              width={32}
              height={32}
              className="size-8 rounded-lg"
            />
            <div className="grid flex-1 text-left text-sm leading-tight">
              <span className="truncate font-medium">{user.name}</span>
              <span className="truncate text-xs text-foreground/70">
                {role}
              </span>
            </div>
            <EllipsisVerticalIcon className="ml-auto size-4" />
          </DropdownMenuTrigger>
          <DropdownMenuContent
            className="min-w-56"
            side={isMobile ? "bottom" : "right"}
            align="end"
            sideOffset={4}
          >
            <DropdownMenuGroup>
              <DropdownMenuLabel className="p-0 font-normal">
                <div className="flex items-center gap-2 px-1 py-1.5 text-left text-sm">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={`https://mc-heads.net/avatar/${user.uuid}/32`}
                    alt=""
                    width={32}
                    height={32}
                    className="size-8 rounded-lg"
                  />
                  <div className="grid flex-1 text-left text-sm leading-tight">
                    <span className="truncate font-medium">{user.name}</span>
                    <span className="truncate text-xs text-muted-foreground">
                      {role}
                    </span>
                  </div>
                </div>
              </DropdownMenuLabel>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem render={<Link href="/profile" />}>
              <UserCheck />
              Profile
            </DropdownMenuItem>
            <DropdownMenuItem render={<Link href="/profile/security" />}>
              <Shield />
              Security
            </DropdownMenuItem>
            <DropdownMenuItem render={<Link href="/profile/permissions" />}>
              <Gauge />
              Permissions
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              onClick={(e) => {
                e.preventDefault();
                setTheme(isDark ? "light" : "dark");
              }}
            >
              {isDark ? <Sun /> : <Moon />}
              Toggle theme
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={logout}>
              <LogOutIcon />
              Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}
