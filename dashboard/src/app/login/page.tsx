import type { Metadata } from "next";
import { Suspense } from "react";
import { LoginPageClient } from "./login-client";

// `no-referrer` ensures the controller URL + magic-link token in `?link=` are
// never leaked to third parties via `Referer` on navigation or asset loads.
export const metadata: Metadata = {
  referrer: "no-referrer",
};

export default function Page() {
  return (
    <div className="relative flex min-h-svh w-full items-center justify-center overflow-hidden p-6 md:p-10">
      {/* Ambient background — layered radial gradients tinted with the banner
          palette. Pure CSS, theme-aware via `dark:` variants. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10
          bg-[radial-gradient(ellipse_80%_60%_at_50%_-10%,rgba(122,162,247,0.18),transparent_60%),radial-gradient(ellipse_50%_40%_at_10%_110%,rgba(125,207,255,0.14),transparent_60%),radial-gradient(ellipse_40%_30%_at_100%_100%,rgba(137,180,250,0.12),transparent_60%)]
          dark:bg-[radial-gradient(ellipse_80%_60%_at_50%_-10%,rgba(122,162,247,0.22),transparent_60%),radial-gradient(ellipse_50%_40%_at_10%_110%,rgba(125,207,255,0.18),transparent_60%),radial-gradient(ellipse_40%_30%_at_100%_100%,rgba(137,180,250,0.15),transparent_60%)]"
      />
      {/* Floating cloud accent — faint monochrome SVG, top-right. */}
      <svg
        aria-hidden
        viewBox="0 0 200 120"
        className="pointer-events-none absolute -right-10 -top-6 -z-10 h-40 w-64 text-primary/10 dark:text-primary/15"
        fill="currentColor"
      >
        <path d="M50 80c-16 0-28-12-28-26 0-13 10-24 24-26 4-14 17-24 32-24 18 0 32 13 34 30 2-1 5-1 8-1 14 0 26 11 26 25s-12 25-26 25H50z" />
      </svg>
      <div className="w-full max-w-sm">
        <Suspense fallback={null}>
          <LoginPageClient />
        </Suspense>
      </div>
    </div>
  );
}
