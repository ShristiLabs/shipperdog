# ShipperDog user-docs site

VitePress application for the end-user documentation site (developer docs
stay in `docs/`). Architecture, information design, and the work-package
breakdown live in [PLAN.md](PLAN.md) — it is the contract for everything
built here.

## Requirements

- Node >= 22.12, npm (no pnpm/yarn — matches the repo's other apps)
- All dependencies are exact-pinned with a committed lockfile

### Dependency overrides

Two transitive pins in `package.json` keep the audit gate clean while the
direct pins stay on the mandated majors:

- `katex 0.18.11` — mermaid 11 declares katex ^0.16.47; every 0.16/0.17
  katex carries a high-severity prototype-pollution advisory
  (GHSA-238p-pmpm-9mq7, fix landed at 0.18.2). Mermaid 12 uses katex 0.18
  with the same wrapper, and katex is only loaded for math-in-diagrams,
  which this site's diagrams do not use.
- `vite 6.4.4` — vitepress 1.6.4 declares vite ^5.4.14; all vite <=6.4.2
  carry high-severity dev-server advisories (path traversal in optimized
  deps, launch-editor NTLMv2 disclosure, fs.deny bypass). Vitepress 1.6.x
  runs on vite 6; verified by build and dev-server smoke tests.

## Commands

```sh
npm install          # first time only
npm run docs:dev     # dev server at http://localhost:5173/shipperdog/snapshot/
npm run docs:build   # production build to .vitepress/dist/ (fails on dead links)
npm run docs:preview # serve the production build locally
```

## Versions and base paths

Every build targets one version subtree of the Pages site. The `DOCS_VERSION`
environment variable selects it and derives the VitePress `base`:

| DOCS_VERSION      | base path                  | deployed subtree |
| ----------------- | -------------------------- | ---------------- |
| `snapshot` (default) | `/shipperdog/snapshot/` | `snapshot/` on gh-pages |
| `v0.1` (WP2, from release tags) | `/shipperdog/v0.1/` | `v0.1/` on gh-pages |

A `snapshot` build also shows the "Unreleased — documentation for main"
banner (`.vitepress/theme/SnapshotBanner.vue`).

## CI and deployment

`.github/workflows/docs-site.yml` builds the site on every PR touching
`docs-site/**` (build-only; dead internal links fail the build) and, on push
to `main`, deploys the build to the `snapshot/` subtree of the `gh-pages`
branch — existing version subtrees are preserved (subtree replace, not force
push).

One-time setup (repo admin): enable GitHub Pages once —
Settings -> Pages -> Source "Deploy from a branch" -> Branch `gh-pages`,
`/ (root)`. Publishing to `gh-pages` works without this step; the site only
goes live at https://shristilabs.github.io/shipperdog/snapshot/ after Pages
is enabled.
