import { withMermaid } from 'vitepress-plugin-mermaid';

// VitePress configuration for the ShipperDog user-docs site.
// Site architecture contract: docs-site/PLAN.md (sections 2 and 3).
//
// Versioning (PLAN 2.2): every build targets exactly one version subtree of
// the GitHub Pages site. DOCS_VERSION selects it — 'snapshot' (default, built
// from main) or, from WP2 onward, a release tag like 'v0.1'. The VitePress
// base must equal the deployed subtree path so assets and links resolve.
const version = process.env.DOCS_VERSION ?? 'snapshot';
const base = `/shipperdog/${version}/`;

declare module 'vitepress' {
  interface ThemeConfig {
    /** Show the "Unreleased" banner on snapshot builds (PLAN 2.2). */
    snapshotBanner?: boolean;
  }
}

// Mermaid (PLAN 2.1): diagrams render client-side; the plugin lazy-loads
// mermaid in the browser only, so the SSR/build pass never imports it.
// mermaid 11 imports the CommonJS fastdom package with a default import;
// without pre-bundling that breaks ESM interop in the dev server, so it
// joins the plugin's own optimizeDeps list (dayjs, cytoscape, ...).
export default withMermaid({
  vite: {
    optimizeDeps: {
      include: ['fastdom', 'fastdom/extensions/fastdom-promised.js'],
    },
  },
  base,
  srcExclude: ['README.md', 'PLAN.md', 'VERSIONING.md'],
  cleanUrls: true,
  title: 'ShipperDog Docs',
  description:
    'End-user documentation for ShipperDog — enterprise mobile beta distribution and release management across the Console, the Tester Portal, and the CLI/CI.',
  head: [
    ['link', { rel: 'icon', type: 'image/png', sizes: '32x32', href: `${base}favicon-32.png` }],
    ['link', { rel: 'icon', type: 'image/png', sizes: '16x16', href: `${base}favicon-16.png` }],
    ['link', { rel: 'apple-touch-icon', sizes: '180x180', href: `${base}apple-touch-icon.png` }],
  ],
  themeConfig: {
    siteTitle: 'ShipperDog',
    logo: '/logo-mark-192.png',
    nav: [
      { text: 'Get Started', link: '/get-started/' },
      { text: 'Roles', link: '/roles/' },
      { text: 'How-To', link: '/how-to/' },
      { text: 'Concepts', link: '/concepts/' },
      { text: 'Reference', link: '/reference/' },
    ],
    sidebar: [
      {
        text: 'Get Started',
        items: [
          { text: 'Overview', link: '/get-started/' },
          { text: 'Core concepts', link: '/get-started/concepts' },
          { text: 'Platform tour', link: '/get-started/platform-tour' },
          { text: 'Five-minute install', link: '/get-started/five-minute-install' },
        ],
      },
      {
        text: 'Roles',
        items: [
          { text: 'All roles', link: '/roles/' },
          { text: 'Platform Operator', link: '/roles/platform-operator' },
          { text: 'Organization Admin', link: '/roles/org-admin' },
          { text: 'Release Manager', link: '/roles/release-manager' },
          { text: 'App Owner', link: '/roles/app-owner' },
          { text: 'Developer', link: '/roles/developer' },
          { text: 'QA Engineer', link: '/roles/qa-engineer' },
          { text: 'Security / Compliance Reviewer', link: '/roles/security-reviewer' },
          { text: 'Tenant Admin', link: '/roles/tenant-admin' },
          { text: 'Tester', link: '/roles/tester' },
        ],
      },
      {
        text: 'How-To',
        items: [
          { text: 'Overview', link: '/how-to/' },
          {
            text: 'Tester Portal',
            collapsed: false,
            items: [
              { text: 'Accept an invitation', link: '/how-to/portal/accept-invite' },
              { text: 'Browse your applications', link: '/how-to/portal/browse-apps' },
              { text: 'Install on iOS', link: '/how-to/portal/install-ios' },
              { text: 'Install on Android', link: '/how-to/portal/install-android' },
              { text: 'Submit feedback', link: '/how-to/portal/feedback' },
            ],
          },
          {
            text: 'Automation',
            collapsed: false,
            items: [{ text: 'Android companion app', link: '/how-to/automation/companion-app' }],
          },
        ],
      },
      {
        text: 'Concepts',
        items: [
          { text: 'Overview', link: '/concepts/' },
          { text: 'Architecture', link: '/concepts/architecture' },
          { text: 'Tenancy and isolation', link: '/concepts/tenancy-isolation' },
          { text: 'Release lifecycle', link: '/concepts/release-lifecycle' },
          { text: 'Rings and rollout', link: '/concepts/rings-and-rollout' },
          { text: 'Approvals and gates', link: '/concepts/approvals-gates' },
          { text: 'Artifact pipeline', link: '/concepts/artifact-pipeline' },
          { text: 'Authorization', link: '/concepts/authorization' },
          { text: 'Identity and SSO', link: '/concepts/identity-sso' },
          { text: 'Feedback and analytics', link: '/concepts/feedback-analytics' },
          { text: 'Security model', link: '/concepts/security-model' },
        ],
      },
      {
        text: 'Reference',
        items: [{ text: 'Overview', link: '/reference/' }],
      },
    ],
    search: {
      provider: 'local',
    },
    snapshotBanner: version === 'snapshot',
    footer: {
      copyright: 'ShristiLabs',
    },
  },
});
