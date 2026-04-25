"use client";

import { useTheme } from "next-themes";
import { useEffect, useState } from "react";

// Per-layer opacity (index 0 = layer 1). Unlisted layers default to 1.
// sky-dark layer map:
//   1 = sky gradient  2 = moon + stars  3 = upper clouds (over moon)
//   4 = mid clouds    5 = soft distant clouds  6 = foreground storm clouds
const LAYER_OPACITY: Record<"sky-light" | "sky-dark", Record<number, number>> =
  {
    "sky-light": {},
    "sky-dark": {
      3: 0.35, // upper clouds — let the moon breathe through
      4: 0.65, // mid clouds
      5: 0.75, // soft distant clouds
    },
  };

// Layer counts per theme folder (1 = furthest back, N = closest to viewer)
const LAYER_COUNT: Record<"sky-light" | "sky-dark", number> = {
  "sky-light": 3,
  "sky-dark": 6,
};

/**
 * Full-viewport login background composed from stacked pixel-art parallax
 * layers. Switches between sky-light/ and sky-dark/ based on the active theme.
 *
 * Assets live in /public/backgrounds/<folder>/<n>.png where n starts at 1
 * (furthest back) and increases toward the viewer.
 *
 * `image-rendering: pixelated` keeps the 576×324 source crisp at any size.
 * A gradient overlay at the bottom ensures the login card stays readable.
 */
export function LoginBackground() {
  const { resolvedTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  const folder: "sky-light" | "sky-dark" =
    mounted && resolvedTheme === "dark" ? "sky-dark" : "sky-light";

  const layerCount = LAYER_COUNT[folder];
  const opacityMap = LAYER_OPACITY[folder];

  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
    >
      {/* SSR / pre-mount fallback gradient — no flash of unstyled content */}
      <div
        className="absolute inset-0 bg-gradient-to-b
          from-[#5b8fd4] via-[#7eb8e8] to-[#b8d9f0]
          dark:from-[#0d1b2e] dark:via-[#1a2f4a] dark:to-[#243752]"
      />

      {/* Stacked pixel-art layers — 1 is sky/back, N is ground/front */}
      {mounted &&
        Array.from({ length: layerCount }, (_, i) => i + 1).map((n) => (
          <div
            key={`${folder}-${n}`}
            className="absolute inset-0"
            style={{
              backgroundImage: `url(/backgrounds/${folder}/${n}.png)`,
              backgroundSize: "cover",
              backgroundPosition: "center bottom",
              backgroundRepeat: "no-repeat",
              imageRendering: "pixelated",
              opacity: opacityMap[n] ?? 1,
            }}
          />
        ))}

      {/* Bottom gradient — keeps login card readable against any sky */}
      <div
        className="absolute inset-x-0 bottom-0 h-1/2
          bg-gradient-to-t from-background/70 to-transparent
          dark:from-background/80"
      />
    </div>
  );
}
