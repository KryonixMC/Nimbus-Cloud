'use client';

import { Tabs, Tab } from 'fumadocs-ui/components/tabs';
import { DynamicCodeBlock } from 'fumadocs-ui/components/dynamic-codeblock';
import { TerminalIcon } from 'lucide-react';

export function InstallTabs() {
  return (
    <Tabs items={['Linux / macOS', 'Windows']} defaultIndex={0}>
      <Tab value="Linux / macOS">
        <DynamicCodeBlock
          lang="bash"
          code="curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash"
          codeblock={{
            title: 'Terminal',
            icon: <TerminalIcon className="size-3.5" />,
            allowCopy: true,
          }}
        />
      </Tab>
      <Tab value="Windows">
        <DynamicCodeBlock
          lang="powershell"
          code="irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex"
          codeblock={{
            title: 'PowerShell',
            icon: <TerminalIcon className="size-3.5" />,
            allowCopy: true,
          }}
        />
      </Tab>
    </Tabs>
  );
}
