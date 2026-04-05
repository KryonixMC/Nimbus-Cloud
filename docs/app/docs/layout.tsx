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
    <div className="flex items-center justify-center" style={{ color }}>
      {children}
    </div>
  );
}

/** Build a Set of all page URLs under a given prefix. */
function urlsUnder(prefix: string): Set<string> {
  const pages = source.getPages();
  const set = new Set<string>();
  for (const page of pages) {
    if (page.url.startsWith(prefix)) {
      set.add(page.url);
    }
  }
  return set;
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
            urls: urlsUnder('/docs/guide'),
            icon: (
              <TabIcon color="hsl(142 71% 45%)">
                <BookOpen className="size-5" />
              </TabIcon>
            ),
          },
          {
            title: 'Configuration',
            description: 'TOML config reference',
            url: '/docs/config/nimbus-toml',
            urls: urlsUnder('/docs/config'),
            icon: (
              <TabIcon color="hsl(199 89% 48%)">
                <Settings className="size-5" />
              </TabIcon>
            ),
          },
          {
            title: 'API Reference',
            description: 'REST API & WebSocket',
            url: '/docs/reference/api',
            urls: urlsUnder('/docs/reference'),
            icon: (
              <TabIcon color="hsl(280 65% 60%)">
                <Code className="size-5" />
              </TabIcon>
            ),
          },
          {
            title: 'Developer',
            description: 'Architecture & plugins',
            url: '/docs/developer/architecture',
            urls: urlsUnder('/docs/developer'),
            icon: (
              <TabIcon color="hsl(25 95% 53%)">
                <Puzzle className="size-5" />
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
