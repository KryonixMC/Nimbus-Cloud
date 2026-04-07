import Link from 'next/link';
import Image from 'next/image';
import {
  ArrowRight,
  CloudIcon,
  GamepadIcon,
  GlobeIcon,
  HeartIcon,
  LayersIcon,
  MonitorIcon,
  PackageIcon,
  ShieldIcon,
  TerminalIcon,
  ZapIcon,
} from 'lucide-react';
import { InstallTabs } from './install-tabs';

const features = [
  {
    icon: <ZapIcon className="size-5" />,
    title: 'Smart Auto-Scaling',
    desc: 'Instances scale based on player count — configurable thresholds, idle timeouts, and game states.',
  },
  {
    icon: <GlobeIcon className="size-5" />,
    title: 'Zero-Config Proxy',
    desc: 'Velocity auto-managed — forwarding, server list, MOTD, tab list, and chat sync.',
  },
  {
    icon: <LayersIcon className="size-5" />,
    title: 'Multi-Node Cluster',
    desc: 'Distribute servers across machines with automatic placement and failover.',
  },
  {
    icon: <PackageIcon className="size-5" />,
    title: 'Auto-Download',
    desc: 'Paper, Purpur, Leaf, Velocity, Forge, Fabric, NeoForge — JARs downloaded automatically.',
  },
  {
    icon: <ShieldIcon className="size-5" />,
    title: 'Built-in Permissions',
    desc: 'Groups, inheritance, tracks, audit log — central DB, no external plugins needed.',
  },
  {
    icon: <TerminalIcon className="size-5" />,
    title: 'REST API + WebSocket',
    desc: 'Live events, bidirectional console, and full programmatic control over your network.',
  },
  {
    icon: <GamepadIcon className="size-5" />,
    title: 'Bedrock Support',
    desc: 'Geyser + Floodgate auto-configured — let Bedrock players join your Java network seamlessly.',
  },
  {
    icon: <MonitorIcon className="size-5" />,
    title: 'Interactive Console',
    desc: '30+ commands with tab-completion, live service logs, and a built-in setup wizard.',
  },
];

const platforms = [
  'Paper', 'Purpur', 'Pufferfish', 'Leaf', 'Folia', 'Velocity',
  'Forge', 'Fabric', 'NeoForge', 'Vanilla',
];

const footerDocs = [
  { label: 'Quick Start', href: '/docs/guide/quickstart' },
  { label: 'Installation', href: '/docs/guide/installation' },
  { label: 'Configuration', href: '/docs/config/nimbus-toml' },
  { label: 'Commands', href: '/docs/guide/commands' },
];

const footerDev = [
  { label: 'REST API', href: '/docs/reference/api' },
  { label: 'WebSocket', href: '/docs/reference/websocket' },
  { label: 'Architecture', href: '/docs/developer/architecture' },
  { label: 'Modules', href: '/docs/developer/modules' },
];

const footerCommunity = [
  { label: 'GitHub', href: 'https://github.com/NimbusPowered/Nimbus' },
  { label: 'Issues', href: 'https://github.com/NimbusPowered/Nimbus/issues' },
  { label: 'Releases', href: 'https://github.com/NimbusPowered/Nimbus/releases' },
];

export default function Page() {
  return (
    <main className="pt-4 pb-0">
      {/* Hero Card */}
      <section className="relative border border-fd-border rounded-2xl overflow-hidden mx-auto w-full max-w-fd-container">
        <div className="absolute inset-0 -z-10">
          <div className="absolute inset-0 bg-gradient-to-br from-fd-primary/10 via-transparent to-fd-primary/5" />
          <div className="absolute top-0 left-1/3 h-[400px] w-[600px] rounded-full bg-fd-primary/8 blur-[120px]" />
          <div className="absolute bottom-0 right-1/4 h-[300px] w-[400px] rounded-full bg-fd-primary/5 blur-[100px]" />
        </div>

        <div className="flex flex-col z-[2] px-6 py-16 md:px-16 md:py-20 max-md:items-center max-md:text-center">
          <p className="text-xs text-fd-primary font-medium rounded-full py-2 px-4 border border-fd-primary/30 w-fit">
            Minecraft Cloud System
          </p>

          <h1 className="text-4xl mt-8 mb-4 leading-tight font-semibold xl:text-5xl">
            Deploy servers,
            <br />
            not <span className="text-fd-primary">complexity</span>.
          </h1>

          <p className="text-fd-muted-foreground max-w-xl mb-10 text-base leading-relaxed">
            Dynamic server management from a single JAR — auto-scaling, multi-node
            clusters, and a powerful API without the bloat.
          </p>

          <div className="flex flex-row items-center gap-4 flex-wrap w-fit">
            <Link
              href="/docs/guide/quickstart"
              className="group inline-flex items-center justify-center gap-2 px-6 py-3 rounded-full font-medium tracking-tight transition-all duration-300 ease-out text-sm bg-fd-primary text-fd-primary-foreground hover:shadow-lg hover:shadow-fd-primary/25 hover:scale-[1.02] active:scale-[0.98]"
            >
              Get Started
              <ArrowRight className="size-4 transition-transform duration-300 ease-out group-hover:translate-x-0.5" />
            </Link>
            <a
              href="https://github.com/NimbusPowered/Nimbus"
              target="_blank"
              rel="noreferrer noopener"
              className="inline-flex items-center justify-center gap-2 px-6 py-3 rounded-full font-medium tracking-tight transition-all duration-300 ease-out text-sm border border-fd-border bg-fd-secondary text-fd-secondary-foreground hover:bg-fd-accent hover:border-fd-accent active:scale-[0.98]"
            >
              GitHub
            </a>
          </div>
        </div>
      </section>

      {/* Install */}
      <section className="mx-auto mt-12 max-w-fd-container px-6">
        <div className="mx-auto max-w-3xl">
          <p className="text-center text-sm font-medium text-fd-muted-foreground mb-3">
            One command to install
          </p>
          <InstallTabs />
        </div>
      </section>

      {/* Features */}
      <section className="mx-auto mt-24 max-w-fd-container px-6">
        <div className="text-center mb-10">
          <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">
            Everything you need.
          </h2>
          <p className="mt-3 text-fd-muted-foreground text-base">
            From auto-scaling to API access — Nimbus handles the infrastructure.
          </p>
        </div>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {features.map((f) => (
            <div
              key={f.title}
              className="group flex gap-4 rounded-xl border border-fd-border bg-fd-card p-6 transition-all duration-300 ease-out hover:border-fd-primary/30 hover:shadow-md hover:shadow-fd-primary/5 hover:-translate-y-0.5"
            >
              <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-fd-primary/10 text-fd-primary transition-colors duration-300 ease-out group-hover:bg-fd-primary/15">
                {f.icon}
              </div>
              <div>
                <p className="font-medium">{f.title}</p>
                <p className="mt-1 text-sm text-fd-muted-foreground leading-relaxed">
                  {f.desc}
                </p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Platforms */}
      <section className="mx-auto mt-24 max-w-fd-container px-6 text-center">
        <p className="text-sm font-medium text-fd-muted-foreground mb-5">
          Supports 9 server platforms
        </p>
        <div className="flex flex-wrap justify-center gap-2.5">
          {platforms.map((name) => (
            <span
              key={name}
              className="rounded-full border border-fd-border bg-fd-card px-4 py-2 text-sm font-medium transition-colors duration-300 ease-out hover:border-fd-primary/30 hover:text-fd-primary"
            >
              {name}
            </span>
          ))}
        </div>
      </section>

      {/* CTA */}
      <section className="relative mx-auto mt-24 max-w-3xl overflow-hidden rounded-2xl border border-fd-border px-6 py-16 text-center">
        <div className="absolute inset-0 -z-10">
          <div className="absolute inset-0 bg-gradient-to-t from-fd-primary/5 via-transparent to-transparent" />
        </div>

        <CloudIcon className="mx-auto size-10 text-fd-primary mb-4" />
        <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          Up and running in minutes.
        </h2>
        <p className="mx-auto mt-3 max-w-lg text-fd-muted-foreground text-base">
          One command to install, one JAR to run. No Docker, no databases — just
          Java 21 and your network is live.
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/docs/guide/quickstart"
            className="group inline-flex items-center gap-2 rounded-full bg-fd-primary px-6 py-3 text-sm font-medium text-fd-primary-foreground transition-all duration-300 ease-out hover:shadow-lg hover:shadow-fd-primary/25 hover:scale-[1.02] active:scale-[0.98]"
          >
            Quick Start
            <ArrowRight className="size-4 transition-transform duration-300 ease-out group-hover:translate-x-0.5" />
          </Link>
          <Link
            href="/docs/reference/api"
            className="inline-flex items-center gap-2 rounded-full border border-fd-border px-6 py-3 text-sm font-medium transition-all duration-300 ease-out hover:bg-fd-accent hover:border-fd-accent active:scale-[0.98]"
          >
            API Reference
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="mx-auto mt-24 w-full max-w-fd-container px-6">
        <div className="border-t border-fd-border pt-12 pb-10">
          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4">
            {/* Brand */}
            <div>
              <div className="flex items-center gap-2.5 mb-3">
                <Image
                  src="/icon.png"
                  alt="Nimbus"
                  width={28}
                  height={28}
                  className="rounded-md"
                />
                <span className="font-semibold tracking-tight">Nimbus</span>
              </div>
              <p className="text-sm text-fd-muted-foreground leading-relaxed">
                Lightweight Minecraft cloud system.
                <br />
                One JAR, zero complexity.
              </p>
            </div>

            {/* Docs */}
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fd-muted-foreground mb-3">
                Documentation
              </p>
              <ul className="space-y-2.5">
                {footerDocs.map((link) => (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      className="text-sm text-fd-muted-foreground transition-colors duration-200 hover:text-fd-foreground"
                    >
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>

            {/* Developer */}
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fd-muted-foreground mb-3">
                Developer
              </p>
              <ul className="space-y-2.5">
                {footerDev.map((link) => (
                  <li key={link.href}>
                    <Link
                      href={link.href}
                      className="text-sm text-fd-muted-foreground transition-colors duration-200 hover:text-fd-foreground"
                    >
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>

            {/* Community */}
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-fd-muted-foreground mb-3">
                Community
              </p>
              <ul className="space-y-2.5">
                {footerCommunity.map((link) => (
                  <li key={link.href}>
                    <a
                      href={link.href}
                      target="_blank"
                      rel="noreferrer noopener"
                      className="text-sm text-fd-muted-foreground transition-colors duration-200 hover:text-fd-foreground"
                    >
                      {link.label}
                    </a>
                  </li>
                ))}
              </ul>
            </div>
          </div>

          {/* Bottom bar */}
          <div className="mt-10 flex flex-col items-center gap-3 border-t border-fd-border pt-6 sm:flex-row sm:justify-between">
            <p className="text-xs text-fd-muted-foreground">
              &copy; {new Date().getFullYear()} NimbusPowered. Open source under MIT.
            </p>
            <p className="flex items-center gap-1.5 text-xs text-fd-muted-foreground">
              Built with <HeartIcon className="size-3 text-fd-primary" /> and Kotlin
            </p>
          </div>
        </div>
      </footer>
    </main>
  );
}
