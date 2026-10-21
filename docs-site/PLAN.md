# ShipperDog End-User Documentation Site — Plan

Status: **delivered** (2026-10). Work packages WP1–WP7 landed sequentially,
one PR each — see the completion note at the end. This document is now the
as-built record; the page inventory below reflects what shipped, including
the two deliberate deviations noted alongside it.

## 1. Who the users are

ShipperDog has three delivery surfaces (Console web app, Tester Portal mobile web,
CLI/CI) and a role catalog that maps cleanly to nine documentation personas:

| # | Persona | Roles (system) | Primary surface | What they come for |
|---|---|---|---|---|
| 1 | **Platform Operator** | `platform_admin` | Console + Terraform | Org onboarding, instance health, cross-tenant ops, break-glass |
| 2 | **Organization Admin** | `ORG_ADMIN` | Console | Members & roles, tenants, IdP/SCIM/SSO config, channel credentials, ABAC/SIEM policies |
| 3 | **Release Manager** | `RELEASE_MANAGER` | Console + CLI | Release creation → approvals → gates → ring promotion → staged rollout → rollback |
| 4 | **App Owner / Product** | `APP_OWNER` | Console | App registry, ownership scope, product approvals, release manifests, dashboards |
| 5 | **Developer** | `DEVELOPER` | CLI + CI + Console | Artifact upload w/ provenance, versions & tags, CI actions (GHA/Jenkins/GitLab), gate feedback |
| 6 | **QA Engineer** | `QA_ENGINEER` | Console + Portal admin views | Tester groups, feedback triage, crash-free gates, device fleet |
| 7 | **Security / Compliance Reviewer** | `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER` | Console | Security feedback, approval signatures, audit trail, SIEM streams |
| 8 | **Tenant Admin** | `TENANT_ADMIN` | Console (tenant-scoped) | Tenant members, devices, tenant dashboard, app visibility |
| 9 | **Tester** ⭐ | `TENANT_TESTER` | **Portal (+ companion app)** | Accept invite → browse apps → install builds → submit feedback; the only true end-user persona |

⭐ The Tester is the persona with zero Console access — the Portal track must be
fully standalone (invite email → first install in under 5 minutes).

## 2. Site architecture (VitePress, versioned, mermaid)

### 2.1 Repo & tooling

- **Location:** `docs-site/` (developer docs stay in `docs/`)
- **Stack:** VitePress (pinned) + `vitepress-plugin-mermaid` + `mermaid` v11;
  local search (built-in minisearch — zero external service)
- **Branding:** the corgi — `docs/assets/brand/` as logo/mark in navbar, favicon
  set, portal-blue accent

### 2.2 Versioning strategy

VitePress has no first-class versioning (vuejs/vitepress#109). Pattern:

```
https://<org>.github.io/shipperdog/
├── snapshot/        ← built from main on every merge (docs paths only)
├── v0.1/            ← built from the v0.1 release tag — immutable after publish
├── v0.2/            ← ditto
├── /                ← redirect page to newest stable (a copied root breaks — absolute hashed asset URLs)
└── versions.json    ← [{id:"snapshot"},{id:"v0.2"},{id:"v0.1"}] + which is "latest"
```

- **Build rule:** each trigger builds exactly ONE version subtree (never rebuilds
  old versions); `actions-gh-pages`-style deploy with keep-files preserves prior
  subtrees on the `gh-pages` branch
- **Switcher:** a small custom navbar Vue component (~100 lines, no third-party
  dep) reading `versions.json`; on switch it maps the current page path onto the
  target version and falls back to that version's home if the page does not
  exist there
- **Alternative evaluated:** `@viteplus/versions` (npm) ships a ready-made
  multiversion build + switcher — decision at WP2: DIY component preferred (one
  less dependency, full control of path-mapping behavior)
- **Snapshot banner:** `snapshot/` build injects an "Unreleased — docs for main"
  banner via VitePress theme config
- **Publish flow:** version tag → workflow builds `/vX.Y/` → re-copies it to `/`
  → regenerates `versions.json` → single Pages deploy

### 2.3 CI (`docs-site.yml`)

- **Trigger A** — push to `main` touching `docs-site/**`: build+deploy `snapshot/` only
- **Trigger B** — release tag `v*`: build+deploy `vX.Y/` + refresh root copy + `versions.json`
- **PR validation:** build-only (no deploy) when `docs-site/**` changed —
  `vitepress build` catches dead internal links natively
- Concurrency-cancel, no PR matrix — per standing ShristiLabs CI preferences

## 3. Information architecture & page inventory

```
docs-site/
├── .vitepress/  (config, theme, VersionSwitcher.vue, versions workflow)
├── public/      (brand assets, versions.json)
└── <version-root>/
    ├── index.md                    Hero: what ShipperDog is, role cards
    ├── get-started/
    │   ├── concepts.md             Org→Tenant→App→Version→Artifact model (mermaid)
    │   ├── platform-tour.md        Console walk-through (screenshots)
    │   └── five-minute-install.md  The tester golden path (mermaid sequence)
    ├── roles/                      One quickstart per persona (9 pages)
    │   ├── platform-operator.md  org-admin.md  release-manager.md  app-owner.md
    │   ├── developer.md  qa-engineer.md  security-reviewer.md  tenant-admin.md
    │   └── tester.md               ⭐ the standalone portal track
    ├── how-to/
    │   ├── console/                19 task guides: orgs · tenants · apps ·
    │   │   versions+tags · releases · approvals · quality-gates ·
    │   │   rings+promotion · staged-rollout · rollback · feedback-triage ·
    │   │   devices · analytics+dashboards · notifications ·
    │   │   distribution-channels · abac-policies · sso-oidc-saml ·
    │   │   scim-provisioning · siem-streams
    │   │   (as built: audit-log folded into siem-streams — both surfaces
    │   │   live on one console screen; environments have no dedicated
    │   │   screen — folded as mentions into releases/channels/policies)
    │   ├── portal/                 Tester how-tos (5): accept-invite ·
    │   │   browse-apps · install-ios · install-android · feedback
    │   └── automation/             CLI + CI (6): cli-reference · github-actions ·
    │       jenkins · gitlab · webhooks · companion-app
    ├── concepts/                   Deep dives (10)
    │   ├── architecture.md · tenancy-isolation.md · release-lifecycle.md ·
    │   ├── rings-and-rollout.md · approvals-gates.md · artifact-pipeline.md ·
    │   ├── authorization.md · identity-sso.md · feedback-analytics.md ·
    │   └── security-model.md
    └── reference/
        ├── glossary.md · error-codes.md (user-facing subset) · faq.md
        └── troubleshooting.md      Top 10 support scenarios
```

≈ 50 pages across versions (as built: 62 in the snapshot tree — 58 through
WP6, plus the four reference pages of WP7; only `snapshot/` edits in-tree,
releases are frozen copies).

Post-delivery additions: the OTA updates docs tree (issues #195-#199,
tracking the OTA Updates milestone that shipped as PRs #179-#194)
lands on top of the WP1-WP7 inventory rather than forking it.
Landed: the concepts page plus reference/navigation plumbing (#195,
PR #200 — this file, `concepts/ota-updates.md` in the sidebar after
Rings and rollout with its device-flow sequence diagram, the glossary
OTA terms, the error-codes OTA section, three FAQ entries, four
cross-links from existing pages), then the console OTA how-tos (#196 —
`how-to/console/ota-bundles.md` and `ota-rollouts.md` in the console
sidebar after Roll back a release, with an upload-chain flowchart and
a rollout-standing state diagram, the concept page's how-to links,
and cross-links from staged-rollout and analytics-and-dashboards).
Pending: the SDK how-to at `how-to/ota-sdk.md` (#197), the CLI
reference regen (#198), and the screenshot pass (#199). Recorded here
the same way the audit-log and environments folds were: the inventory
above stays the as-built WP1-WP7 record; this note is the running
addendum.

## 4. Mermaid diagram inventory (14)

| Diagram | Type | Placed in |
|---|---|---|
| Platform architecture | flowchart TB | concepts/architecture |
| Tenancy model | flowchart | get-started/concepts |
| Release lifecycle (11 edges, 3 terminals) | stateDiagram-v2 | concepts/release-lifecycle |
| Artifact validation FSM | stateDiagram-v2 | concepts/artifact-pipeline |
| Ring promotion walk | flowchart LR | concepts/rings-and-rollout |
| Approval engine (ALL/ANY/conditional) | flowchart | concepts/approvals-gates |
| Authorization decision chain (7 layers) | flowchart | concepts/authorization |
| Tester golden path | sequenceDiagram | get-started/five-minute-install |
| Signed-URL download issuance | sequenceDiagram | concepts/security-model |
| OIDC + SAML login flows | sequenceDiagram ×2 | concepts/identity-sso |
| SCIM provisioning/leaver cascade | sequenceDiagram | concepts/identity-sso |
| Feedback→metric→gate→promotion closed loop | flowchart | concepts/feedback-analytics |
| Staged tenant rollout + rollback | flowchart | how-to/console/staged-rollout |
| CI integration topology | flowchart | how-to/automation |

## 5. Work packages

1. **WP1 — Scaffold:** `docs-site/` VitePress app, theme + brand, mermaid wiring,
   local search, snapshot banner, build CI (PR build-only), Pages deploy of `snapshot/`
2. **WP2 — Versioning:** version-switcher component, `versions.json` contract,
   tag-triggered `vX.Y/` build + root-copy refresh, keep-files Pages deploy, runbook
3. **WP3 — Foundations:** concepts/ (all 10, with diagrams 1–8, 14) + get-started/
4. **WP4 — Role quickstarts:** the 9 role pages, each ending in a hand-off task list
5. **WP5 — Tester portal track:** portal how-tos + companion page, mobile-screenshot
   set (Playwright, phone viewport)
6. **WP6 — Console + automation how-tos:** remaining ~21 pages, CLI reference
   generated from `--help`
7. **WP7 — Reference + polish:** glossary, FAQ, troubleshooting, error-code subset,
   full-site review pass, OG image

Each WP lands as its own PR. Content sources of truth: the API's OpenAPI baseline
and `docs/` (developer docs) — user docs never duplicate developer internals.

## Sources

- vuejs/vitepress#109 — Versioning
- VitePress Routing (base/subdirectory builds) — https://vitepress.dev/guide/routing
- VitePress Default Theme Nav — https://vitepress.dev/reference/default-theme-nav
- @viteplus/versions (npm) — https://www.npmjs.com/package/@viteplus/versions
- Lando Multiversion VitePress Build — https://vitepress-theme-default-plus.lando.dev/guides/multiversion-vitepress-build.html
- SQLFluff versioned docs hosting — https://docs.sqlfluff.com/en/stable/development/versioned-docs-hosting

## Completion note

Delivered 2026-10, one PR per work package:

| WP | PR | Scope shipped |
|---|---|---|
| WP1 Scaffold | [#149](https://github.com/ShristiLabs/shipperdog/pull/149) | VitePress app, theme + corgi brand, mermaid, local search, snapshot banner, CI, Pages deploy |
| WP2 Versioning | [#151](https://github.com/ShristiLabs/shipperdog/pull/151) | Version switcher, `versions.json` contract, tag builds + root-copy refresh, runbook |
| WP3 Foundations | [#152](https://github.com/ShristiLabs/shipperdog/pull/152) | All ten concept pages with diagrams, get-started track |
| WP4 Role quickstarts | [#153](https://github.com/ShristiLabs/shipperdog/pull/153) | The nine persona pages with real screenshots |
| WP5 Tester portal track | [#154](https://github.com/ShristiLabs/shipperdog/pull/154) | Portal how-tos, mobile screenshot set, companion page |
| WP6 Console + automation | [#157](https://github.com/ShristiLabs/shipperdog/pull/157) | 19 console how-tos, CLI reference, CI pages, webhooks |
| WP7 Reference + polish | [#158](https://github.com/ShristiLabs/shipperdog/pull/158) | Glossary, error-code subset, FAQ, troubleshooting, full-site review pass, OG image + meta |

Deviations from the original inventory, both recorded at delivery time:
the audit-log page folded into siem-streams (WP6, one console screen), and
environments folded as mentions (WP6, no dedicated screen). All other
sections shipped as planned.
