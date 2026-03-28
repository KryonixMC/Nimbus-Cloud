import type { NextConfig } from "next";

const nimbusApiUrl = process.env.NIMBUS_API_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${nimbusApiUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
