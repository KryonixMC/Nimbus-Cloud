"use client"

import Image from "next/image"
import { NavDocuments } from "@/components/nav-documents"
import { NavMain } from "@/components/nav-main"
import { NavSecondary } from "@/components/nav-secondary"
import { NavUser } from "@/components/nav-user"
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar"
import {
  LayoutDashboardIcon,
  ServerIcon,
  FolderTreeIcon,
  NetworkIcon,
  TerminalIcon,
  Settings2Icon,
  PlugIcon,
  ZapIcon,
  ScrollTextIcon,
  ShieldIcon,
  UsersIcon,
  MonitorIcon,
  RadioIcon,
  BoxIcon,
  type LucideIcon,
} from "lucide-react"
import Link from "next/link"
import { useModules } from "@/lib/modules"

const navMain = [
  { title: "Overview", url: "/", icon: <LayoutDashboardIcon /> },
  { title: "Services", url: "/services", icon: <ServerIcon /> },
  { title: "Groups", url: "/groups", icon: <FolderTreeIcon /> },
  { title: "Nodes", url: "/nodes", icon: <NetworkIcon /> },
  { title: "Console", url: "/console", icon: <TerminalIcon /> },
  { title: "Plugins", url: "/plugins", icon: <PlugIcon /> },
  { title: "Stress Test", url: "/stress", icon: <ZapIcon /> },
  { title: "Audit Log", url: "/audit", icon: <ScrollTextIcon /> },
]

const navSecondary = [
  { title: "Settings", url: "/settings", icon: <Settings2Icon /> },
]

const coreModules = [
  { name: "Sync Proxy", url: "/modules/syncproxy", icon: <RadioIcon /> },
]

const iconMap: Record<string, LucideIcon> = {
  Shield: ShieldIcon,
  Users: UsersIcon,
  Monitor: MonitorIcon,
  Box: BoxIcon,
  Server: ServerIcon,
  Radio: RadioIcon,
}

export function AppSidebar({ ...props }: React.ComponentProps<typeof Sidebar>) {
  const { activeModules } = useModules()

  const moduleItems = [
    ...coreModules,
    ...activeModules.map((mod) => {
      const Icon = iconMap[mod.dashboard?.icon ?? "Box"] ?? BoxIcon
      return {
        name: mod.name,
        url: `/modules/${mod.id}`,
        icon: <Icon />,
      }
    }),
  ]

  return (
    <Sidebar collapsible="offcanvas" {...props}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              className="data-[slot=sidebar-menu-button]:p-1.5!"
              render={<Link href="/" />}
            >
              <Image src="/icon.png" alt="Nimbus" width={20} height={20} />
              <span className="text-base font-semibold">Nimbus</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <NavMain items={navMain} />
        <NavDocuments items={moduleItems} />
        <NavSecondary items={navSecondary} className="mt-auto" />
      </SidebarContent>
      <SidebarFooter>
        <NavUser />
      </SidebarFooter>
    </Sidebar>
  )
}
