import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  output: 'export',
  images: { unoptimized: true },
  serverExternalPackages: ['lightningcss', '@tailwindcss/postcss'],
};

export default withMDX(config);
