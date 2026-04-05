import { DocsLayout } from 'fumadocs-ui/layouts/docs';
import { source } from '@/lib/source';
import type { ReactNode } from 'react';
import {
  BookOpen,
  Settings,
  Code,
  Puzzle,
} from 'lucide-react';

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <DocsLayout
      tree={source.getPageTree()}
      nav={{
        title: 'Nimbus',
      }}
      sidebar={{
        tabs: [
          {
            title: 'Guide',
            description: 'Getting started & setup',
            url: '/docs/guide/introduction',
            icon: <BookOpen />,
          },
          {
            title: 'Configuration',
            description: 'TOML config reference',
            url: '/docs/config/nimbus-toml',
            icon: <Settings />,
          },
          {
            title: 'API Reference',
            description: 'REST API & WebSocket',
            url: '/docs/reference/api',
            icon: <Code />,
          },
          {
            title: 'Developer',
            description: 'Architecture & plugins',
            url: '/docs/developer/architecture',
            icon: <Puzzle />,
          },
        ],
      }}
      links={[
        {
          text: 'GitHub',
          url: 'https://github.com/jonax1337/Nimbus',
        },
      ]}
    >
      {children}
    </DocsLayout>
  );
}
