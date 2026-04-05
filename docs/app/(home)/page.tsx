import Link from 'next/link';
import {
  ArrowRight,
  BatteryChargingIcon,
  CloudIcon,
  GlobeIcon,
  LayersIcon,
  MonitorIcon,
  PackageIcon,
  ServerIcon,
  ShieldIcon,
  TerminalIcon,
  TimerIcon,
  ZapIcon,
} from 'lucide-react';

function cn(...classes: (string | undefined | false)[]) {
  return classes.filter(Boolean).join(' ');
}

function heading(variant: 'h2' | 'h3', extra?: string) {
  const base = 'font-medium tracking-tight';
  const size = variant === 'h2' ? 'text-3xl lg:text-4xl' : 'text-xl lg:text-2xl';
  return cn(base, size, extra);
}

function btn(variant: 'primary' | 'secondary' = 'primary', extra?: string) {
  const base = 'inline-flex items-center justify-center gap-2 px-5 py-3 rounded-full font-medium tracking-tight transition-colors text-sm';
  const style = variant === 'primary'
    ? 'bg-fd-primary text-fd-primary-foreground hover:opacity-90'
    : 'border border-fd-border bg-fd-secondary text-fd-secondary-foreground hover:bg-fd-accent';
  return cn(base, style, extra);
}

function card(variant: 'default' | 'muted' = 'default', extra?: string) {
  const base = 'rounded-2xl text-sm p-6 shadow-lg';
  const style = variant === 'default'
    ? 'border border-fd-border bg-fd-card'
    : 'bg-fd-muted/50 border border-fd-border';
  return cn(base, style, extra);
}

function GitHubIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
    </svg>
  );
}

export default function Page() {
  return (
    <main className="pt-4 pb-6 md:pb-12">
      {/* Hero */}
      <div className="relative flex min-h-[500px] h-[60vh] max-h-[700px] border border-fd-border rounded-2xl overflow-hidden mx-auto w-full max-w-[1400px]">
        {/* Gradient background */}
        <div className="absolute inset-0 -z-10">
          <div className="absolute inset-0 bg-gradient-to-br from-fd-primary/10 via-transparent to-fd-primary/5" />
          <div className="absolute top-0 left-1/3 h-[400px] w-[600px] rounded-full bg-fd-primary/8 blur-[120px]" />
          <div className="absolute bottom-0 right-1/4 h-[300px] w-[400px] rounded-full bg-fd-primary/5 blur-[100px]" />
        </div>

        <div className="flex flex-col z-[2] px-4 size-full md:p-12 max-md:items-center max-md:text-center">
          <p className="mt-12 text-xs text-fd-primary font-medium rounded-full py-2 px-4 border border-fd-primary/30 w-fit">
            Minecraft Cloud System
          </p>

          <h1 className="text-4xl my-8 leading-tight font-medium xl:text-5xl xl:mb-12">
            Deploy servers,
            <br />
            not <span className="text-fd-primary">complexity</span>.
          </h1>

          <p className="text-fd-muted-foreground max-w-lg mb-8 leading-relaxed">
            Dynamic server management from a single JAR — auto-scaling, multi-node clusters,
            and a powerful API without the bloat.
          </p>

          <div className="flex flex-row items-center gap-4 flex-wrap w-fit">
            <Link
              href="/docs/guide/quickstart"
              className={btn()}
            >
              Get Started
              <ArrowRight className="size-4" />
            </Link>
            <a
              href="https://github.com/jonax1337/Nimbus"
              target="_blank"
              rel="noreferrer noopener"
              className={btn('secondary')}
            >
              <GitHubIcon className="size-4" />
              GitHub
            </a>
          </div>
        </div>
      </div>

      {/* Content grid */}
      <div className="grid grid-cols-1 gap-6 mt-12 px-6 mx-auto w-full max-w-[1400px] md:px-12 lg:grid-cols-2 lg:mt-20">
        {/* Intro text */}
        <p className="text-2xl tracking-tight leading-snug font-light col-span-full md:text-3xl xl:text-4xl">
          Nimbus is a <span className="text-fd-primary font-medium">lightweight</span> cloud system
          for <span className="text-fd-primary font-medium">Minecraft</span> — managing dynamic
          server instances, proxies, and scaling from a{' '}
          <span className="text-fd-primary font-medium">single JAR</span>.
        </p>

        {/* Terminal */}
        <div
          className={cn(card(), 'col-span-full p-0 overflow-hidden')}
        >
          <div className="terminal-header">
            <span className="terminal-title">nimbus</span>
          </div>
          <pre className="terminal-body">
<span className="t-blue">{'   _  __ __ _   __ ___  _ __  ___'}</span>{'\n'}
<span className="t-bright-blue">{'  / |/ // // \\,\' // o.)/// /,\' _/'}</span>{'\n'}
<span className="t-cyan">{' / || // // \\,\' // o \\/ U /_\\ `. '}</span>{'\n'}
<span className="t-bright-cyan">{'/_/|_//_//_/ /_//___,\'\\_,\'/___,\''}</span>{'\n'}
<span className="t-dim">{'            C L O U D'}</span>{'\n'}
<span className="t-dim">{'  Network:'}</span>  <span className="t-bold">{'MyNetwork'}</span>{'\n'}
<span className="t-dim">{'  Version:'}</span>  <span className="t-cyan">{'v0.2.0'}</span>{'\n'}
<span className="t-dim">{'──────────────────────────────'}</span>{'\n'}
<span className="t-dim">{'[12:00:01]'}</span> <span className="t-yellow">{'▲ STARTING'}</span>  <span className="t-bold">{'Proxy-1'}</span> <span className="t-dim">{'(port=25565)'}</span>{'\n'}
<span className="t-dim">{'[12:00:04]'}</span> <span className="t-green">{'● READY'}</span>     <span className="t-bold">{'Proxy-1'}</span>{'\n'}
<span className="t-dim">{'[12:00:04]'}</span> <span className="t-yellow">{'▲ STARTING'}</span>  <span className="t-bold">{'Lobby-1'}</span> <span className="t-dim">{'(port=30000)'}</span>{'\n'}
<span className="t-dim">{'[12:00:08]'}</span> <span className="t-green">{'● READY'}</span>     <span className="t-bold">{'Lobby-1'}</span>{'\n'}
<span className="t-dim">{'[12:00:15]'}</span> <span className="t-green">{'↑ SCALE UP'}</span>  <span className="t-bold">{'BedWars'}</span>{' 1 → 2\n'}
<span className="t-prompt">{'nimbus'}</span> <span className="t-cyan">{'»'}</span> <span className="t-cursor">{'_'}</span>
          </pre>
        </div>

        <Features />
        <WhyNimbus />
        <BuiltWith />
        <GetStarted />
      </div>
    </main>
  );
}

function Features() {
  const features = [
    {
      icon: <LayersIcon className="size-5" />,
      title: 'Multi-Node Cluster',
      desc: 'Distribute game servers across machines with automatic placement, failover, and a built-in TCP load balancer.',
    },
    {
      icon: <ZapIcon className="size-5" />,
      title: 'Smart Auto-Scaling',
      desc: 'Instances scale up and down based on player count. Configurable thresholds, idle timeouts, and game states.',
    },
    {
      icon: <GlobeIcon className="size-5" />,
      title: 'Zero-Config Proxy',
      desc: 'Velocity auto-managed — forwarding, server list, MOTD, tab list, and chat sync out of the box.',
    },
    {
      icon: <PackageIcon className="size-5" />,
      title: 'Auto-Download',
      desc: 'Paper, Purpur, Velocity, Forge, Fabric, NeoForge — server JARs downloaded and updated automatically.',
    },
  ];

  return (
    <>
      <div className={card()}>
        <h3 className={heading('h3', 'mb-6')}>
          Everything you need.
        </h3>
        <p className="mb-6">
          From auto-scaling to API access — Nimbus handles the infrastructure so you can focus on
          building your network.
        </p>
        <Link
          href="/docs/guide/introduction"
          className={btn('primary', 'w-fit')}
        >
          Learn More
        </Link>
      </div>

      <div className={card('muted', 'flex flex-col gap-4')}>
        {features.map((f) => (
          <div key={f.title} className="flex gap-3 items-start">
            <div className="flex items-center justify-center shrink-0 size-9 rounded-lg bg-fd-primary/10 text-fd-primary mt-0.5">
              {f.icon}
            </div>
            <div>
              <p className="font-medium">{f.title}</p>
              <p className="text-xs text-fd-muted-foreground mt-1">{f.desc}</p>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

function WhyNimbus() {
  const more = [
    {
      icon: <ServerIcon className="size-5" />,
      title: 'Modpack Import',
      desc: 'Import any Modrinth modpack in one command. Auto-configured with concurrent downloads.',
    },
    {
      icon: <TerminalIcon className="size-5" />,
      title: 'REST API + WebSocket',
      desc: 'Live event streams, bidirectional console, file management, and permissions — all via API.',
    },
    {
      icon: <ShieldIcon className="size-5" />,
      title: 'Built-in Permissions',
      desc: 'Groups, inheritance, tracks, meta, audit log — central DB, no external plugins needed.',
    },
    {
      icon: <MonitorIcon className="size-5" />,
      title: 'Server Selector',
      desc: 'Signs + NPCs for server selection. Auto-updating player counts and status indicators.',
    },
  ];

  return (
    <>
      <h2
        className={heading('h2', 'text-fd-primary text-center mb-0 col-span-full mt-8')}
      >
        Why Nimbus?
      </h2>

      <div className={cn(card(), 'flex flex-col')}>
        <h3 className={heading('h3', 'mb-6')}>
          No external dependencies.
        </h3>
        <p className="mb-6">
          No Docker, no databases, no complex setup. Just Java 21 and a single JAR file.
          Running in under 5 minutes.
        </p>

        <div className="overflow-x-auto rounded-xl border border-fd-border">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-fd-border bg-fd-muted/50">
                <th className="text-left p-3 font-medium text-fd-muted-foreground" />
                <th className="text-left p-3 font-semibold">Nimbus</th>
                <th className="text-left p-3 font-semibold">Manual</th>
                <th className="text-left p-3 font-semibold">Heavy Cloud</th>
              </tr>
            </thead>
            <tbody className="text-fd-muted-foreground">
              {[
                ['Setup', '< 5 min', 'Hours', '30+ min'],
                ['Dependencies', 'Java 21', 'Many', 'Docker + DB'],
                ['Proxy', 'Automatic', 'Manual', 'Varies'],
                ['Scaling', 'Built-in', 'None', 'Plugin'],
                ['API', 'REST + WS', 'None', 'Varies'],
              ].map(([label, a, b, c], i, arr) => (
                <tr key={label} className={i < arr.length - 1 ? 'border-b border-fd-border' : ''}>
                  <td className="p-3 font-medium text-fd-foreground">{label}</td>
                  <td className="p-3 text-fd-primary font-medium">{a}</td>
                  <td className="p-3">{b}</td>
                  <td className="p-3">{c}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className={card('muted', 'flex flex-col gap-4')}>
        {more.map((f) => (
          <div key={f.title} className="flex gap-3 items-start">
            <div className="flex items-center justify-center shrink-0 size-9 rounded-lg bg-fd-primary/10 text-fd-primary mt-0.5">
              {f.icon}
            </div>
            <div>
              <p className="font-medium">{f.title}</p>
              <p className="text-xs text-fd-muted-foreground mt-1">{f.desc}</p>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

function BuiltWith() {
  return (
    <>
      <div className={cn(card(), 'flex flex-col')}>
        <h3 className={heading('h3', 'mb-6')}>
          A truly modular system.
        </h3>
        <p className="mb-8">
          Separated into{' '}
          <span className="text-fd-primary">Core</span> →{' '}
          <span className="text-fd-primary">Modules</span> →{' '}
          <span className="text-fd-primary">Plugins</span>{' '}
          — extend Nimbus with custom modules, or use the SDK to build your own server logic.
        </p>
        <div className="flex flex-col gap-2">
          {[
            { name: 'nimbus-core', desc: 'Main cloud controller — scaling, proxy, API, console.' },
            { name: 'nimbus-agent', desc: 'Remote agent for multi-node cluster deployments.' },
            { name: 'nimbus-bridge', desc: 'Velocity plugin — hub commands + cloud bridge.' },
            { name: 'nimbus-sdk', desc: 'Server SDK for Spigot / Paper / Folia backends.' },
            { name: 'nimbus-perms', desc: 'Permissions plugin with groups, tracks, and audit.' },
          ].map((item) => (
            <div
              key={item.name}
              className="flex flex-col text-sm gap-1 p-3 border border-dashed border-fd-border rounded-lg lg:flex-row lg:items-center"
            >
              <p className="font-medium font-mono text-fd-primary text-xs whitespace-nowrap">
                {item.name}
              </p>
              <p className="text-xs text-fd-muted-foreground flex-1 lg:text-end">
                {item.desc}
              </p>
            </div>
          ))}
        </div>
      </div>

      <div className={cn(card(), 'flex flex-col')}>
        <h3 className={heading('h3', 'mb-6')}>
          9 server platforms.
        </h3>
        <p className="mb-6">
          Paper, Purpur, Pufferfish, Folia, Velocity, Forge, Fabric, NeoForge, and Vanilla —
          all auto-downloaded, configured, and optimized.
        </p>
        <div className="flex flex-row flex-wrap gap-2 mt-auto">
          {['Paper', 'Purpur', 'Pufferfish', 'Folia', 'Velocity', 'Forge', 'Fabric', 'NeoForge', 'Vanilla'].map(
            (name) => (
              <span
                key={name}
                className="px-3 py-1.5 rounded-lg bg-fd-primary/10 text-fd-primary text-xs font-medium"
              >
                {name}
              </span>
            ),
          )}
        </div>
      </div>
    </>
  );
}

function GetStarted() {
  return (
    <>
      <h2
        className={heading('h2', 'mt-8 text-fd-primary text-center mb-0 col-span-full')}
      >
        Ready to deploy?
      </h2>

      <div className={cn(card(), 'flex flex-col')}>
        <CloudIcon className="text-fd-primary mb-4 size-8" />
        <h3 className={heading('h3', 'mb-6')}>
          Up and running in minutes.
        </h3>
        <p className="mb-8">
          One command to install, one JAR to run. Nimbus handles the rest — server downloads,
          proxy config, plugin deployment, and auto-scaling.
        </p>
        <div className="flex flex-row items-center gap-2 flex-wrap">
          <Link
            href="/docs/guide/quickstart"
            className={btn('primary')}
          >
            Quick Start
            <ArrowRight className="size-4" />
          </Link>
          <Link
            href="/docs/reference/api"
            className={btn('secondary')}
          >
            API Reference
          </Link>
        </div>
      </div>

      <div className={cn(card(), 'flex flex-col p-0 pt-8')}>
        <h2 className="text-3xl text-center font-extrabold font-mono uppercase mb-4 lg:text-4xl">
          Nimbus Cloud
        </h2>
        <p className="text-center font-mono text-xs text-fd-muted-foreground mb-8">
          lightweight and fast, just like a cloud.
        </p>
        <div className="h-[200px] mt-auto overflow-hidden p-8 bg-gradient-to-b from-fd-primary/5">
          <div className="mx-auto bg-[radial-gradient(circle_at_0%_100%,transparent_60%,hsl(var(--fd-primary)/0.15))] size-[500px] rounded-full" />
        </div>
      </div>

      <ul className={cn(card(), 'flex flex-col gap-6 col-span-full')}>
        <li>
          <span className="flex flex-row items-center gap-2 font-medium">
            <BatteryChargingIcon className="size-5" />
            Battery included.
          </span>
          <span className="mt-2 text-sm text-fd-muted-foreground block">
            Proxy, permissions, scaling, API — everything built-in. No external plugins required.
          </span>
        </li>
        <li>
          <span className="flex flex-row items-center gap-2 font-medium">
            <GitHubIcon className="size-5" />
            Fully open-source.
          </span>
          <span className="mt-2 text-sm text-fd-muted-foreground block">
            MIT licensed, available on GitHub. Contributions welcome.
          </span>
        </li>
        <li>
          <span className="flex flex-row items-center gap-2 font-medium">
            <TimerIcon className="size-5" />
            Within minutes.
          </span>
          <span className="mt-2 text-sm text-fd-muted-foreground block">
            One-command installer. Interactive setup wizard. Your network is live in under 5 minutes.
          </span>
        </li>
        <li className="flex flex-row flex-wrap gap-2 mt-auto pt-4">
          <Link
            href="/docs/guide/quickstart"
            className={btn()}
          >
            Read docs
          </Link>
          <a
            href="https://github.com/jonax1337/Nimbus"
            target="_blank"
            rel="noreferrer noopener"
            className={btn('secondary')}
          >
            Open GitHub
          </a>
        </li>
      </ul>
    </>
  );
}
