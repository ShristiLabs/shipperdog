# Admin Console (S2-04, S3-04, S9-05)

The admin console is the React single-page application in `frontend/`
(PRD §21 stack): React 19, TypeScript (strict), MUI 9 with MUI X Data
Grid, React Query 5, React Router 7, built with Vite 7. S2-04 delivers
the scaffold plus tenant administration — list, create, edit,
deactivate, and member management — and the conventions every later
console story (E3 onward) reuses. S3-04 adds the application console on
those conventions: the registry listing, create/edit, the shared-app
authorization screen, and the reversible archive. S9-05 adds the
release dashboard (PRD §32): the application-scoped release listing and
the per-release screen a Release Manager judges release health from.

## Running locally

- **Compose (full stack):** `docker compose up` serves the console at
  http://localhost:5173 (nginx container proxying `/api` to the api
  service — one origin, no CORS). Sign in with the seeded dev admin
  ([authentication](authentication.md), local only).
- **Dev server (frontend only):** `cd frontend && npm install && npm run
  dev` — Vite on :5173 proxying `/api` to `:8080` and `/actuator` to
  the management port `:8081`.
- **Gate:** `npm run lint` (eslint + typescript-eslint + react-hooks),
  `npm run build` (strict `tsc` typecheck + bundle), `npm test`
  (Vitest; happy-dom environment). Node >= 22.12.

## Structure

```
frontend/src/
  api/          client.ts (fetch wrapper + apiFetchText for non-JSON
                bodies), problem.ts (RFC 7807 decoding), auth.ts,
                tenants.ts, organizations.ts, applications.ts
                (registry + shared-app authorization), releases.ts
                + versions.ts (the S9-05 dashboard surfaces)
  auth/         tokenStore.ts, AuthContext.tsx, RequireAuth.tsx
  components/   ToastContext.tsx (error toast convention)
  hooks/        usePermission.ts (role-aware navigation probes),
                useOrganizationDirectory.ts (walks org tenants/members
                rosters across cursor pages for pickers and name maps;
                since S9-05 also the application registry and
                per-application version walks)
  layout/       ConsoleLayout.tsx (app bar + nav drawer)
  pages/        LoginPage, TenantsPage, TenantMembersPage, tenantRules,
                ApplicationsPage, ApplicationDetailPage,
                ApplicationDialog (create/edit form),
                applicationRules (client mirrors of field rules),
                ReleasesPage, ReleaseDetailPage, releaseRules
                (PRD 17 state-vocabulary mirrors; S9-05)
  test/         harness.tsx (providers + fetch router), setup.ts
frontend/e2e/   console-verify.mjs, applications-verify.mjs,
                releases-verify.mjs + apk-fixture.mjs (Playwright
                smokes vs compose stack)
```

## Conventions fixed by this scaffold

### API access only through the client

Screens never call `fetch` directly. `apiFetch`/`pageFetch`
(`api/client.ts`) own the cross-cutting contract from
[API conventions](api-conventions.md):

- base path `/api/v1`, same-origin;
- every request carries a fresh `X-Correlation-Id` (shown on error
  toasts so a failure joins to server logs and the audit trail);
- errors are the problem+json envelope, decoded to `ApiError` with the
  stable `code`, registry `detail`, `traceId`, and the 400 `errors`
  field map;
- list calls use `useInfiniteQuery` with the opaque `next_cursor` and a
  Load more control — cursors are never parsed, `limit` stays 1-100.
- non-JSON bodies (the release manifest's `format=yaml` render) go
  through `apiFetchText` — the same contract (correlation id, bearer,
  single-flight 401 refresh and retry, problem+json error decoding),
  returning text instead of parsed JSON.

### Auth and token lifecycle

Login stores the rotated token pair from
[authentication](authentication.md) in `localStorage` under one key
(`sd.console.session`, atomic on login/logout/refresh). The access
token is 15 minutes; `AuthContext` arms a silent refresh one minute
before expiry, and a 401 on any business call triggers one
single-flight refresh plus a single retry — concurrent 401s share one
refresh, honoring single-use rotation. A failed refresh clears the
session and returns the user to the login screen. `GET /auth/me`
participates in the recovery like any business call (the #35 review
finding, fixed in S8-05): a reload with an expired access token but a
live refresh token recovers transparently instead of logging the
operator out — only the raw auth POSTs (login, register, refresh,
logout) never enter the retry loop.

**Storage trade-off (accepted for M1, recorded in
[ADR-0006](adr/ADR-0006-portal-session-token-storage.md)):**
`localStorage` survives reloads and multiple tabs but is readable by
injected scripts. The API never trusts the client: authorization is
re-evaluated live per request ([authorization](authorization.md)),
and refresh rotation + family revocation bound what a stolen token is
worth. The S8-05 hardening ships a strict CSP (`script-src 'self'`,
no inline scripts) on the console origin — the XSS vector that would
make theft trivial is what the policy eliminates. The cookie-session
migration path is recorded in the ADR, not built.

### Role-aware navigation (PRD §26)

Access tokens deliberately carry no role claims, and the console never
caches roles. Each navigation section probes the exact endpoint it
would load (`usePermission`); a permitted 200 shows the section, a
concealed 404 or opaque 403 hides it. The backend keeps enforcing
every call regardless — the probe decides what renders, never what is
allowed.

### Screens

- **Login** (`/login`): opaque failure message, post-login redirect to
  the intended route; deep links to console pages bounce here when
  anonymous (`RequireAuth`).
- **Tenants** (`/tenants`): data grid with client-side search (name or
  external id), server-side status filter, create/edit dialogs whose
  field rules mirror the API (`tenantRules.ts`: name 1-200, externalId
  1-100 after trim), and a reversible deactivate behind a confirmation
  naming the tenant.
- **Tenant members** (`/tenants/:id/members`): roster with role and
  membership status chips (suspend/reactivate revokes sessions
  server-side), role changes, removal with confirmation, and an add
  dialog offering the organization roster for pickup plus a raw user-id
  entry for tenant testers outside the organization; the role picker is
  fixed to the M1 roles (Tenant Admin, Tenant Tester).
- **Applications** (`/applications`, S3-04): two views of one registry
  behind a scope toggle. "All applications" is the organization-wide
  administration listing (`GET /api/v1/applications?organizationId=`,
  both ownership scopes — the only surface that reaches a shared
  application before its first tenant authorization); "By tenant"
  resolves a tenant's effective visibility (owned plus authorized
  shared). Client-side search over the loaded cursor pages, Load more,
  and a register dialog whose rules mirror the API (`applicationRules`:
  name 1-200, description max 2000, per-platform reverse-DNS
  identifiers required exactly when their platform is chosen).
- **Application detail** (`/applications/:id`, S3-04): metadata with the
  validated edit dialog — the ownership-scope transition is audited
  server-side and sent only when the scope actually changes — a
  reversible archive behind a confirmation naming the application, and,
  for shared applications only, the authorized-tenants screen: grant
  with an optional reason (1-500), revoke the same way, grantedBy
  resolved through the organization roster where possible (raw UUID
  otherwise — no user-name port exists yet). Versions and artifacts
  render honest empty states until E4/E5; mutations invalidate the
  `["applications"]` query-key prefix, which covers both listing views
  and per-application reads.
- **Releases** (`/releases`, S9-05): the release listing is
  application-addressed (the API requires `applicationId` as the
  permission target), so the screen opens with an application picker
  (deep links preselect: `/releases/:applicationId`). Status, version,
  and target-tenant filters ride the listing's server-side query
  parameters; ring and release-ref search filter the loaded cursor
  pages client-side (the registry search precedent — the listing has no
  ring parameter). Rows carry status chips from PRD 17's vocabulary and
  open the dashboard. The nav section renders under the applications
  probe: every role holding `app:applications:read` also holds
  `app:releases:read` (authorization matrix), while a DEVELOPER — who
  holds release read but not the registry read that backs the picker —
  reaches releases through the CLI (S9-06) instead.
- **Release dashboard** (`/releases/:applicationId/:releaseId`, S9-05;
  PRD §32): one screen per release. Lifecycle actions offer exactly
  PRD 17's legal edges (`releaseRules.ts` mirrors the machine; the
  server rejects anything else with the precise 409, and the
  failure/rollback edges open a mandatory-reason dialog); the artifacts
  block shows the creation slots and flips to the frozen content pins
  (build, sha256, size, filename) once the manifest exists; the
  approvals block renders LIVE since S10-02 (issue #41): the bound
  policy's rounds with their group modes, the waiting-on list
  (`approvalRules.ts` mirrors the backend evaluator — the active
  group's pending steps), recorded signatures with roster-resolved
  approvers, skip rationales (which signature satisfied an ANY group;
  the condition's evaluation), approve/reject affordances on exactly
  the decidable steps (rejections open the mandatory-reason dialog),
  the rework restart on a rejected round, and the earlier rounds with
  their rejection reasons; a policy holds section renders since
  S10-06 (issue #45) when the engine's ledger carries any — every
  fired HOLD rule's demand with its evidence, the privileged
  acknowledge (mandatory reason, `app:releases:policy-acknowledge`)
  beside each PENDING hold, the acknowledged history with actor,
  reason, and moment; a release no policy bound renders the
  honest empty state (the surface's 404); quality gates render LIVE
  since S10-03 (per-gate states with evidence links, observed values
  against thresholds, the distinct NO_DATA fail-closed state, override
  records, and the mandatory-reason override action on blocking
  gates); distribution shows the starting
  ring, the targeted environment (S9-04), and the resolved target
  set; the promotion engine renders LIVE since S10-05 (issue #44): a
  Promote action on the release's own screen (its 409 rendering the
  COMPLETE blocker list the API answered, verbatim), the "why can't I
  promote" precheck dialog (the same evaluation as a read), and the
  append-only promotions timeline — every COMPLETED hop and every
  BLOCKED attempt with its recorded evidence — beside the state
  timeline; the state timeline renders the append-only history with
  roster-resolved actors, timestamps, and reasons; the frozen-manifest
  section shows the digest and freeze facts, the document summary,
  the YAML compliance render, a YAML download, and a diff against
  another APPROVED release of the same application (artifact pins
  rendered by their content facts — the identity a CHANGED verdict
  actually used). Detail, history, approvals, and manifest queries
  auto-refresh every 30 seconds; before APPROVED the manifest panel
  shows the honest not-frozen state (the surface's 404), never an
  error.
- **Approval workflows** (`/approval-workflows`, S10-02, issue #41):
  the definitions editor the issue's AC4 names — the organization's
  chains listed with their group-shaped summaries, and the create/edit
  dialog that composes the ordered chain as GROUPS (the simplified
  tree editor): each group an ALL/ANY mode toggle (the PARALLEL and OR
  modes) holding its ordered steps — each step a role from the
  five-role vocabulary, an optional tenant scope, and an optional
  trigger condition (`ENVIRONMENT_EQUALS` /
  `FEATURE_FLAG_CONTAINS`, the stub vocabulary). Client-side
  validation (`approvalRules.ts`) refuses before any write the shapes
  that would make a step unreachable — an empty definition, an empty
  group, a role demanded twice, the ten-step ceiling, a malformed
  condition or binding name — and the server re-validates everything
  (the console is never the gate; refusals surface the problem+json
  code). The name is set at creation only (the binding vocabulary
  environments reference); edits replace the chain and re-attach the
  ring (an emptied ring detaches). The nav section renders under the
  `org:approval-workflows:read` probe.
- **Release rings** (`/rings`, S10-04, issue #43): the rollout ladder
  editor — the organization's effective vocabulary (its seeded five
  plus customs, system defaults where unshadowed) in progressive
  order with Production last, each ring's provenance badge
  (organization vs system), eligibility summary, policy chips, and
  gate-set count. The create/edit dialog writes name, position,
  promotion policy, the name-only approval-policy and channel
  references, and the eligibility axes (tenants from the
  organization directory, tester groups from each tenant's roster,
  degrading to free id entry for actors without
  `app:tester-groups:manage`); an EMPTY eligibility selection clears
  the rules (the documented reset). The server re-validates
  everything (the console is never the gate). The nav section renders
  under the `org:rings:read` probe.
- **Release policies** (`/release-policies`, S10-06, issue #45): the
  declarative rule store — the organization's standing BLOCK /
  HOLD_FOR_REVIEW / ALLOW rules in the defined evaluation order, each
  row with its action chip, scope chip, and the server-rendered
  condition summary. The create/edit dialog writes name, action,
  scope (with its reference: application id, ring picker from the
  vocabulary, environment id), position, enabled, and the condition
  as JSON — with the PRD 33 examples as one-click templates, a
  client-side JSON-parse gate, and the server's field validation
  (references resolvable, the condition compiles) rendered inline.
  The skip-allowances panel edits the promotion seam's widening knob
  (comma-separated ring codes; an untouched Save re-puts the loaded
  set — the PUT is whole-set). The nav section renders under the
  `org:release-policies:read` probe (limit-1, the sibling probes'
  shape).

## CI and verification

`frontend-ci` (`.github/workflows/frontend-ci.yml`) gates every PR:
lint, strict-typecheck build, Vitest — one Node 22 job, about a minute.
Browser verification is a Playwright smoke against the compose stack
(`frontend/e2e/console-verify.mjs` and, since S3-04,
`frontend/e2e/applications-verify.mjs`; since S9-05,
`frontend/e2e/releases-verify.mjs` walks the dashboard golden path — a
release created through the S9-01 API, listed, driven through the UI to
APPROVED (the manifest freeze), then rolled back with a reason — using
`apk-fixture.mjs` — the console's own port of the backend's
AndroidFixtures (the same independent-implementation move the portal's
e2e made in S8-04; the two apps share no code by design) — mints
genuinely READY artifacts through the real validation pipeline; run
instructions in each header); it is intentionally outside CI
because it needs the full stack.

## Decisions

- **Hand-written typed client** instead of OpenAPI codegen: the served
  contract is published (`/v3/api-docs`, CI artifact) and generation
  can be introduced when the API surface churn settles (E3+).
- **Vite 7 (Rollup)** rather than Vite 8 (Rolldown): Rolldown's native
  optional dependencies hit npm/cli#4828 in clean installs; Rollup has
  no platform bindings, keeping CI deterministic.
- **happy-dom** over jsdom for tests: jsdom 30's dependency tree needs
  require-of-ESM (Node >= 22.12); happy-dom keeps the local gate green
  on 22.11 with identical semantics for these tests.
