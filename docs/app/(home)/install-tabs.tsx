'use client';

import { useState } from 'react';

const tabs = [
  {
    label: 'Linux / macOS',
    command: 'curl -fsSL https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.sh | bash',
  },
  {
    label: 'Windows',
    command: 'irm https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.ps1 | iex',
  },
];

export function InstallTabs() {
  const [active, setActive] = useState(0);
  const [copied, setCopied] = useState(false);

  function copy() {
    navigator.clipboard.writeText(tabs[active].command);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="rounded-xl border border-fd-border bg-fd-card overflow-hidden">
      <div className="flex border-b border-fd-border">
        {tabs.map((tab, i) => (
          <button
            key={tab.label}
            onClick={() => setActive(i)}
            className={`flex-1 px-4 py-2.5 text-xs font-medium transition-colors duration-200 ${
              active === i
                ? 'text-fd-primary bg-fd-primary/5 border-b-2 border-fd-primary -mb-px'
                : 'text-fd-muted-foreground hover:text-fd-foreground'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-3 px-4 py-3">
        <code className="flex-1 text-sm font-mono text-fd-foreground truncate">
          {tabs[active].command}
        </code>
        <button
          onClick={copy}
          className="shrink-0 rounded-md p-1.5 text-fd-muted-foreground transition-colors duration-200 hover:text-fd-foreground hover:bg-fd-accent"
          aria-label="Copy command"
        >
          {copied ? (
            <svg className="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          ) : (
            <svg className="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
          )}
        </button>
      </div>
    </div>
  );
}
