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
  api/          client.ts (fetch wrapper + single-flight refresh,
                multipart form support since S11-01), problem.ts
                (RFC 7807 decoding), auth.ts (raw auth endpoints),
                catalog.ts (the S8-02 catalog contract: card types +
                cursor paging), history.ts (the S8-03 history
                contract: entry/slot types, cursor paging, the
                versionId deep-link probe), feedback.ts (the S11-01
                submission contract: six-type vocabulary, multipart
                submit, UA-derived OS prefill)
  auth/         tokenStore.ts (session storage), AuthContext.tsx
                (session lifecycle + silent refresh), RequireAuth.tsx
                (route guard), tokenStore.test.ts,
                AuthContext.test.tsx (silent-refresh timer)
  components/   ToastContext.tsx (error toast convention),
                HistoryEntryCard.tsx (one authorized version: badge,
                TTL, platform rows, Report-issue trigger),
                FeedbackDialog.tsx (the S11-01 submit surface),
                DownloadFlow.tsx (the S8-04 install guidance),
                NotFoundPage.tsx (the opaque 404 page)
  layout/       PortalLayout.tsx (app bar, tester identity, logout)
  pages/        LoginPage, AcceptInvitationPage, HomePage (the S8-02
                My Applications catalog), ApplicationPage (the S8-03
                version history), VersionPage (the S8-03 version
                deep link), and their .test.tsx suites
  test/         harness.tsx (providers + fetch router; seedSession/
                seedCatalog/seedHistory stubs), setup.ts
portal/e2e/     portal-verify.mjs (Playwright smoke vs compose stack)
```

## Authentication flows

The portal consumes the identity module's endpoints
([authentication](authentication.md)); for authentication it adds
none and needs no portal-specific backend routes. Since S8-02 the
home screen also consumes the decision API's self-scoped catalog
(GET /api/v1/authorization/decisions/applications), and since S8-03
the version-history views consume its eligibility listing (GET
/api/v1/authorization/decisions/eligibility,
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

## Version history (S8-03)

Tapping a catalog card opens the application's version history at
`/applications/:tenantId/:applicationId` — the authorized versions
FR-013 names, server-computed by the decision API's eligibility
listing ([authorization](authorization.md)): newest first by the
version sort key, each row with its lifecycle badge (Draft, Active,
**Archived**, **Revoked**), the entry's access TTL when a
contributing grant has one, and the per-platform rows the issue's
acceptance criteria pin — platform, build number from artifact
extraction, size, and upload date (the newest distributable artifact
per platform, which is the build a download would serve). The
download entry itself arrives with S8-04 (next section).

- **INV-006 semantics.** Old versions stay listed while a grant
  covers them: ARCHIVED and REVOKED versions remain visible with
  their badges (a revoked version blocks new downloads — the engine's
  rule — but history remains), and revoking the GRANT drops the
  version from this tester's view on the next read (deny-by-default).
  A latest-N window lists exactly the N newest working-set versions:
  when a newer build arrives, the oldest slides out unless an
  explicit grant pins it.
- **Paging.** The history pages at 20 versions (the story's fixed
  default) through the same cursor envelope as the catalog — Load
  more follows `next_cursor`.
- **Deep links.** Each version is shareable at
  `/applications/:tenantId/:applicationId/versions/:versionId` — the
  URL a tester passes along when reproducing an issue. The page
  probes the version against the caller's authorized set server-side:
  an authorized id renders the version detail; an unknown,
  unauthorized, or foreign-application id renders the SAME opaque 404
  page (no hint which check failed, §12). An unauthenticated visit
  parks the deep link in the login `from` marker and returns to it
  after sign-in. Applications the tester cannot reach render the same
  404 page on the history route itself.

The authenticated shell shows the tester's identity in the app bar
beside the catalog. The install/download flow (S8-04, next section)
builds on the same contracts (the eligibility slots name the artifact
a download decision would authorize).

- **OTA-channel releases (S16-16, issue #178).** An entry that is an
  OTA bundle's own versions row (ADR-0010 D2 — the bundle's label is
  the version name) renders an **OTA update** chip and the bundle
  manifest beside its platform rows: the bundle version, the open
  platform tag, the byte size, the bundle's lifecycle status, and the
  SHA-256 checksum the installed build's updater verified — the
  tester-facing half of the device contract in [ota.md](ota.md). The
  manifest rides the same eligibility entry (an optional `ota`
  member); store versions carry nothing OTA-shaped. The portal stays
  a viewer for OTA deliveries: the update itself ships to the
  embedded SDK updater, not through the portal's download flow.

## Downloads and install guidance (S8-04)

Each version-history platform row (and the deep-linked version page)
carries a per-platform Download button (`DownloadFlow`) for the slot's
artifact. The button calls the artifact module's issuance route
(`src/api/downloads.ts` -> `GET /api/v1/artifacts/{artifactId}/
download?version_id=`): the engine's DOWNLOAD decision for the caller
is the route's entire authority (no console permission gate — the
relaxation [authorization](authorization.md) documents), and an ALLOW
answers the routed channel and behavior, the short-lived signed URL,
its expiry, and the download facts — never the storage key.

- **Server-resolved guidance.** The issuance answer carries the
  channel routing table's resolved `behavior`, and the portal renders
  it — never re-deriving platform rules client-side: Android direct
  (`OPEN_SIGNED_URL`) shows the one-time "install unknown apps"
  consent guidance before the URL opens; iOS direct
  (`OPEN_SIGNED_URL_ADHOC`) shows the ad-hoc/enterprise provisioning
  note; the TestFlight placeholder row (`DEEP_LINK_TESTFLIGHT`) is
  carried for the M2/M3 distribution model and unreachable from V1's
  always-DIRECT downloads. The guidance dialog also shows the file
  name, size, and the URL's remaining minutes.
- **The URL is the credential.** Continue opens the URL through the
  browser (`window.location.assign`): emulator deployments answer an
  API-relative path the nginx `/api` proxy serves same-origin;
  production answers an absolute GCS V4 URL. No portal code sits on
  the fetch path.
- **The telemetry pairing block (S15-01).** The issuance request
  carries the page's tenant addressing, and a paired download's
  answer carries the `telemetry` block — the ingest token a test
  build's embedded SDK configures (bound to this tester, tenant, and
  release; [telemetry-sdk](telemetry-sdk.md)). The guidance dialog
  surfaces the token with a copy action; unpaired downloads render
  the guidance unchanged (telemetry is additive evidence, never a
  download gate). `TelemetryGuidance.test.tsx` pins both legs.
- **Opaque denials.** A concealed denial (no grant, revoked version,
  nothing installable for the platform) renders the same problem
  envelope every portal surface shows — stable code, no layer hints —
  with a Retry and Close.
- **Tests.** `DownloadFlow.test.tsx` covers the guidance legs and
  denial rendering; the e2e script (`e2e/portal-verify.mjs` plus the
  `apk-fixture.mjs` signed-APK minter) uploads a real fixture through
  the API, settles it READY, and proves the browser download is
  byte-identical (sha256) with the artifact's file name.

## Feedback submission (S11-01)

"Report issue" on the version screens (the deep-linked version page
and every version card of the history list) opens the
`FeedbackDialog`: the tester picks one of PRD 8.13's six types (Bug,
Feedback, Crash, Feature request, UX issue, Security issue), types the
required description, optionally pins the report to the build they
were looking at (a platform slot's artifact), optionally reports
device/OS (the OS prefilled from the user agent, always editable),
and attaches up to five files — png/jpg/mp4/txt/log. The dialog is
mobile-first: a phone viewport gets the full-screen sheet, tablets and
up the centered card.

- **Context is the screen's, identity is the server's (AC2).** The
  form carries only what the tester typed or chose plus the
  application/version/tenant link the page already addresses; the
  tester record, submitting tenant, organization, and release resolve
  server-side from the session and the release link — the client
  contract has no identity field to spoof, and the recorded request
  proves it in tests. The submission contract is deliberately the one
  the S15-01 in-app SDK will POST from inside a shipped build: one
  API, two clients.
- **The SDK's deep-link handoff (S15-01).** The version page honors
  `?report=1&hint=<capped summary>`: the embedded SDK's feedback link
  (see [telemetry-sdk](telemetry-sdk.md)) opens the report dialog
  with the capped crash summary prefilled — the tester edits and
  submits; nothing posts itself.
- **Validation.** Client-side gates (non-empty description,
  extension/count fast-fails) refuse before any request; the server's
  own bounds stay the authority — its field-level rejections render
  inline under the offending field (the 400's `errors` member keyed
  by field, the attachment 422's keyed by file name), with the fix in
  the form, never a blind retry.
- **Honest failures.** Every other failure renders the problem
  envelope — stable code, trace id, Retry — including the submission
  surface's single concealment (`FEEDBACK_RELEASE_NOT_FOUND`: every
  broken release link answers the same miss). A transport failure
  renders the unexpected-error retry. A 201 renders the confirmation
  with the stored report's id.
- **Tests.** `FeedbackDialog.test.tsx` covers the submit-flow states
  (golden path form shape, build pinning, attachments, client gates,
  server field rejections, concealment, transport failure) and
  `api/feedback.test.ts` the UA-derived OS prefill; the e2e script
  provisions a release targeting the tester's tenant and submits a
  report with an attachment end to end, verifying the server-attached
  context through the admin API.

## Hardening and deployment (S8-05)

The production-credibility story (issue #39) hardened every origin
the portal touches and made the deployment repeatable:

- **Security headers (CSP et al.).** The portal's nginx answers every
  SPA response — entry, client-side-route fallback, hashed assets —
  with the securityheaders.com-grade set: strict CSP
  (`script-src 'self'`, no inline scripts ship in the bundle;
  `style-src 'unsafe-inline'` for MUI's runtime-injected styles;
  `connect-src 'self'`), HSTS, `X-Frame-Options: DENY`, `nosniff`,
  `no-referrer`, and an empty Permissions-Policy
  (`portal/security-headers.conf`). The API carries the same posture
  on its own origin (Spring Security headers, `default-src 'none'`
  CSP). CI grades the deployed image, not a config review:
  `infrastructure/security-headers/check-portal-headers.sh` builds
  the portal container exactly as deployed and asserts all six
  headers on three path shapes (plus the no-inline-script invariant
  the CSP depends on).
- **Session posture.** localStorage stays for M1 with the trade-off
  consciously recorded and its migration path named:
  [ADR-0006](adr/ADR-0006-portal-session-token-storage.md). The CSP
  is the load-bearing mitigation.
- **Rate limiting.** The backend now enforces per-client-IP token
  buckets on `POST /auth/login` and the download tier (429
  `RATE_LIMITED` + `Retry-After`, env-configurable
  `RATE_LIMIT_*`, fails open). Client identity resolves from the raw
  `X-Forwarded-For` chain trusted from the right — compose's nginx
  and Cloud Run's fronting proxy both append the real client, so the
  default trust depth is one (`RATE_LIMIT_TRUSTED_PROXY_COUNT`);
  forged prefixes cannot mint new buckets. The limiter deliberately
  runs before the security chains: a flood costs one map lookup, not
  a JWT decode or a password hash.
- **Same image, two deployment targets.** The portal's nginx conf is
  a template (`default.conf.template`): compose sets
  `API_UPSTREAM=api:8080`, Cloud Run the api service's URL — one
  image everywhere, so the CI header check grades what deploys.
- **Deployment.** Terraform in `infrastructure/terraform/`
  provisions the Cloud Run stack (ADR-0005); the runbook is
  [deployment.md](deployment.md); the post-deploy smoke test
  (`infrastructure/smoke/smoke.sh`) proves health, tester login, and
  a non-empty catalog against the seeded demo org, and the deploy
  workflow wires it after every apply.
