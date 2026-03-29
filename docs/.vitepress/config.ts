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
      { text: 'Multi-Node', link: '/guide/multi-node' },
      { text: 'Config', link: '/config/nimbus-toml' },
      { text: 'Reference', link: '/reference/commands' },
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
            { text: 'Modpack Import', link: '/guide/modpacks' },
          ],
        },
        {
          text: 'Core',
          items: [
            { text: 'Core Concepts', link: '/guide/concepts' },
            { text: 'Proxy Setup', link: '/guide/proxy-setup' },
            { text: 'Server Groups', link: '/guide/server-groups' },
            { text: 'Scaling', link: '/guide/scaling' },
          ],
        },
        {
          text: 'Advanced',
          items: [
            { text: 'Multi-Node & Load Balancer', link: '/guide/multi-node' },
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
            { text: 'Permissions', link: '/config/permissions' },
            { text: 'Display', link: '/config/display' },
          ],
        },
      ],
      '/reference/': [
        {
          text: 'Reference',
          items: [
            { text: 'Commands', link: '/reference/commands' },
            { text: 'REST API', link: '/reference/api' },
            { text: 'WebSocket', link: '/reference/websocket' },
            { text: 'Events', link: '/reference/events' },
          ],
        },
      ],
      '/developer/': [
        {
          text: 'Developer',
          items: [
            { text: 'Architecture', link: '/developer/architecture' },
            { text: 'SDK', link: '/developer/sdk' },
            { text: 'Bridge Plugin', link: '/developer/bridge' },
            { text: 'Signs Plugin', link: '/developer/signs' },
          ],
        },
      ],
    },

    search: {
      provider: 'local',
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/your-org/nimbus' },
    ],

    footer: {
      message: 'Nimbus Cloud System',
      copyright: '© 2025 Jonas Laux',
    },

    outline: [2, 3],
  },
})
