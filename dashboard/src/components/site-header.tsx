"use client"

import React from "react"
import { usePathname } from "next/navigation"
import Link from "next/link"
import { Separator } from "@/components/ui/separator"
import { SidebarTrigger } from "@/components/ui/sidebar"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"

const routeLabels: Record<string, string> = {
  "": "Overview",
  services: "Services",
  groups: "Groups",
  nodes: "Nodes",
  console: "Console",
  plugins: "Plugins",
  stress: "Stress Test",
  audit: "Audit Log",
  settings: "Settings",
  modules: "Modules",
  syncproxy: "Sync Proxy",
  perms: "Permissions",
  players: "Players",
  display: "Display",
}

export function SiteHeader() {
  const pathname = usePathname()
  const segments = pathname.split("/").filter(Boolean)

  const crumbs: { label: string; href: string }[] = []
  let path = ""
  for (const seg of segments) {
    path += `/${seg}`
    crumbs.push({
      label: routeLabels[seg] ?? decodeURIComponent(seg),
      href: path,
    })
  }

  return (
    <header className="flex h-(--header-height) shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-(--header-height)">
      <div className="flex w-full items-center gap-1 px-4 lg:gap-2 lg:px-6">
        <SidebarTrigger className="-ml-1" />
        <Separator
          orientation="vertical"
          className="mx-2 h-4 data-vertical:self-auto"
        />
        <Breadcrumb>
          <BreadcrumbList>
            {crumbs.length === 0 ? (
              <BreadcrumbItem>
                <BreadcrumbPage>Overview</BreadcrumbPage>
              </BreadcrumbItem>
            ) : (
              crumbs.map((crumb, i) => {
                const isLast = i === crumbs.length - 1
                return (
                  <React.Fragment key={crumb.href}>
                    {i > 0 && <BreadcrumbSeparator />}
                    <BreadcrumbItem>
                      {isLast ? (
                        <BreadcrumbPage>{crumb.label}</BreadcrumbPage>
                      ) : (
                        <BreadcrumbLink render={<Link href={crumb.href} />}>
                          {crumb.label}
                        </BreadcrumbLink>
                      )}
                    </BreadcrumbItem>
                  </React.Fragment>
                )
              })
            )}
          </BreadcrumbList>
        </Breadcrumb>
      </div>
    </header>
  )
}
