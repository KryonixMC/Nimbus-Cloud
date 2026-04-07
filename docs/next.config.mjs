import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

const isProd = process.env.NODE_ENV === 'production';

/** @type {import('next').NextConfig} */
const config = {
  output: 'export',
  basePath: isProd ? '/Nimbus' : '',
  images: { unoptimized: true },
  serverExternalPackages: ['lightningcss', '@tailwindcss/postcss'],
};

export default withMDX(config);
