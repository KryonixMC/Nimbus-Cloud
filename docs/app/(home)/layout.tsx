import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { NavTitle } from '@/components/nav-title';
import type { ReactNode } from 'react';

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <HomeLayout
      nav={{
        title: <NavTitle />,
      }}
      links={[
        { text: 'Documentation', url: '/docs/guide/introduction' },
        {
          text: 'GitHub',
          url: 'https://github.com/NimbusPowered/Nimbus',
        },
      ]}
    >
      {children}
    </HomeLayout>
  );
}
