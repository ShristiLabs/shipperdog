# Authentication (S1-03, extended by S1-04, S1-05, S14-01, S14-02, and S14-03)

First-party authentication for the platform: invitation-only user
registration, email/password login, JWT access tokens with rotating
refresh tokens, single-use password resets, personal access tokens
(PATs) for CLI/CI, and — since S14-01 — organization-scoped single
sign-on for console users: OIDC (S14-01) and SAML 2.0 (S14-02), the
two provider kinds sharing one identity-resolution layer. Implemented
by the `identity` module
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
S14-01 OIDC and S14-02 SAML — see [the SSO
sections](#oidc-single-sign-on-s14-01)):

| Method | Path | Auth | Result |
|---|---|---|---|
| GET | `/sso/options?organizationId=` | none | `200 {organizationId, providerType}` — `"OIDC"` or `"SAML"`, whichever kind the organization's enabled configuration uses — or the same `404` for unknown, unconfigured, and disabled alike (the console learns nothing beyond "no") |
| GET | `/sso/authorize?organizationId=` | none | `302` to the provider: the OIDC authorization endpoint (authorization-code + PKCE `S256`, backend-generated state/nonce/verifier) or the SAML IdP's Redirect-binding SSO location (DEFLATE+b64 AuthnRequest, RelayState) — dispatched by the configured kind; `400` when OIDC discovery fails, `404` when SSO is off |
| GET | `/sso/callback` | none (the OIDC IdP's redirect) | always `302` to the console's SSO landing route: `?code=<one-time session code>` on success, `?error=sso` on every failure (uniform — the distinct reason lives on the `SSO_LOGIN_FAILURE` audit line only) |
| GET | `/sso/saml/{organizationId}/metadata` | none | `200 application/samlmetadata+xml` — the organization's SP EntityDescriptor (its entityID is this route's own URL); the same `404` when the organization has no enabled SAML configuration |
| POST | `/sso/saml/{organizationId}/acs` | none (the IdP's POST-binding form) | always `302` to the console's SSO landing route: `?code=<one-time session code>` on success, `?error=sso` on every failure — both SAML flows (SP-initiated and IdP-initiated) land here |
| POST | `/sso/exchange` | none (session code in body) | `200` with the same `{accessToken, refreshToken, tokenType, expiresInSeconds}` shape as `/login`; `401 INVALID_SSO_SESSION_CODE` for unknown, expired, and already-consumed codes alike (provider-blind — every SSO kind mints the same code) |
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
`AUTH_INVITATION_TTL` (`P3D`). The SSO deployment posture (S14-01
OIDC, S14-02 SAML) lives under `shipperdog.sso.*`:
`SSO_REDIRECT_BASE_URI` (`http://localhost:8080` — the API's
externally reachable base; the OIDC callback `redirect_uri` registered
with every provider is `<base>/api/v1/auth/sso/callback`, and each
organization's SAML SP entityID/ACS derive from the same base as
`<base>/api/v1/auth/sso/saml/{orgId}/metadata` and `.../acs`),
`SSO_CONSOLE_CALLBACK_URI`
(`http://localhost:5173/sso/callback` — the console route every SSO
callback redirects the browser to), `SSO_AUTHORIZATION_REQUEST_TTL`
(`PT10M` — covers the OIDC state/nonce/verifier and the SAML
AuthnRequest ID/RelayState alike), `SSO_SESSION_CODE_TTL` (`PT60S`),
`SSO_DISCOVERY_CACHE_TTL` (`PT10M`), `SSO_JWKS_CACHE_TTL` (`PT10M`),
`SSO_CLOCK_SKEW` (`PT1M` — applied to ID-token and SAML assertion time
claims alike), and the optional SAML SP signing pair (S14-02):
`SSO_SAML_SP_SIGNING_KEY` / `SSO_SAML_SP_SIGNING_CERT` (empty by
default — the unsigned posture: unsigned AuthnRequests and an SP
metadata document without a signing KeyDescriptor, which Entra, Okta,
and Google Workspace all accept; when both are set, the pair must be a
parseable PKCS#8 RSA key with its matching X.509 certificate, the
metadata publishes the certificate, and the AuthnRequest redirect
carries an RSA-SHA256 signature — startup refuses on a malformed or
mismatched pair).

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
place it exists, since the browser redirect is uniform; SAML refusals
carry `saml_*` reasons since S14-02, the shared resolver's reasons are
provider-neutral),
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

## SAML single sign-on (S14-02)

SAML 2.0 as a second provider kind for enterprises standardized on it
(PRD 25 names Entra, Okta, and Google Workspace): the same `PUT
/api/v1/organizations/{id}/idp-config` route, one configuration row per
provider kind (`UNIQUE (organization_id, provider_type)`), the same
identity semantics, and the same one-time session code the console
exchanges — SAML is a thin second frontend over the provider-neutral
resolver the OIDC flow uses. The validation stack and its hardening
posture are recorded in [ADR-0009](adr/ADR-0009-saml-validation-stack.md).

**Configuration** uses the same typed record with `providerType:
"SAML"`: `idpMetadataXml` (the IdP's EntityDescriptor XML, parsed and
pinned at write time — an entityID, an HTTP-Redirect
SingleSignOnService location over https, and at least one
`use="signing"` KeyDescriptor certificate are required; unparseable or
anchor-less metadata is refused, never stored), the signature policy
`wantAssertionsSigned` (default true — Entra's shape) and
`wantResponseSigned` (default false; Google Workspace signs the
Response only — set this true and `wantAssertionsSigned` false; at
least one must be required), `enforceSsoLogin` (default false — see
below), and the shared provisioning columns: `provisioningMode`,
`jitDefaultRole`, `requireVerifiedEmail`, `groupClaimName` (the SAML
attribute Name carrying enterprise groups — URI-named attributes like
Entra's `http://schemas.microsoft.com/ws/2008/06/identity/claims/roles`
are valid), and `roleMappings`. The read echoes the pinned
certificates' SHA-256 fingerprints — never the metadata XML and never
any secret. An `inactiveClaimName` is refused on SAML rows (the claim
has no SAML meaning; SAML has no inactive assertion).

**The SP surface**: each organization's SP metadata document is served
at `GET /api/v1/auth/sso/saml/{orgId}/metadata` — its entityID is that
route's own URL (a stable per-organization identifier to register with
the IdP), its AssertionConsumerService is the POST-binding route
`/api/v1/auth/sso/saml/{orgId}/acs`, and the optional signing
KeyDescriptor appears only when the deployment configured the SP
signing pair. The SP-initiated flow starts at the same `GET
/sso/authorize` as OIDC (the configured kind dispatches): the backend
persists the AuthnRequest ID and RelayState — both hashed, single-use
with the shared authorization-request TTL, consumed atomically so a
racing replay loses — and redirects the browser to the IdP's
Redirect-binding SSO location with the DEFLATE+b64 AuthnRequest. The
IdP-initiated flow needs no request: the IdP POSTs straight to the
ACS. Both flows land on the same ACS, and every outcome is the uniform
`302` to the console (`?code=` or `?error=sso`).

**Validation** (ADR-0009) runs before anything the payload says is
acted on: signature verification on Apache Santuario with certificates
pinned from the ingested metadata ONLY (a certificate embedded in the
assertion's KeyInfo is ignored), algorithm pinning (RSA-SHA256/384/512
and ECDSA over SHA-256/384/512 digests — SHA-1, HMAC, and unpinned
transforms refused before any crypto runs), signature-wrapping
defense in layers (document-wide ID uniqueness, exactly one direct
Assertion child, every signature's single Reference covering its own
enclosing element), XXE-hardened parsing with size caps, and the
conditions: NotBefore/NotOnOrAfter within the clock skew, an
AudienceRestriction naming the SP entityID, a bearer
SubjectConfirmationData whose Recipient is this deployment's ACS, and
InResponseTo corroboration — a SubjectConfirmationData InResponseTo
DEMANDS the Response repeat the same id (a blank or different
Response-level value is refused; the attribute rides outside the
assertion's enveloped signature, so anything less would let a captured
SP-initiated response be replayed as pseudo-IdP-initiated). Transient
NameIDs are refused (single-use by definition); EncryptedAssertion is
refused and deferred — this SP publishes no encryption KeyDescriptor,
so no honest IdP encrypts for it. SAML Single Logout is likewise
deferred; the OIDC Back-Channel Logout receiver is OIDC-only.

**Provisioning and linkage** are IDENTICAL to OIDC — the same shared
resolver, the same `user_idp_identities` links (the SAML NameID is the
subject; an email-format NameID asserts the address when no email
attribute rides along), the same INVITED_ONLY/JIT policies, the same
most-privileged-mapped-group role mapping, the same platform-admin
break-glass refusal. The `sso_authorization_requests` table holds the
hashed SP-initiated request state and is swept with the OIDC flow
state.

**Certificate rotation**: the administrator re-uploads the IdP
metadata with the new certificate — the new certificate pins at write
time and the old one stops verifying immediately (refused as
`saml_assertion_signature` or `saml_response_signature`, depending on
the leg). Overlapping rotation is supported: a metadata document
carrying both the old and the new signing certificate keeps both
pinned for the window, so the re-upload can precede the IdP's key
switch. Validation failures are the alerting surface: every refusal
WARN-logs and emits an `SSO_LOGIN_FAILURE` audit event carrying the
distinct `saml_*` reason — a spike in `saml_*_signature` failures
after a rotation means an IdP still signing with the retired key.

**Replay posture**: SP-initiated responses are single-use (the
atomic request consume plus the RelayState hash binding); IdP-initiated
responses carry no request state by definition, so their replay window
is bounded by the assertion's `NotOnOrAfter` (plus the clock skew) —
an accepted property of IdP-initiated SAML, mitigated by short
assertion lifetimes at the IdP and HTTPS on the ACS. When an
organization carries BOTH an OIDC and a SAML row, the `options` probe
and `authorize` dispatch prefer OIDC; the SAML flow remains reachable
through its own metadata and ACS routes.

**The SAML mandate** (`enforceSsoLogin: true`): password login is
refused for the organization's active members while the configuration
is enabled — the uniform generic `401` with the distinct
`sso_enforced` reason on the `LOGIN_FAILURE` audit line. Platform
administrators keep their break-glass local login, existing sessions
are untouched (the mandate governs the password credential, not live
tokens), and disabling the configuration lifts it immediately.

## SCIM provisioning (S14-03)

The SCIM 2.0 provisioning surface carries the third credential
posture: a per-organization connector bearer token (`sds_` prefix,
the PAT posture — SHA-256 hash at rest, plaintext exactly once at
generation, one live token per organization, atomic rotation). It is
NOT a user credential: it authenticates the identity provider's
provisioning pushes on `/scim/v2/**` only, those routes sit outside
the `/api/v1` bearer surface, and it never mints sessions. The whole
surface — token lifecycle, mapping rules, provisioning semantics, the
leaver cascade, and the IdP setup guide — is documented in
[scim.md](scim.md). Its failures answer the RFC 7644 SCIM error shape
(`application/scim+json`), not the problem+json envelope: a
deliberate, documented deviation (see
[error codes](error-codes.md)).

## Extension path

The OIDC provider S1-03's structure reserved a place for has landed
(S14-01, above), as has SAML (S14-02, above) and SCIM provisioning
(S14-03, above). Identity touches organizations only through the
`OrganizationAccess` interface (login block, `org_id` claim,
invitation membership grants, role snapshots and the SSO role-mapping
apply for the S1-05 RBAC engine — see
[authorization](authorization.md)); `tenant_id` claims arrive with
S2-03.
