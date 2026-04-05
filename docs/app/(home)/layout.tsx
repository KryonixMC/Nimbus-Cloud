import { HomeLayout } from 'fumadocs-ui/layouts/home';
import type { ReactNode } from 'react';

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <HomeLayout
      nav={{
        title: 'Nimbus',
      }}
      links={[
        { text: 'Documentation', url: '/docs/guide/introduction' },
        {
          text: 'GitHub',
          url: 'https://github.com/jonax1337/Nimbus',
        },
      ]}
    >
      {children}
    </HomeLayout>
  );
}
