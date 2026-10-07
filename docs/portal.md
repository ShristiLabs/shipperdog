# Tester Portal (S8-01)

The tester portal is the tester-facing single-page application in
`portal/` (PRD §21, FR-010): React 19, TypeScript (strict), MUI 9,
React Query 5, React Router 7, built with Vite 7 — the same stack as
the [admin console](frontend.md) but deliberately a SEPARATE app,
bundle, and deploy target. Testers on mobile networks load a small
portal and never the console's admin surface; the shared conventions
(API client, auth context, error toasts) are re-created small here
rather than imported from `frontend/`, so no console code can leak
into this bundle. S8-01 delivers the scaffold plus tester
authentication: login, the invitation-accept entry point, and an
authenticated shell whose home is an honest empty state — a tester
with no memberships or grants is EXPECTED to see nothing there
(INV-005 resting state, [authorization](authorization.md)), not an
error.

## Running locally

- **Compose (full stack):** `docker compose up` serves the portal at
  http://localhost:5174 (nginx container proxying `/api` to the api
  service — one origin, no CORS) alongside the console on 5173. The
  invitation accept links the API mints (`TESTER_PORTAL_BASE_URL`,
  default `http://localhost:5174`) point here.
- **Dev server (portal only):** `cd portal && npm install && npm run
  dev` — Vite on :5174 proxying `/api` to `:8080` and `/actuator` to
  the management port `:8081`. Node >= 22.12; all dependencies
  exact-pinned with a committed lockfile.
- **Gate:** `npm run lint` (eslint + typescript-eslint + react-hooks),
  `npm run build` (strict `tsc` typecheck + bundle), `npm test`
  (Vitest; happy-dom environment). The `frontend-ci` workflow runs
  the same three on every PR as its own job — neither app's gate can
  pass on the other's green.
- **Browser smoke (manual):** `portal/e2e/portal-verify.mjs` is a
  Playwright suite against the compose stack — it provisions a linked
  tester through the admin API, accepts the invitation end to end,
  and checks the failure paths. Not part of CI; run instructions are
  in the script header.

## Structure

```
portal/src/
  api/          client.ts (fetch wrapper + single-flight refresh),
                problem.ts (RFC 7807 decoding), auth.ts (raw auth
                endpoints), catalog.ts (the S8-02 catalog contract:
                card types + cursor paging)
  auth/         tokenStore.ts (session storage), AuthContext.tsx
                (session lifecycle + silent refresh), RequireAuth.tsx
                (route guard), tokenStore.test.ts,
                AuthContext.test.tsx (silent-refresh timer)
  components/   ToastContext.tsx (error toast convention)
  layout/       PortalLayout.tsx (app bar, tester identity, logout)
  pages/        LoginPage, AcceptInvitationPage, HomePage (the S8-02
                My Applications catalog), HomePage.test.tsx (catalog
                states)
  test/         harness.tsx (providers + fetch router; seedSession/
                seedCatalog stubs), setup.ts
portal/e2e/     portal-verify.mjs (Playwright smoke vs compose stack)
```

## Authentication flows

The portal consumes the identity module's endpoints
([authentication](authentication.md)); for authentication it adds
none and needs no portal-specific backend routes. Since S8-02 the
home screen also consumes the decision API's self-scoped catalog
(GET /api/v1/authorization/decisions/applications,
[authorization](authorization.md)).

- **Login:** `POST /api/v1/auth/login` with email + password.
  Suspended and deactivated testers receive the same opaque 401
  `INVALID_CREDENTIALS` problem as a wrong password — the portal
  renders it without any account-state hint.
- **Invitation accept:** the accept link opens
  `/accept-invitation?token=...`; the tester sets a password via
  `POST /api/v1/auth/register` (single-use token, 7-day TTL,
  [testers](testers.md)) and is then signed in through the standard
  login. Expired, revoked, or already-used tokens surface the
  problem envelope honestly; a missing token renders a card
  explaining the link is required.
- **Session storage:** the token pair lives in `localStorage` under
  `sd.portal.session` — a different key from the console's
  `sd.console.session`, so a tester and an operator signed in on the
  same browser never clobber each other. M1 accepts the localStorage
  XSS trade-off documented in [frontend](frontend.md); the API never
  trusts the client for authorization.
- **Silent refresh:** `AuthContext` arms a timer that rotates the
  access token one minute before expiry; rotation is single-use
  server-side, so every refresh stores the new pair atomically. On a
  401, the client runs ONE single-flight refresh shared by all
  in-flight calls, then retries the original request exactly once.
  Unlike the console (whose client excludes every `/auth/*` path
  from this loop), the portal narrows the exclusion to the raw auth
  POSTs (login, register, refresh, logout) — `GET /auth/me`
  deliberately participates, so a reload with an expired access
  token but a live refresh token recovers transparently.
- **Expiry:** when a refresh fails (revoked family, rotated-away
  token), the client clears the session, emits the portal's
  session-expired event, and the shell drops to the login screen.
- **Logout:** revokes the refresh token (`POST /api/v1/auth/logout`,
  best-effort) and clears local state regardless of the outcome.

## My Applications (S8-02)

The authenticated home is the tester's authorized application
catalog: one card per application the server says they can reach and
install (FR-012/PRD 31) — name, latest authorized version with its
status badge, per-platform availability (iOS/Android, each resolving
to the newest authorized installable build), and an Archived badge
for archived applications. The screen never filters a bigger list
client-side (PRD 26): the decision API computes the set, and an
honest EMPTY catalog is the correct view for a grantless-but-linked
tester (INV-005 resting state), rendered as guidance, not an error.

- **Loading:** the first fetch shows a loading indicator; cached
  renders are instant (30 s stale time) and a Refresh button
  revalidates on demand.
- **Paging:** the catalog pages through the platform cursor envelope
  with a Load more button (`useInfiniteQuery` on `next_cursor`).
- **Errors:** API failures render the problem+json envelope (stable
  code, trace id) with a Retry; a mid-session refresh failure keeps
  the last good page beneath the error card.

The authenticated shell shows the tester's identity in the app bar
beside the catalog. Version history (S8-03) and the install/download
flow (S8-04) build on the same card contract.
