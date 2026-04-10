import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { NavTitle } from '@/components/nav-title';
import { SiGithub } from '@icons-pack/react-simple-icons';
import type { ReactNode } from 'react';

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <HomeLayout
      nav={{
        title: <NavTitle />,
      }}
      links={[
        { text: 'Documentation', url: '/docs/guide/introduction' },
        { text: 'Changelog', url: '/docs/project/changelog' },
        {
          text: 'Dashboard',
          url: 'https://dashboard.nimbuspowered.org',
          external: true,
        },
        {
          type: 'icon',
          label: 'GitHub',
          icon: <SiGithub className="size-5" />,
          text: 'GitHub',
          url: 'https://github.com/NimbusPowered/Nimbus',
          external: true,
        },
      ]}
    >
      {children}
    </HomeLayout>
  );
}
