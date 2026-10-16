# Docs versioning runbook

How documentation versions are cut, deployed, and repaired. Architecture
context: [PLAN.md](PLAN.md) §2.2. The live layout is:

```
https://shristilabs.github.io/shipperdog/
├── snapshot/        Trigger A — rebuilt from main on every docs-site push
├── v0.1/, v0.2/...  Trigger B — one frozen subtree per release tag
├── /                REAL COPY of the newest stable release (no symlinks)
└── versions.json    the switcher manifest (workflow-generated, never hand-edited)
```

## The version model

- **`snapshot/`** tracks main. Every push to `main` touching `docs-site/**`
  redeploys it and refreshes `versions.json` (Trigger A in
  `.github/workflows/docs-site.yml`).
- **`vX.Y/`** is frozen at a release tag (`v0.1`, `v0.2`, ...). Each tag
  push builds exactly ONE subtree; **released subtrees are immutable — they
  are never rebuilt** by any later trigger. Fixes to a released version go
  through the bad-publish procedure below.
- **`/` (root)** is a plain file copy of the newest stable `vX.Y/` so the
  site root serves stable docs (GitHub Pages does not follow symlinks).
- **`versions.json`** at the gh-pages root drives the navbar switcher:
  `{ "latest": "v0.2", "versions": [{ "id": "snapshot", "label": "SNAPSHOT (main)" }, { "id": "v0.2", "label": "v0.2" }, ...] }`
  — snapshot first, releases newest-first.

## Cutting a version (the tag flow)

1. Tag the release commit whose docs you want frozen:
   `git tag v0.1 && git push origin v0.1`.
2. The tag push (`v*`) starts `docs-site-ci` Trigger B, which:
   - resolves `DOCS_VERSION=v0.1` from the tag name (validated against the
     id grammar `^v[0-9]+(\.[0-9]+)+$`);
   - builds with VitePress `base=/shipperdog/v0.1/` (banner off);
   - rsyncs the build into `v0.1/` on `gh-pages` (subtree replace — old
     versions untouched);
   - real-copies the same build to the gh-pages root, excluding
     `snapshot/`, `v*/`, `versions.json`, `.nojekyll` so siblings survive;
   - regenerates `versions.json` via `docs-site/scripts/regen-versions.mjs`
     (preserves existing entries, adds the new id, sets `latest` to the
     highest release);
   - pushes gh-pages once.
3. Verify: `https://shristilabs.github.io/shipperdog/` serves the new
   version, `v0.1/` responds, and `/versions.json` lists it.

`versions.json` evolution example — after `v0.1`, then `v0.2`:

```json
{ "latest": "v0.1", "versions": [ { "id": "snapshot", "label": "SNAPSHOT (main)" }, { "id": "v0.1", "label": "v0.1" } ] }
{ "latest": "v0.2", "versions": [ { "id": "snapshot", "label": "SNAPSHOT (main)" }, { "id": "v0.2", "label": "v0.2" }, { "id": "v0.1", "label": "v0.1" } ] }
```

Before any release exists, Trigger A seeds
`{ "latest": null, "versions": [snapshot] }` so the switcher works on
snapshot alone.

## The tag-flow test path (no real release)

Trigger B can be exercised without cutting a release: Actions ->
`docs-site-ci` -> Run workflow -> version input `v0.0-docs-test` (the
transient id grammar is `^v[0-9]+\.[0-9]+-docs-test$` — two segments
only, so longer ids like `v1.2.3-docs-test` are rejected before any
deploy step runs; it deploys, is listed in `versions.json`, but never
becomes `latest`). Alternatively push a throwaway tag `v0.0-docs-test`
and delete it afterwards. The `-docs-test` subtree is a test artifact —
clean it up:

```sh
git fetch origin gh-pages
git worktree add --detach /tmp/gh-pages-cleanup origin/gh-pages
cd /tmp/gh-pages-cleanup
git rm -r v0.0-docs-test/
# Restore the root copy from the newest stable release — before the first
# real release, restore it from snapshot/ instead. Same excludes as
# Trigger B's root copy, so sibling subtrees and versions.json survive:
rsync -a --delete \
  --exclude='.git' --exclude='.nojekyll' --exclude='versions.json' \
  --exclude='snapshot' --exclude='v[0-9]*' \
  v0.1/ ./                        # newest stable; use snapshot/ pre-first-release
node <repo>/docs-site/scripts/regen-versions.mjs . snapshot   # drops the test entry
git add -A && git commit -m "docs: remove v0.0-docs-test test subtree"
git push origin HEAD:gh-pages
```

After the cleanup the root serves the restored copy (the newest stable
release, or the snapshot before the first real release) — never a 404 —
and `versions.json` is back to its pre-test state, snapshot-only until a
release exists.

## Fixing a bad publish

Published versions are immutable, so a bad release subtree is replaced, not
patched in place by CI:

1. **Bad subtree, version stays** (e.g. build glitch): delete the subtree
   and re-push the SAME tag after fixing the tag target — or cherry-pick
   the intended docs state onto a new commit, re-tag `vX.Y+1`. Never move
   an already-published tag silently; releases are frozen copies.
2. **Remove a version entirely** (e.g. released by mistake):
   ```sh
   git worktree add --detach /tmp/gh-pages-fix origin/gh-pages
   cd /tmp/gh-pages-fix
   git rm -r v0.9/
   node <repo>/docs-site/scripts/regen-versions.mjs . snapshot
   # restore the root copy from the new latest (here v0.1):
   rm -rf assets get-started how-to concepts roles reference index.html 404.html hashmap.json vp-icons.css favicon-*.png apple-touch-icon.png logo-mark-192.png
   rsync -a v0.1/ ./ --exclude versions.json --exclude snapshot --exclude 'v[0-9]*'
   git add -A && git commit -m "docs: remove bad v0.9 publish" && git push origin HEAD:gh-pages
   ```
3. **Corrupt `versions.json`** (regen refuses to clobber an unparseable
   file — it exits non-zero on purpose): reconstruct it by hand once, then
   re-run `regen-versions.mjs <gh-pages-root> snapshot` to normalize.

## Housekeeping

- **Drop the `vite` override** in `docs-site/package.json` when vitepress
  widens its declared range past `^5.4.14` to cover secure vite releases
  (the override pins 6.4.4 for dev-server advisories; see README
  "Dependency overrides"). Then `npm install`, re-audit, rebuild.
- The `katex` override has the same shape — drop it when mermaid ships a
  katex range that audits clean.
- `docs-site/public/versions.json` is a local-dev fixture for the
  VersionSwitcher (the dev server has no site root); production always
  reads the gh-pages root manifest. CI strips the fixture from the build
  output before every deploy (the "Strip dev-only versions.json fixture"
  step in `.github/workflows/docs-site.yml`), so no deployed subtree ever
  carries a stale manifest copy. Keep the fixture plausible, never treat
  it as the source of truth.
