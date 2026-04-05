'use client';

import { useState } from 'react';
import { DynamicCodeBlock } from 'fumadocs-ui/components/dynamic-codeblock';
import { TerminalIcon } from 'lucide-react';

const tabs = [
  {
    label: 'Linux / macOS',
    lang: 'bash',
    command:
      'curl -fsSL https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.sh | bash',
  },
  {
    label: 'Windows',
    lang: 'powershell',
    command:
      'irm https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.ps1 | iex',
  },
];

export function InstallTabs() {
  const [active, setActive] = useState(0);

  return (
    <div className="overflow-hidden rounded-xl border border-fd-border bg-fd-card shadow-sm">
      {/* Tab triggers — styled like Fumadocs CodeBlockTabsList */}
      <div className="flex items-center gap-1 px-2 border-b border-fd-border text-fd-muted-foreground">
        {tabs.map((tab, i) => (
          <button
            key={tab.label}
            onClick={() => setActive(i)}
            className={`relative group inline-flex text-sm font-medium items-center px-2 py-1.5 transition-colors duration-200 ${
              active === i
                ? 'text-fd-primary'
                : 'text-fd-muted-foreground hover:text-fd-accent-foreground'
            }`}
          >
            {active === i && (
              <div className="absolute inset-x-2 bottom-0 h-px bg-fd-primary" />
            )}
            {tab.label}
          </button>
        ))}
      </div>

      {/* Code block — uses Fumadocs DynamicCodeBlock for real Shiki highlighting */}
      <DynamicCodeBlock
        lang={tabs[active].lang}
        code={tabs[active].command}
        codeblock={{
          title: tabs[active].label === 'Windows' ? 'PowerShell' : 'Terminal',
          icon: <TerminalIcon className="size-3.5" />,
          allowCopy: true,
        }}
      />
    </div>
  );
}
