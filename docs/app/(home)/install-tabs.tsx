'use client';

import { useState } from 'react';

const tabs = [
  {
    label: 'Linux / macOS',
    command:
      'curl -fsSL https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.sh | bash',
  },
  {
    label: 'Windows',
    command:
      'irm https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.ps1 | iex',
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
    <figure className="not-prose overflow-hidden rounded-xl border border-fd-border bg-fd-card shadow-sm text-sm">
      {/* Title bar — matches Fumadocs CodeBlock header */}
      <div className="flex items-center h-9.5 border-b border-fd-border px-4 text-fd-muted-foreground">
        <div className="flex items-center gap-1">
          {tabs.map((tab, i) => (
            <button
              key={tab.label}
              onClick={() => setActive(i)}
              className={`relative px-2 py-1.5 text-sm font-medium transition-colors duration-200 ${
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

        <button
          onClick={copy}
          className="ms-auto shrink-0 inline-flex items-center justify-center rounded-md size-7 transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground"
          aria-label="Copy command"
        >
          {copied ? (
            <svg className="size-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          ) : (
            <svg className="size-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
          )}
        </button>
      </div>

      {/* Code area — matches Fumadocs CodeBlock viewport */}
      <div className="overflow-x-auto py-3.5 px-4">
        <pre className="min-w-full w-max">
          <code className="text-[13px] leading-relaxed text-fd-foreground font-mono">
            {tabs[active].command}
          </code>
        </pre>
      </div>
    </figure>
  );
}
