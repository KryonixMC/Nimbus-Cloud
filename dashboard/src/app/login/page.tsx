import type { Metadata } from "next";
import { Suspense } from "react";
import { LoginPageClient } from "./login-client";
import { PixelSky } from "@/components/pixel-sky";

// `no-referrer` ensures the controller URL + magic-link token in `?link=` are
// never leaked to third parties via `Referer` on navigation or asset loads.
export const metadata: Metadata = {
  referrer: "no-referrer",
};

export default function Page() {
  return (
    <div className="relative flex min-h-svh w-full items-center justify-center overflow-hidden p-6 md:p-10">
      {/* Animated Minecraft pixel-sky background — replaces the earlier
          radial-gradient + cloud SVG. Clouds drift, stars twinkle, all
          pure SVG + CSS. */}
      <PixelSky />
      <div className="w-full max-w-sm">
        <Suspense fallback={null}>
          <LoginPageClient />
        </Suspense>
      </div>
    </div>
  );
}
