import Link from 'next/link';
import {
  ArrowRight,
  CloudIcon,
  GlobeIcon,
  LayersIcon,
  PackageIcon,
  ShieldIcon,
  TerminalIcon,
  ZapIcon,
} from 'lucide-react';
import { InstallTabs } from './install-tabs';

export default function Page() {
  return (
    <main className="pt-4 pb-16">
      {/* Hero Card */}
      <div className="relative flex min-h-[500px] h-[60vh] max-h-[700px] border border-fd-border rounded-2xl overflow-hidden mx-auto w-full max-w-[1400px]">
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
            Dynamic server management from a single JAR — auto-scaling, multi-node
            clusters, and a powerful API without the bloat.
          </p>

          <div className="flex flex-row items-center gap-4 flex-wrap w-fit">
            <Link
              href="/docs/guide/quickstart"
              className="group inline-flex items-center justify-center gap-2 px-5 py-3 rounded-full font-medium tracking-tight transition-all duration-300 ease-out text-sm bg-fd-primary text-fd-primary-foreground hover:shadow-lg hover:shadow-fd-primary/25 hover:scale-[1.02] active:scale-[0.98]"
            >
              Get Started
              <ArrowRight className="size-4 transition-transform duration-300 ease-out group-hover:translate-x-0.5" />
            </Link>
            <a
              href="https://github.com/jonax1337/Nimbus"
              target="_blank"
              rel="noreferrer noopener"
              className="inline-flex items-center justify-center gap-2 px-5 py-3 rounded-full font-medium tracking-tight transition-all duration-300 ease-out text-sm border border-fd-border bg-fd-secondary text-fd-secondary-foreground hover:bg-fd-accent hover:border-fd-accent active:scale-[0.98]"
            >
              GitHub
            </a>
          </div>

          <div className="mt-auto mb-4 w-full max-w-xl max-md:px-2">
            <InstallTabs />
          </div>
        </div>
      </div>

      {/* Section heading */}
      <div className="mx-auto mt-20 mb-8 max-w-5xl px-6 text-center">
        <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          Everything you need.
        </h2>
        <p className="mt-2 text-fd-muted-foreground">
          From auto-scaling to API access — Nimbus handles the infrastructure.
        </p>
      </div>

      {/* Features */}
      <section className="mx-auto grid max-w-5xl gap-4 px-6 sm:grid-cols-2 lg:grid-cols-3">
        {[
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
            desc: 'Paper, Purpur, Velocity, Forge, Fabric, NeoForge — JARs downloaded automatically.',
          },
          {
            icon: <ShieldIcon className="size-5" />,
            title: 'Built-in Permissions',
            desc: 'Groups, inheritance, tracks, audit log — central DB, no external plugins.',
          },
          {
            icon: <TerminalIcon className="size-5" />,
            title: 'REST API + WebSocket',
            desc: 'Live events, bidirectional console, and full control over your network.',
          },
        ].map((f) => (
          <div
            key={f.title}
            className="group flex gap-3 rounded-xl border border-fd-border bg-fd-card p-5 transition-all duration-300 ease-out hover:border-fd-primary/30 hover:shadow-md hover:shadow-fd-primary/5 hover:-translate-y-0.5"
          >
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-fd-primary/10 text-fd-primary transition-colors duration-300 ease-out group-hover:bg-fd-primary/20">
              {f.icon}
            </div>
            <div>
              <p className="font-medium text-sm">{f.title}</p>
              <p className="mt-1 text-xs text-fd-muted-foreground leading-relaxed">
                {f.desc}
              </p>
            </div>
          </div>
        ))}
      </section>

      {/* Platforms */}
      <section className="mx-auto mt-20 max-w-5xl px-6 text-center">
        <p className="text-sm font-medium text-fd-muted-foreground mb-5">
          Supports 9 server platforms
        </p>
        <div className="flex flex-wrap justify-center gap-2">
          {['Paper', 'Purpur', 'Pufferfish', 'Folia', 'Velocity', 'Forge', 'Fabric', 'NeoForge', 'Vanilla'].map(
            (name) => (
              <span
                key={name}
                className="rounded-full border border-fd-border px-3.5 py-1.5 text-xs font-medium transition-colors duration-300 ease-out hover:border-fd-primary/30 hover:text-fd-primary"
              >
                {name}
              </span>
            ),
          )}
        </div>
      </section>

      {/* CTA */}
      <section className="relative mx-auto mt-20 max-w-3xl overflow-hidden rounded-2xl border border-fd-border px-6 py-14 text-center">
        <div className="absolute inset-0 -z-10">
          <div className="absolute inset-0 bg-gradient-to-t from-fd-primary/5 via-transparent to-transparent" />
        </div>

        <CloudIcon className="mx-auto size-10 text-fd-primary mb-4" />
        <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          Up and running in minutes.
        </h2>
        <p className="mx-auto mt-3 max-w-md text-fd-muted-foreground">
          One command to install, one JAR to run. No Docker, no databases — just
          Java 21 and your network is live.
        </p>
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/docs/guide/quickstart"
            className="group inline-flex items-center gap-2 rounded-full bg-fd-primary px-5 py-2.5 text-sm font-medium text-fd-primary-foreground transition-all duration-300 ease-out hover:shadow-lg hover:shadow-fd-primary/25 hover:scale-[1.02] active:scale-[0.98]"
          >
            Quick Start
            <ArrowRight className="size-4 transition-transform duration-300 ease-out group-hover:translate-x-0.5" />
          </Link>
          <Link
            href="/docs/reference/api"
            className="inline-flex items-center gap-2 rounded-full border border-fd-border px-5 py-2.5 text-sm font-medium transition-all duration-300 ease-out hover:bg-fd-accent hover:border-fd-accent active:scale-[0.98]"
          >
            API Reference
          </Link>
        </div>
      </section>
    </main>
  );
}
