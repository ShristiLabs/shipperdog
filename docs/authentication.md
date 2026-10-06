# Authentication (S1-03, extended by S1-04 and S1-05)

First-party authentication for the platform: invitation-only user
registration, email/password login, JWT access tokens with rotating
refresh tokens, single-use password resets, and personal access tokens
(PATs) for CLI/CI. Implemented by the `identity` module
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

## Token semantics

- **Access tokens** are HS256 JWTs: `iss` (configurable), `sub` (user
  id), `type=access`, `jti`, `iat`, `exp`. TTL defaults to 15 minutes
  (the issue caps it there; authorization caching in S7-03 must not
  depend on it). Since S1-04 the `org_id` claim carries the user's
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
  deactivation block. Suspension is reversible and revokes nothing;
  full revocation semantics arrive with S6-04.
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
`AUTH_INVITATION_TTL` (`P3D`).

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
`organizationId` and `role`. The S1-04 organization mutations emit
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

## Extension path

Credential sources sit behind the identity services so an OIDC provider
can be added (S14-01) as an alternative without touching callers; the
per-organization IdP configuration record itself is stored (not yet
functional) by the `organization` module since S1-04. Identity touches
organizations only through the `OrganizationAccess` interface (login
block, `org_id` claim, invitation membership grants, role snapshots for
the S1-05 RBAC engine — see [authorization](authorization.md));
`tenant_id` claims arrive with S2-03.
