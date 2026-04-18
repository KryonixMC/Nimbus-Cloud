"use client";

import { AppSidebar } from "@/components/app-sidebar";
import { SiteHeader } from "@/components/site-header";
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar";
import { AuthProvider } from "@/lib/auth";
import { ModulesProvider } from "@/lib/modules";
import { ClusterEventToaster } from "@/components/cluster-event-toaster";
import { RoutePermissionGuard } from "@/components/route-permission-guard";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthProvider>
      <ModulesProvider>
        <SidebarProvider
          style={
            {
              "--sidebar-width": "calc(var(--spacing) * 72)",
              "--header-height": "calc(var(--spacing) * 12)",
            } as React.CSSProperties
          }
        >
          <ClusterEventToaster />
          <AppSidebar variant="inset" />
          <SidebarInset>
            <SiteHeader />
            <div className="flex flex-1 flex-col">
              <div className="@container/main flex flex-1 flex-col gap-2">
                <div className="flex flex-col gap-4 py-4 md:gap-6 md:py-6 px-4 lg:px-6">
                  <RoutePermissionGuard>{children}</RoutePermissionGuard>
                </div>
              </div>
            </div>
          </SidebarInset>
        </SidebarProvider>
      </ModulesProvider>
    </AuthProvider>
  );
}
