# Authentication (S1-03)

First-party authentication for the platform: invitation-only user
registration, email/password login, JWT access tokens with rotating
refresh tokens, single-use password resets, and personal access tokens
(PATs) for CLI/CI. Implemented by the `identity` module
(`backend/src/main/java/com/shipperdog/api/identity`); every auth event
flows through the `audit` module's `AuditEventPublisher` surface.

## Endpoints (base `/api/v1/auth`)

| Method | Path | Auth | Result |
|---|---|---|---|
| POST | `/login` | none | `200` with `{accessToken, refreshToken, tokenType, expiresInSeconds}`; any failure is a uniform `401 invalid_credentials` |
| POST | `/register` | none (invitation token in body) | `201` user view; `400` invalid_invitation / weak_password; `409` email_taken |
| POST | `/refresh` | none (refresh token in body) | `200` rotated token pair; `401` invalid_refresh_token |
| POST | `/logout` | none (refresh token in body) | `204`; idempotent — revokes the token family |
| GET | `/me` | access JWT or PAT | `200` current actor (`orgId`/`tenantId` null until S1-04) |
| POST | `/invitations` | access JWT or PAT | `201` with the raw invitation token (shown once); re-inviting an address revokes its previous pending invitation |
| POST | `/password-reset/request` | none | `202` always — known and unknown addresses are indistinguishable |
| POST | `/password-reset/confirm` | none (token in body) | `204`; `400` invalid_reset_token (expired or already used) |
| POST | `/tokens` | access JWT or PAT | `201` with the raw PAT (shown once); optional `expiresInSeconds` |
| GET | `/tokens` | access JWT or PAT | `200` PAT metadata (prefix only, never the secret) |
| DELETE | `/tokens/{id}` | access JWT or PAT | `204`; `404` when the PAT belongs to another user |

## Token semantics

- **Access tokens** are HS256 JWTs: `iss` (configurable), `sub` (user
  id), `type=access`, `jti`, `iat`, `exp`. TTL defaults to 15 minutes
  (the issue caps it there; authorization caching in S7-03 must not
  depend on it). The `org_id` and `tenant_id` claims are attached once a
  user's membership can be resolved (S1-04/S1-05); until then they are
  absent, never defaulted.
- **Refresh tokens** are JWTs with `type=refresh`, TTL 7 days by
  default. Every use rotates them; reuse of an already-rotated token
  revokes the whole token family and emits a `TOKEN_REUSE_DETECTED`
  audit event. Logout and password reset revoke families as well.
- Refresh tokens are rejected as access credentials, and access tokens
  are rejected at `/refresh`.
- **Personal access tokens** are opaque (`sdp_` + 43 URL-safe chars),
  returned exactly once, and stored only as SHA-256 digests. The
  listing shows a short prefix. `last_used_at` is refreshed at most
  once per minute per token.

## Security behavior

- Passwords: Argon2id (19 MiB, 2 iterations, 1 lane, 32-byte hash);
  policy is length 12-128. Hash parameters are parsed from the stored
  hash, so they can be raised later without invalidating accounts.
- Failed-login lockout: after 5 consecutive failures (configurable) the
  account locks for 15 minutes. Locked, disabled, and unknown accounts
  all answer the same generic 401, with a dummy Argon2 comparison on
  every failure path so timing does not reveal account state. A
  `USER_LOCKED_OUT` audit event accompanies the lock.
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

The dev seed (docker compose, `dev` profile) provides a bootstrap admin:
`dev@shipperdog.local` / `shipperdog-dev-admin`. It never exists in
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
The current binding logs events and republishes them in-process; the
durable audit sink arrives with S1-06 without changing callers.

## Extension path

Credential sources sit behind the identity services so an OIDC provider
can be added (S14-01) as an alternative without touching callers. There
is deliberately no organization or RBAC logic here — that lands with
S1-04/S1-05, which will also start attaching `org_id`/`tenant_id`
claims.
