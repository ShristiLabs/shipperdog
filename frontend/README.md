# ShipperDog Console

The admin console (issue #12, S2-04): React 19 + TypeScript (strict) +
MUI 9 + MUI X Data Grid + React Query 5 + React Router 7 on Vite 7.
First frontend story — the scaffold here (API client, auth context,
error toasts, permission probes) is the idiom every later console story
follows.

## Commands

```bash
npm install        # Node >= 22.12
npm run dev        # Vite dev server on :5173, /api proxied to :8080
npm run lint       # eslint (typescript-eslint + react-hooks)
npm run build      # strict typecheck + production bundle to dist/
npm test           # Vitest unit/component tests
```

Browser smoke suite against a local `docker compose up` stack (not part
of CI): see `e2e/console-verify.mjs` — run instructions in its header.

Local stack: `docker compose up` from the repository root runs the
console (nginx on http://localhost:5173) alongside the API; the login
seed account is documented in
[docs/authentication.md](../docs/authentication.md).

## Structure

```
src/
  api/          typed client: problem+json decoding, cursor pagination,
                correlation ids, single-flight token refresh
  auth/         session storage, AuthContext (silent refresh), route guard
  components/   cross-screen providers (toast convention)
  hooks/        usePermission (role-aware navigation probes)
  layout/       console shell (app bar, nav drawer)
  pages/        screens: login, tenants, tenant members
```

## Conventions fixed by this scaffold

- **API access only through `apiFetch`/`pageFetch`** — they own the
  bearer header, X-Correlation-Id, the problem+json envelope, and the
  401 refresh-retry. Screens never call `fetch` directly.
- **Cursor pagination**: lists use `useInfiniteQuery` with a Load more
  control driven by `next_cursor`; the cursor is opaque and never
  parsed.
- **Errors surface via `useToast().showError`** — stable code + detail
  + trace id, matching [docs/api-conventions.md](../docs/api-conventions.md).
- **Role-aware navigation uses probes, not cached roles** (PRD §26):
  tokens carry no role claims by design, so `usePermission` asks the
  endpoint itself and the backend keeps enforcing every call.
- **Validation mirrors the API** (see `pages/tenantRules.ts`) with the
  server as the authority; the client only fails fast.

## Decisions

- **Hand-written typed client instead of OpenAPI codegen**: the served
  contract is published at `/v3/api-docs` and CI exports
  `backend/target/openapi.yaml`, so generation can be introduced when
  the API surface churn settles (E3+); five endpoints did not justify a
  Java-based generator in the frontend CI path yet.
- **localStorage session storage**: accepted for the M1 admin console
  (documented trade-off in [docs/frontend.md](../docs/frontend.md));
  the API never trusts the client for authorization.
- **happy-dom test environment**: jsdom 30 requires Node >= 22.12 for
  require-of-ESM in its dependency tree; happy-dom keeps the local
  gate green on 22.11 with identical test semantics.
- **Vite 7 (Rollup) over Vite 8 (Rolldown)**: Rolldown's native
  optional dependencies trip npm/cli#4828 in `npm ci`; Rollup needs no
  platform bindings and keeps CI deterministic.
