import { defineConfig } from 'vitepress'

export default defineConfig({
  base: '/Nimbus/',
  title: 'Nimbus',
  description: 'Lightweight Minecraft Cloud System',
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#0ea5e9' }],
    ['meta', { name: 'og:type', content: 'website' }],
    ['meta', { name: 'og:title', content: 'Nimbus - Lightweight Minecraft Cloud System' }],
    ['meta', { name: 'og:description', content: 'Dynamic server management from a single JAR. Auto-scaling, proxy integration, and a powerful API.' }],
  ],
  appearance: true,
  lastUpdated: false,
  themeConfig: {
    logo: { light: '/logo-light.svg', dark: '/logo-dark.svg' },
    siteTitle: 'Nimbus',

    nav: [
      { text: 'Home', link: '/' },
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'Config', link: '/config/nimbus-toml' },
      { text: 'API', link: '/reference/api' },
      { text: 'Developer', link: '/developer/architecture' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/introduction' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Quick Start', link: '/guide/quickstart' },
            { text: 'Core Concepts', link: '/guide/concepts' },
            { text: 'Console Commands', link: '/guide/commands' },
          ],
        },
        {
          text: 'Server Setup',
          items: [
            { text: 'Server Groups', link: '/guide/server-groups' },
            { text: 'Proxy Setup', link: '/guide/proxy-setup' },
            { text: 'Scaling', link: '/guide/scaling' },
            { text: 'Modpack Import', link: '/guide/modpacks' },
          ],
        },
        {
          text: 'Advanced',
          items: [
            { text: 'Multi-Node & Load Balancer', link: '/guide/multi-node' },
            { text: 'Bedrock / Geyser', link: '/guide/bedrock' },
            { text: 'Permissions', link: '/guide/permissions' },
            { text: 'Stress Testing', link: '/guide/stress-testing' },
          ],
        },
      ],
      '/config/': [
        {
          text: 'Configuration',
          items: [
            { text: 'nimbus.toml', link: '/config/nimbus-toml' },
            { text: 'Groups', link: '/config/groups' },
            { text: 'Templates', link: '/config/templates' },
          ],
        },
        {
          text: 'Modules',
          items: [
            { text: 'Permissions', link: '/config/permissions' },
            { text: 'Display (Signs & NPCs)', link: '/config/display' },
          ],
        },
      ],
      '/reference/': [
        {
          text: 'API Reference',
          items: [
            { text: 'REST API', link: '/reference/api' },
            { text: 'WebSocket', link: '/reference/websocket' },
            { text: 'Events', link: '/reference/events' },
          ],
        },
      ],
      '/developer/': [
        {
          text: 'Overview',
          items: [
            { text: 'Architecture', link: '/developer/architecture' },
          ],
        },
        {
          text: 'Plugins',
          items: [
            { text: 'SDK (nimbus-sdk)', link: '/developer/sdk' },
            { text: 'Bridge (nimbus-bridge)', link: '/developer/bridge' },
            { text: 'Display (nimbus-display)', link: '/developer/display' },
            { text: 'Permissions (nimbus-perms)', link: '/developer/perms' },
          ],
        },
        {
          text: 'Internals',
          items: [
            { text: 'Agent (nimbus-agent)', link: '/developer/agent' },
            { text: 'Protocol (nimbus-protocol)', link: '/developer/protocol' },
          ],
        },
      ],
    },

    search: {
      provider: 'local',
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/kryonixmc/Nimbus' },
    ],

    footer: {
      message: 'Nimbus Cloud System',
      copyright: '© 2025-2026 Jonas Laux',
    },

    outline: [2, 3],
  },
})
