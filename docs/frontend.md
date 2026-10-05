# Admin Console (S2-04, S3-04)

The admin console is the React single-page application in `frontend/`
(PRD §21 stack): React 19, TypeScript (strict), MUI 9 with MUI X Data
Grid, React Query 5, React Router 7, built with Vite 7. S2-04 delivers
the scaffold plus tenant administration — list, create, edit,
deactivate, and member management — and the conventions every later
console story (E3 onward) reuses. S3-04 adds the application console on
those conventions: the registry listing, create/edit, the shared-app
authorization screen, and the reversible archive.

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
  api/          client.ts (fetch wrapper), problem.ts (RFC 7807
                decoding), auth.ts, tenants.ts, organizations.ts,
                applications.ts (registry + shared-app authorization)
  auth/         tokenStore.ts, AuthContext.tsx, RequireAuth.tsx
  components/   ToastContext.tsx (error toast convention)
  hooks/        usePermission.ts (role-aware navigation probes),
                useOrganizationDirectory.ts (walks org tenants/members
                rosters across cursor pages for pickers and name maps)
  layout/       ConsoleLayout.tsx (app bar + nav drawer)
  pages/        LoginPage, TenantsPage, TenantMembersPage, tenantRules,
                ApplicationsPage, ApplicationDetailPage,
                ApplicationDialog (create/edit form),
                applicationRules (client mirrors of field rules)
  test/         harness.tsx (providers + fetch router), setup.ts
frontend/e2e/   console-verify.mjs, applications-verify.mjs
                (Playwright smokes vs compose stack)
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

### Auth and token lifecycle

Login stores the rotated token pair from
[authentication](authentication.md) in `localStorage` under one key
(`sd.console.session`, atomic on login/logout/refresh). The access
token is 15 minutes; `AuthContext` arms a silent refresh one minute
before expiry, and a 401 on any business call triggers one
single-flight refresh plus a single retry — concurrent 401s share one
refresh, honoring single-use rotation. A failed refresh clears the
session and returns the user to the login screen.

**Storage trade-off (accepted for M1):** `localStorage` survives reloads
and multiple tabs but is readable by injected scripts. The API never
trusts the client: authorization is re-evaluated live per request
([authorization](authorization.md)), and refresh rotation + family
revocation bound what a stolen token is worth. Revisit (memory-only +
refresh cookie) when the console gains third-party content.

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

## CI and verification

`frontend-ci` (`.github/workflows/frontend-ci.yml`) gates every PR:
lint, strict-typecheck build, Vitest — one Node 22 job, about a minute.
Browser verification is a Playwright smoke against the compose stack
(`frontend/e2e/console-verify.mjs` and, since S3-04,
`frontend/e2e/applications-verify.mjs`; run instructions in each
header); it is intentionally outside CI because it needs the full
stack.

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
