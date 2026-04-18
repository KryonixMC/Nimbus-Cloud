"use client";

import { useId, useMemo } from "react";

/**
 * Animated Minecraft-style pixel sky — pure SVG + CSS, no external media.
 *
 * Three layers, all rendered with `shape-rendering="crispEdges"` so the
 * pixels stay sharp at any viewport size:
 *   1. Sky gradient (top darker blue → bottom lighter blue, day-time feel)
 *   2. Twinkling stars — fixed seeded positions, CSS `animation-delay`
 *      staggers the twinkle so they don't pulse in sync
 *   3. Pixel clouds — cluster of rectangles that drift horizontally
 *      across the sky on a slow loop
 *
 * Deterministic positions (no `Math.random` at render) so the server-
 * rendered HTML matches the client hydration and React doesn't complain.
 */
export function PixelSky({ className }: { className?: string }) {
  const gradId = useId();

  // Pre-computed "pseudo-random" star positions — spread evenly-ish so the
  // sky doesn't have empty regions. Values are integers (pixel-grid) so the
  // stars land on whole cells.
  const stars = useMemo(
    () => [
      { x: 8, y: 6, d: 0 },
      { x: 22, y: 14, d: 1.4 },
      { x: 37, y: 5, d: 3.1 },
      { x: 51, y: 18, d: 0.6 },
      { x: 64, y: 9, d: 2.2 },
      { x: 79, y: 22, d: 4.0 },
      { x: 92, y: 11, d: 1.9 },
      { x: 14, y: 28, d: 2.6 },
      { x: 45, y: 32, d: 0.9 },
      { x: 72, y: 30, d: 3.4 },
      { x: 88, y: 40, d: 1.1 },
      { x: 30, y: 44, d: 4.2 },
      { x: 58, y: 48, d: 0.3 },
      { x: 5, y: 50, d: 2.9 },
      { x: 96, y: 56, d: 1.7 },
      { x: 40, y: 60, d: 3.8 },
      { x: 20, y: 66, d: 0.5 },
      { x: 76, y: 68, d: 2.3 },
      { x: 60, y: 75, d: 4.5 },
      { x: 10, y: 80, d: 1.3 },
    ],
    []
  );

  // Each pixel cloud is a list of relative cells (x,y,w,h). A cloud has a
  // puffy Minecraft-texture silhouette built from small rectangles rather
  // than a single blob — that's the whole point of "pixel".
  const cloudShape: Array<[number, number, number, number]> = useMemo(
    () => [
      [2, 1, 4, 1],
      [1, 2, 6, 1],
      [0, 3, 8, 1],
      [1, 4, 6, 1],
      [3, 5, 3, 1],
    ],
    []
  );

  // Three clouds at different altitudes + speeds — parallax-style depth.
  const clouds = useMemo(
    () => [
      { y: 10, scale: 1.4, duration: 72, delay: 0, opacity: 0.85 },
      { y: 24, scale: 1.0, duration: 96, delay: -32, opacity: 0.7 },
      { y: 38, scale: 1.8, duration: 120, delay: -60, opacity: 0.6 },
    ],
    []
  );

  return (
    <div
      aria-hidden
      className={`pointer-events-none absolute inset-0 -z-10 overflow-hidden ${className ?? ""}`}
    >
      <svg
        viewBox="0 0 100 100"
        preserveAspectRatio="xMidYMid slice"
        shapeRendering="crispEdges"
        className="h-full w-full"
      >
        {/* Vertical gradient — daytime Minecraft sky */}
        <defs>
          <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#3a6fb5" />
            <stop offset="40%" stopColor="#6da3d9" />
            <stop offset="100%" stopColor="#a9d5f5" />
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${gradId})`} />

        {/* Twinkling stars — each is a single 1×1 white pixel with a slow
            opacity animation staggered by `animation-delay`. */}
        <g className="pixel-sky-stars" fill="#ffffff">
          {stars.map((s, i) => (
            <rect
              key={i}
              x={s.x}
              y={s.y}
              width={1}
              height={1}
              style={{
                animationDelay: `${s.d}s`,
              }}
            />
          ))}
        </g>

        {/* Drifting clouds — translated horizontally over the full duration.
            Using SVG <g> transforms with CSS keyframes for GPU-friendly
            motion (transform is cheap; opacity isn't touched on the loop). */}
        {clouds.map((c, i) => (
          <g
            key={i}
            className="pixel-sky-cloud"
            style={{
              animationDuration: `${c.duration}s`,
              animationDelay: `${c.delay}s`,
              opacity: c.opacity,
            }}
          >
            <g transform={`translate(0 ${c.y}) scale(${c.scale})`}>
              {cloudShape.map(([x, y, w, h], k) => (
                <rect
                  key={k}
                  x={x}
                  y={y}
                  width={w}
                  height={h}
                  fill="#ffffff"
                />
              ))}
            </g>
          </g>
        ))}
      </svg>

      {/* A very faint dark overlay at the bottom keeps form contrast high
          on light content without hiding the sky. */}
      <div
        className="absolute inset-x-0 bottom-0 h-1/3
          bg-gradient-to-t from-background/60 to-transparent
          dark:from-background/70"
      />
    </div>
  );
}
