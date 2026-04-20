import type { NextConfig } from "next";
import pkg from "./package.json" with { type: "json" };

const nextConfig: NextConfig = {
  allowedDevOrigins: ["192.168.178.32"],
  serverExternalPackages: ["ws"],
  images: {
    qualities: [25, 50, 75, 100],
  },
  env: {
    // Injected at build time so the client bundle can display the version.
    // Sourced from package.json so there is a single source of truth.
    NEXT_PUBLIC_DASHBOARD_VERSION: pkg.version,
  },
};

export default nextConfig;
