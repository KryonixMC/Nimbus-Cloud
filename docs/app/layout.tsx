import { RootProvider } from 'fumadocs-ui/provider/next';
import { Geist, Geist_Mono } from 'next/font/google';
import type { ReactNode } from 'react';
import type { Metadata } from 'next';
import './global.css';

const geist = Geist({
  subsets: ['latin'],
  variable: '--font-geist',
});

const geistMono = Geist_Mono({
  subsets: ['latin'],
  variable: '--font-geist-mono',
});

export const metadata: Metadata = {
  title: {
    template: '%s | Nimbus',
    default: 'Nimbus — Minecraft Cloud System',
  },
  description:
    'Dynamic server management from a single JAR. Auto-scaling, proxy integration, and a powerful API.',
  icons: { icon: '/icon.png' },
  metadataBase: new URL('https://NimbusPowered.github.io/Nimbus'),
  openGraph: {
    type: 'website',
    title: 'Nimbus — Minecraft Cloud System',
    description:
      'Dynamic server management from a single JAR. Auto-scaling, proxy integration, and a powerful API.',
  },
};

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning className={`${geist.variable} ${geistMono.variable}`}>
      <body className="flex flex-col min-h-screen font-[family-name:var(--font-geist)]">
        <RootProvider search={{ options: { type: 'static' } }}>{children}</RootProvider>
      </body>
    </html>
  );
}
