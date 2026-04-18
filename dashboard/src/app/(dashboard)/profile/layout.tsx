"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { PageShell } from "@/components/page-shell";

const TABS = [
  { href: "/profile", label: "Overview" },
  { href: "/profile/security", label: "Security" },
  { href: "/profile/permissions", label: "Permissions" },
] as const;

export default function ProfileLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const pathname = usePathname();

  return (
    <PageShell
      title="Profile"
      description="Manage your account, sessions, and security settings."
    >
      <div className="flex flex-col gap-4">
        <nav
          aria-label="Profile sections"
          className="inline-flex w-fit items-center gap-1 rounded-full bg-muted p-1 text-muted-foreground"
        >
          {TABS.map((tab) => {
            const active =
              tab.href === "/profile"
                ? pathname === "/profile"
                : pathname.startsWith(tab.href);
            return (
              <Link
                key={tab.href}
                href={tab.href}
                className={cn(
                  "inline-flex h-8 items-center justify-center rounded-full px-4 text-sm font-medium transition-colors",
                  active
                    ? "bg-background text-foreground shadow-sm"
                    : "hover:text-foreground"
                )}
              >
                {tab.label}
              </Link>
            );
          })}
        </nav>
        {children}
      </div>
    </PageShell>
  );
}
