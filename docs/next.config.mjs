import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

const basePath = process.env.NEXT_PUBLIC_BASE_PATH || '';

/** @type {import('next').NextConfig} */
const config = {
  output: 'export',
  images: { unoptimized: true },
  serverExternalPackages: ['lightningcss', '@tailwindcss/postcss'],
  basePath,
  assetPrefix: basePath || undefined,
  env: {
    NEXT_PUBLIC_BASE_PATH: basePath,
    NEXT_PUBLIC_DOCS_CHANNEL: process.env.NEXT_PUBLIC_DOCS_CHANNEL || 'stable',
  },
};

export default withMDX(config);
