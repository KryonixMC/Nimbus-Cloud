"use client"

import Image from "next/image"
import { NavDocuments } from "@/components/nav-documents"
import { NavMain } from "@/components/nav-main"
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
  Archive,
  LayoutDashboardIcon,
  ServerIcon,
  FolderTreeIcon,
  NetworkIcon,
  TerminalIcon,
  PlugIcon,
  ZapIcon,
  ScrollTextIcon,
  ShieldIcon,
  StethoscopeIcon,
  UsersIcon,
  MonitorIcon,
  RadioIcon,
  BoxIcon,
  GavelIcon,
  HardDrive,
  PackageIcon,
  type LucideIcon,
} from "@/lib/icons"
import Link from "next/link"
import { useModules } from "@/lib/modules"

const navOverview = [
  { title: "Dashboard", url: "/", icon: <LayoutDashboardIcon /> },
]

const navInfrastructure = [
  { title: "Services", url: "/services", icon: <ServerIcon /> },
  { title: "Groups", url: "/groups", icon: <FolderTreeIcon /> },
  { title: "Dedicated", url: "/dedicated", icon: <BoxIcon /> },
  { title: "Nodes", url: "/nodes", icon: <NetworkIcon /> },
]

const navOperations = [
  { title: "Console", url: "/console", icon: <TerminalIcon /> },
  { title: "Plugins", url: "/plugins", icon: <PlugIcon /> },
  { title: "Stress Test", url: "/stress", icon: <ZapIcon /> },
]

const navMonitoring = [
  { title: "Doctor", url: "/doctor", icon: <StethoscopeIcon /> },
  { title: "Audit Log", url: "/audit", icon: <ScrollTextIcon /> },
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
  Gavel: GavelIcon,
  Package: PackageIcon,
  Archive: Archive,
  HardDrive: HardDrive,
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
        <NavMain label="Overview" items={navOverview} />
        <NavMain label="Infrastructure" items={navInfrastructure} />
        <NavMain label="Operations" items={navOperations} />
        <NavMain label="Monitoring" items={navMonitoring} />
        <NavDocuments items={moduleItems} />
      </SidebarContent>
      <SidebarFooter>
        <NavUser />
      </SidebarFooter>
    </Sidebar>
  )
}
