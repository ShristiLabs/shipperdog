# ShipperDog Tester Portal

The tester-facing web app (issue #35, S8-01): React 19 + TypeScript
(strict) + MUI 9 + React Query 5 + React Router 7 on Vite 7 — the same
stack as the console, deliberately a SEPARATE app, bundle, and deploy
target (issue AC1/AC5): testers on mobile networks load a small
portal, never the console's admin surface. The conventions (API
client, auth context, error toasts) are re-created small here rather
than imported from `frontend/` so no console code can leak into this
bundle.

## Commands

```bash
npm install        # Node >= 22.12
npm run dev        # Vite dev server on :5174, /api proxied to :8080
npm run lint       # eslint (typescript-eslint + react-hooks)
npm run build      # strict typecheck + production bundle to dist/
npm test           # Vitest unit/component tests
```

Browser smoke suite against a local `docker compose up` stack (not part
of CI): see `e2e/portal-verify.mjs` — run instructions in its header.

Local stack: `docker compose up` from the repository root serves the
portal at http://localhost:5174 (nginx proxying `/api`) alongside the
console (5173) and the API. Invitation accept links the API mints
(`TESTER_PORTAL_BASE_URL`) point here.

## Structure

```
src/
  api/          typed client: problem+json decoding, correlation ids,
                single-flight token refresh (re-created portal-small)
  auth/         session storage (sd.portal.session), AuthContext
                (silent refresh), route guard
  components/   cross-screen providers (toast convention)
  layout/       portal shell (app bar, tester identity, logout)
  pages/        screens: login, invitation accept, home placeholder
```

## Conventions fixed by this scaffold

- **API access only through `apiFetch`** — it owns the bearer header,
  X-Correlation-Id, the problem+json envelope, and the 401
  refresh-retry. Screens never call `fetch` directly (the raw auth
  POSTs in `api/auth.ts` are the deliberate exception: they carry no
  session and never enter the refresh loop).
- **GET /auth/me participates in the refresh-retry** (unlike the
  console's blanket /auth/* exclusion): it is the session-validation
  call, so a reload with an expired access token but a live refresh
  token recovers transparently.
- **Errors surface via `useToast().showError`** — stable code + detail
  + trace id, matching [docs/api-conventions.md](../docs/api-conventions.md).
- **Login failures stay opaque** (docs/authentication.md): suspended
  and deactivated testers see the same generic message as wrong
  passwords.
- **Validation mirrors the API** (password 12-128 on the accept form)
  with the server as the authority; the client only fails fast.
- **Mobile-first**: single-column layout, 44px tap targets, 16px base
  font (no iOS focus zoom), nginx gzip for mobile networks.

## Decisions

- **Separate app over a /portal route in the console** (issue AC1/AC5):
  different entry, no admin routes bundled, independent build and
  deploy; a shared component library remains possible later without
  re-coupling the bundles.
- **Separate localStorage key (`sd.portal.session`)**: a tester and an
  operator stay signed in independently on the same browser.
- **No MUI X Data Grid** (or any console-only dependency): the portal
  bundle stays small for mobile networks.
