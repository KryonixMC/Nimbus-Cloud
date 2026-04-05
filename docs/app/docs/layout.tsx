import { DocsLayout } from 'fumadocs-ui/layouts/docs';
import { source } from '@/lib/source';
import type { ReactNode } from 'react';
import {
  BookOpen,
  Settings,
  Code,
  Puzzle,
} from 'lucide-react';

function TabIcon({ children, color }: { children: ReactNode; color: string }) {
  return (
    <div
      className="flex items-center justify-center rounded-md p-1"
      style={{ color }}
    >
      {children}
    </div>
  );
}

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
            icon: (
              <TabIcon color="hsl(142 71% 45%)">
                <BookOpen className="size-4" />
              </TabIcon>
            ),
          },
          {
            title: 'Configuration',
            description: 'TOML config reference',
            url: '/docs/config/nimbus-toml',
            icon: (
              <TabIcon color="hsl(199 89% 48%)">
                <Settings className="size-4" />
              </TabIcon>
            ),
          },
          {
            title: 'API Reference',
            description: 'REST API & WebSocket',
            url: '/docs/reference/api',
            icon: (
              <TabIcon color="hsl(280 65% 60%)">
                <Code className="size-4" />
              </TabIcon>
            ),
          },
          {
            title: 'Developer',
            description: 'Architecture & plugins',
            url: '/docs/developer/architecture',
            icon: (
              <TabIcon color="hsl(25 95% 53%)">
                <Puzzle className="size-4" />
              </TabIcon>
            ),
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
