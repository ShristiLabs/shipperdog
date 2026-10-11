# Authentication (S1-03, extended by S1-04, S1-05, and S14-01)

First-party authentication for the platform: invitation-only user
registration, email/password login, JWT access tokens with rotating
refresh tokens, single-use password resets, personal access tokens
(PATs) for CLI/CI, and — since S14-01 — organization-scoped OIDC single
sign-on for console users. Implemented by the `identity` module
(`backend/src/main/java/com/shipperdog/api/identity`); every auth event
flows through the `audit` module's `AuditEventPublisher` surface.
Since S1-04, identity also enforces the organization-deactivation login
block and attaches the `org_id` claim, both via the `organization`
module's public `OrganizationAccess` surface.

## Endpoints (base `/api/v1/auth`)

| Method | Path | Auth | Result |
|---|---|---|---|
| POST | `/login` | none | `200` with `{accessToken, refreshToken, tokenType, expiresInSeconds}`; any failure is a uniform `401` problem+json with code `INVALID_CREDENTIALS` (see [API conventions](api-conventions.md)) |
| POST | `/register` | none (invitation token in body) | `201` user view; `400` INVALID_INVITATION / WEAK_PASSWORD; `409` EMAIL_TAKEN. Since S6-01 this endpoint is ALSO the tester-onboarding accept flow: an invitation minted by `POST /api/v1/testers` (tester flavor, 7-day TTL) creates the ACTIVE account, links it to the tester record, and flips the tester `INVITED -> ACTIVE` — no organization membership is granted (see [Testers](testers.md)) |
| POST | `/refresh` | none (refresh token in body) | `200` rotated token pair; `401` INVALID_REFRESH_TOKEN |
| POST | `/logout` | none (refresh token in body) | `204`; idempotent — revokes the token family |
| GET | `/me` | access JWT or PAT | `200` current actor (`orgId` resolves live from the earliest ACTIVE membership, S1-04; `tenantId` still null — member assignments landed with S2-03, `/me` tenant resolution remains deferred) |
| POST | `/invitations` | access JWT or PAT | `201` with the raw invitation token (shown once); re-inviting an address revokes its previous pending invitation. Optional `organizationId` + `role` (any `org_role` value, default `ORG_MEMBER`) scope it to an organization: since S1-05 the inviter needs the `org:members:invite` permission there (organization administrators and platform administrators hold it) and target-scoped denials render as concealed `404`s (S1-08); `404` unknown organization, `400` invalid role or `role` without `organizationId`; accepting grants the membership with the recorded role |
| POST | `/password-reset/request` | none | `202` always — known and unknown addresses are indistinguishable |
| POST | `/password-reset/confirm` | none (token in body) | `204`; `400` INVALID_RESET_TOKEN (expired or already used) |
| POST | `/tokens` | access JWT or PAT | `201` with the raw PAT (shown once); optional `expiresInSeconds` |
| GET | `/tokens` | access JWT or PAT | `200` PAT metadata (prefix only, never the secret), cursor-paginated (S1-08) |
| DELETE | `/tokens/{id}` | access JWT or PAT | `204`; `404` when the PAT belongs to another user |

SSO flow endpoints (base `/api/v1/auth/sso`, all anonymous; since
S14-01 — see [the SSO section](#oidc-single-sign-on-s14-01)):

| Method | Path | Auth | Result |
|---|---|---|---|
| GET | `/sso/options?organizationId=` | none | `200 {organizationId, providerType: "OIDC"}` when the organization has an enabled configuration; the same `404` for unknown, unconfigured, and disabled alike (the console learns nothing beyond "no") |
| GET | `/sso/authorize?organizationId=` | none | `302` to the provider's authorization endpoint (authorization-code + PKCE `S256`, backend-generated state/nonce/verifier); `400` when discovery fails, `404` when SSO is off |
| GET | `/sso/callback` | none (the IdP's redirect) | always `302` to the console's SSO landing route: `?code=<one-time session code>` on success, `?error=sso` on every failure (uniform — the distinct reason lives on the `SSO_LOGIN_FAILURE` audit line only) |
| POST | `/sso/exchange` | none (session code in body) | `200` with the same `{accessToken, refreshToken, tokenType, expiresInSeconds}` shape as `/login`; `401 INVALID_SSO_SESSION_CODE` for unknown, expired, and already-consumed codes alike |
| POST | `/sso/logout` | none (OIDC Back-Channel Logout) | `204` — the verified logout token (issuer, audience, signature, the spec's `events` claim, no nonce) revokes the linked account's refresh families; `400` for a token that fails verification. Unknown subjects answer `204` per the spec |

## Token semantics

- **Access tokens** are HS256 JWTs: `iss` (configurable), `sub` (user
  id), `type=access`, `jti`, `iat`, `exp`. TTL defaults to 15 minutes
  (the issue caps it there; the S7-03 decision-input cache does not
  depend on it — it caches engine inputs in Redis, never token
  claims). Since S1-04 the `org_id` claim carries the user's
  primary organization: the **earliest-created membership in an ACTIVE
  organization**. It is absent — never defaulted, never a deactivated
  organization's id — when the user has no active organization
  (org-less users, and members of only deactivated organizations, such
  as exempt platform admins). `tenant_id` remains absent for now:
  member assignments landed with S2-03, but `/me` tenant resolution
  was not in that story's scope. Since S1-05, tokens deliberately carry **no role
  claims**: the RBAC engine evaluates permissions against live database
  memberships on every request (see [authorization](authorization.md)),
  so role changes take effect immediately.
- **Refresh tokens** are JWTs with `type=refresh`, TTL 7 days by
  default. Every use rotates them; reuse of an already-rotated token
  revokes the whole token family and emits a `TOKEN_REUSE_DETECTED`
  audit event. Logout and password reset revoke families as well.
  Since S1-04, refresh is also refused (`401 invalid_refresh_token`,
  failed `TOKEN_REFRESHED` audit event) while every organization of the
  user is DEACTIVATED, so deactivation outlives sessions issued before
  it; platform administrators are exempt. The family is intentionally
  NOT revoked on that refusal — reactivating the organization restores
  the session. Since S1-05, a `DISABLED` account is refused at refresh
  AND its family is revoked (reason `account_disabled`) — disabling an
  account ends its sessions, while organization deactivation stays
  reversible. Role demotions and membership removals also revoke
  families (reasons `role_demoted` / `membership_removed`), as does
  suspending a tenant membership since S2-03 (reason
  `membership_suspended`); the
  evaluator's live role reads make even unrevoked short-lived access
  tokens respect the change at once.
- Refresh tokens are rejected as access credentials, and access tokens
  are rejected at `/refresh`.
- **Personal access tokens** are opaque (`sdp_` + 43 URL-safe chars),
  returned exactly once, and stored only as SHA-256 digests. The
  listing shows a short prefix. `last_used_at` is refreshed at most
  once per minute per token. Since S1-05, PAT authentication re-checks
  the user's status, so a `DISABLED` account cannot keep using existing
  PATs (401, same as an unknown token).

## Security behavior

- Passwords: Argon2id (19 MiB, 2 iterations, 1 lane, 32-byte hash);
  policy is length 12-128. Hash parameters are parsed from the stored
  hash, so they can be raised later without invalidating accounts.
  Since S14-01 the hash is nullable: a JIT-provisioned SSO account is
  password-less until its owner sets one, and password login for such
  an account answers the uniform `401` (with the same dummy
  comparison, so the branch is not distinguishable by timing).
- Failed-login lockout: after 5 consecutive failures (configurable) the
  account locks for 15 minutes. Locked, disabled, and unknown accounts
  all answer the same generic 401, with a dummy Argon2 comparison on
  every failure path so timing does not reveal account state. A
  `USER_LOCKED_OUT` audit event accompanies the lock.
- Organization deactivation (S1-04): a user whose organizations are all
  DEACTIVATED (and who has at least one membership) is refused at login
  and at token refresh. The response stays the uniform generic 401 —
  organization state is not revealed to the caller — while the
  accompanying `LOGIN_FAILURE` audit event carries the distinct reason
  `organization_deactivated`. **Platform administrators**
  (`users.platform_admin`) are exempt: they may hold deactivated-only
  memberships and still log in and refresh; their access tokens carry no
  `org_id` in that state.
- Tester suspension (S6-01): a user whose tester record is SUSPENDED is
  refused by EVERY credential path — login, token refresh, and personal
  access tokens (a suspended PAT authenticates as unknown, a uniform
  401). Same uniform 401 and distinct audit reason
  (`tester_suspended`); platform administrators are exempt like the
  deactivation block. Suspension is reversible and revokes nothing.
- Tester deactivation (S6-04, issue #29): a user whose tester record is
  DISABLED is refused the same way with audit reason
  `tester_deactivated` — and unlike suspension, the deactivating
  transaction already revoked every refresh family through the
  `SessionRevocation` surface (`TOKEN_REVOKED`, reason
  `tester_deactivated`), so a pre-deactivation refresh token trips
  reuse detection rather than parking. Access tokens are stateless
  15-minute JWTs and die at expiry (the revocation SLA — see
  [testers](testers.md)); every stateful surface (login, refresh, PAT,
  authorization decisions) denies on the very next request.
- IdP-inactive hard lock (S14-01): when the organization's provider
  asserts the configured inactive claim, the linked account crosses to
  `LOCKED` with no expiry — a hard lock distinct from the timed
  failed-login lockout above — and every refresh family is revoked
  (reason `idp_inactive`). A hard-locked account is refused at every
  stateful surface: refresh, PAT authentication, password login, and
  SSO alike. Offboarding follows the identity provider.
- Password reset tokens are single-use with a 30-minute TTL. Until the
  notification module exists (S12-02), the reset link is emitted on a
  WARN log line; treat local logs as sensitive. Confirming a reset
  rehashes the password and revokes every refresh token of the account.
- `AUTH_JWT_SECRET` has no committed default; the application refuses
  to start without at least 32 bytes. The compose file carries a
  local-only development value with the same policy as the local
  database password.

## Configuration

All values are environment variables (defaults in parentheses):
`AUTH_JWT_SECRET` (required, min 32 bytes), `AUTH_JWT_ISSUER`
(`shipperdog-api`), `AUTH_ACCESS_TOKEN_TTL` (`PT15M`),
`AUTH_REFRESH_TOKEN_TTL` (`P7D`), `AUTH_LOCKOUT_THRESHOLD` (`5`),
`AUTH_LOCKOUT_DURATION` (`PT15M`), `AUTH_PASSWORD_RESET_TTL` (`PT30M`),
`AUTH_INVITATION_TTL` (`P3D`). The OIDC relying party's deployment
posture (S14-01) lives under `shipperdog.sso.*`:
`SSO_REDIRECT_BASE_URI` (`http://localhost:8080` — the API's
externally reachable base; the callback `redirect_uri` registered with
every provider is `<base>/api/v1/auth/sso/callback`),
`SSO_CONSOLE_CALLBACK_URI`
(`http://localhost:5173/sso/callback` — the console route the callback
redirects the browser to), `SSO_AUTHORIZATION_REQUEST_TTL` (`PT10M`),
`SSO_SESSION_CODE_TTL` (`PT60S`), `SSO_DISCOVERY_CACHE_TTL` (`PT10M`),
`SSO_JWKS_CACHE_TTL` (`PT10M`), `SSO_CLOCK_SKEW` (`PT1M`).

## Local development

The dev seed (docker compose, `dev` profile) provides a bootstrap
**platform administrator**: `dev@shipperdog.local` /
`shipperdog-dev-admin`, flagged `platform_admin` and administering the
seeded demo organization as `ORG_ADMIN` (S1-04). It never exists in
production or clean-database CI runs.

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@shipperdog.local","password":"shipperdog-dev-admin"}'
```

## Audit events

`LOGIN_SUCCESS`, `LOGIN_FAILURE`, `USER_LOCKED_OUT`, `USER_REGISTERED`,
`INVITATION_CREATED`, `PASSWORD_RESET_REQUESTED`,
`PASSWORD_RESET_COMPLETED`, `TOKEN_ISSUED`, `TOKEN_REFRESHED`,
`TOKEN_REVOKED`, `TOKEN_REUSE_DETECTED`, `PAT_CREATED`, `PAT_REVOKED`.
`LOGIN_FAILURE` distinguishes `invalid_credentials` from
`organization_deactivated` in its audit detail (never in the HTTP
response); org-scoped `INVITATION_CREATED` events carry
`organizationId` and `role`. S14-01 adds the SSO vocabulary:
`SSO_LOGIN_SUCCESS` (organization, issuer, subject, provisioning
mode), `SSO_LOGIN_FAILURE` (the distinct refusal reason — the only
place it exists, since the browser redirect is uniform),
`SSO_IDENTITY_LINKED` (subject, link kind `EMAIL`/`JIT`),
`SSO_USER_JIT_PROVISIONED` (the granted role), `SSO_ROLE_MAPPED`
(matched group, from/to roles), `SSO_ACCOUNT_LOCKED`, and `SSO_LOGOUT`
(back-channel logout: issuer, subject). The S1-04 organization mutations emit
`ORGANIZATION_CREATED`, `ORGANIZATION_UPDATED`, `ORG_MEMBER_ADDED`, and
`ORG_IDP_CONFIGURED` from the organization module; S1-05 adds
`ORG_MEMBER_ROLE_CHANGED`, `ORG_MEMBER_REMOVED`,
`TENANT_MEMBER_ROLE_CHANGED`, and `TENANT_MEMBER_REMOVED` (see
[authorization](authorization.md)); S2-03 adds
`TENANT_MEMBER_ADDED` and `TENANT_MEMBER_STATUS_CHANGED`. Since S1-06
the binding also
enriches, redacts, and persists every event to the append-only audit
store in the same transaction — no caller changed shape (see
[audit](audit.md)).

## OIDC single sign-on (S14-01)

Enterprise identity (PRD 25, FR-001): an Organization Admin configures
the organization's corporate OIDC provider (Google Workspace, Entra,
Okta) and console users sign in through it, while local login stays
for platform administrators as break-glass — platform admins never
authenticate through an organization's provider.

**Configuration** is the typed `PUT /api/v1/organizations/{id}/idp-config`
record (owned by the identity module since S14-01; permissions
unchanged since S1-04): `issuerUrl` (https, or http for loopback;
paths allowed — Entra's `/<tenant>/v2.0` shape), `clientId`, the
client secret as one-time `clientSecret` material or a
pre-provisioned `clientSecretRef` (exactly one per write; material is
stored once through the platform's secret store and only the opaque
reference is kept, echoed, and audited — rotation discards the
replaced material), `scopes` (default `openid email`, must contain
`openid`), `provisioningMode` (`INVITED_ONLY` default, or `JIT`),
`jitDefaultRole` (default `VIEWER`, the least-privilege floor),
`requireVerifiedEmail` (default strict), `groupClaimName` +
`roleMappings` (group claim value to org role), `inactiveClaimName`,
and `enabled` (default false — SSO is off until the record says
otherwise).

**The flow** is backend-driven authorization-code + PKCE (`S256`):
`GET /sso/authorize` persists the hashed state, hashed nonce, and the
PKCE verifier (10-minute TTL, single-use — consumed atomically, so a
racing replay loses), redirects the browser to the provider, and the
provider's redirect back lands on `GET /sso/callback`. The callback
exchanges the code (`client_secret_post`), verifies the ID token
against the provider's JWKS — algorithm pinning accepts only RS256 and
ES256 (`alg:none` and the HMAC family are refused before any key is
consulted), issuer and audience must match the configuration, expiry
and issued-at within the clock skew, and the nonce must hash-match the
authorization request — then resolves the account and hands the
browser a one-time session code (60-second TTL) that the console's
`POST /sso/exchange` swaps for the same token pair a local login
receives: SSO sessions ARE local sessions (same claims, same refresh
rotation, same revocation surfaces), minted through the identical
issuance path. JWKS and discovery documents are cached per issuer/TTL
and refreshed on an unknown `kid` (the #54 receiver's doctrine);
a fetch outage keeps the stale set and verification fails closed.

**Provisioning and linkage**: once an OIDC subject is linked
(`user_idp_identities`, unique per organization), logins resolve
through the subject and never re-derive the account from email.
Unlinked assertions match by email — refused outright when the token's
email is unverified under the strict default. INVITED_ONLY links only
existing members; JIT additionally provisions a password-less account
and admits existing non-member accounts, both with the configured
default role. Group-claim role mapping applies on every login: the
most privileged mapped group the token carries wins, no mapped group
leaves the membership untouched, and a refused change (the
last-ORG_ADMIN guard) never blocks a login. Every leg audits.

**Session end**: the OIDC Back-Channel Logout receiver
(`POST /sso/logout`) verifies the logout token with the same
pinned-algorithm machinery (plus the spec's `events` claim; a
nonce-bearing token is refused) and revokes the linked account's
refresh families — IdP-initiated logout and IdP session expiry land on
the same leg.

## Extension path

The OIDC provider S1-03's structure reserved a place for has landed
(S14-01, above); SAML (S14-02) and SCIM (S14-03) follow in E14.
Identity touches organizations only through the `OrganizationAccess`
interface (login block, `org_id` claim, invitation membership grants,
role snapshots and the SSO role-mapping apply for the S1-05 RBAC
engine — see [authorization](authorization.md)); `tenant_id` claims
arrive with S2-03.
