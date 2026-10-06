# Testers — onboarding and invitations (S6-01)

Testers are the people who exercise application versions (PRD 8.8):
each tester record is the ORGANIZATION-scoped person record — one
record per email address per organization, so the same person can be a
tester of two organizations. Tenant attachment is deliberately not
modeled here: `tenant_tester_memberships` arrives with S6-03 and
tester groups with S6-02; this story delivers the invitation funnel
(FRD FR-010): an organization administrator invites by email, the
invitee self-onboards, and the resulting account spans tenants and
groups through memberships. Implemented by the `tester` module
(`backend/src/main/java/com/shipperdog/api/tester`). Permissions
referenced below are enforced by the single RBAC enforcement point —
see [authorization](authorization.md) for the matrix and semantics.

## Onboarding flow

```text
Org Admin                ShipperDog API                Invitee
   |  POST /api/v1/testers     |                          |
   |-------------------------->| create INVITED tester     |
   |                           | mint single-use token     |
   |                           | (7-day TTL, digest-only)  |
   |  201 + invitation block   |                          |
   |<--------------------------|                          |
   |  (email via the notification seam carries the accept link;
   |   in M1 the link is also returned to the invoker once)   |
   |                           |  POST /api/v1/auth/register
   |                           |<-------------------------|
   |                           | create ACTIVE user        |
   |                           | link account, INVITED -> ACTIVE
   |                           |-------------------------->|
   |                           |  POST /api/v1/auth/login  |
   |                           |<-------------------------|
   |                           | access/refresh tokens (S1-03 reused)
```

The invitation machinery is the platform's, reused — not a second
concept: `user_invitations` gains a tester flavor (V19), the accept
flow IS `POST /api/v1/auth/register`, and tokens come from the
existing login. A tester invitation never grants an organization
membership (that is the org-member invitation flavor; tester
memberships attach per tenant in S6-03).

## Endpoints (base `/api/v1/testers`)

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/` | `org:testers:invite` at the body's `organizationId` | `201` tester view (born `INVITED`) with the invitation block (`invitationToken`, `acceptUrl`, `expiresAt` — shown exactly once); duplicate address `200` with the existing tester and its live invitation state (`null` when no invitation is live — expired or revoked; no token is ever re-shown); `400` invalid email/missing `fullName`; `404` unknown organization. Honors `Idempotency-Key` (S1-08) |
| GET | `/?organizationId=...&status=...` | `org:testers:read` at that organization | `200` the organization's testers, cursor-paginated (`limit`/`cursor`), email-ordered; optional `status` filter (`INVITED`/`ACTIVE`/`SUSPENDED`) |
| GET | `/{id}` | `org:testers:read` at the tester's owning organization (`tester` target) | `200` tester view |
| POST | `/{id}/invitations` | `org:testers:invite` at the tester | `201` fresh invitation (revokes the tester's live one first); `409 TESTER_ALREADY_ACTIVE` (accepted onboarding) / `TESTER_INVALID_TRANSITION` (SUSPENDED) / `TESTER_INVITATION_IN_FLIGHT` (a concurrent re-invitation won the race — retry) |
| PUT | `/{id}/status` | `org:testers:manage` at the tester | `200`; `ACTIVE <-> SUSPENDED` transition; `409 TESTER_INVALID_TRANSITION` for anything else (see lifecycle below) |

Unknown testers are `404` for permitted callers; target-scoped
permission denials render as concealed `404`s with the same shape
(PRD 12) — another organization's tester is indistinguishable from a
missing one. All errors use the problem+json envelope (see
[API conventions](api-conventions.md)).

## Identity semantics

- **Email** is the tester identifier, normalized (lower-cased,
  trimmed) and unique per organization
  (`uq_testers_organization_email`). Cross-organization duplicates
  are legitimate — one person, two tester records.
- **`userId`** is null until acceptance: the INVITED record exists
  before any account (V19 widened V1's column, the V15 `build_number`
  precedent). Acceptance links the account and flips the record to
  ACTIVE (`TESTER_ACTIVATED` audit).
- **Invitations** are single-use tokens stored as SHA-256 digests,
  default 7-day TTL (`shipperdog.tester.invitation-ttl`), revoked by
  re-invite, marked `EXPIRED` on first refused use (the S1-03
  `REQUIRES_NEW` semantics), and constrained to one live `PENDING`
  row per tester by the partial index
  `uq_user_invitations_pending_tester`.
- **Accepting** requires the invited address; an address that already
  owns an account is refused `409 EMAIL_TAKEN` — tester onboarding
  can never claim an existing account. Login after acceptance is the
  standard email/password flow (the S1-03 lockout policy included).
  Deactivating the owning organization does NOT block tester
  acceptance or login (deliberate for V1): testers hold no
  organization membership, so the deactivated-organization login
  block never applies — lifecycle and revocation semantics arrive
  with S6-03/S6-04.

## Email delivery

Invitation mail goes through the notification seam
(`notification.EmailSender`). No transport exists in M1: the default
implementation logs the message on the audit logger (the S1-03
password-reset delivery decision), and the accept link is returned to
the inviting administrator in the creation response — dev-mode
visibility until SMTP arrives with S12-02. The `Tester invited`
NOTIFICATION EVENT (publish/subscribe, PRD 11) is reserved for the
S12-01 notifications epic; the audit events below are the durable
record meanwhile.

## Lifecycle

```text
INVITED --accept--> ACTIVE <--suspend/reactivate--> SUSPENDED
```

- `INVITED -> ACTIVE` belongs to the accept flow alone (the manual
  status surface refuses it).
- `ACTIVE <-> SUSPENDED` is the admin surface, audited as
  `TESTER_STATUS_CHANGED` with before/after.
- A SUSPENDED tester cannot authenticate by ANY credential path:
  login, refresh, and personal access tokens are all refused (uniform
  401 / null PAT authentication; audit reason `tester_suspended`).
  Platform administrators stay exempt, mirroring the
  deactivated-organization block. Suspension is reversible; full
  revocation semantics arrive with S6-04 (V1's `DISABLED` label stays
  reserved).

## Audit events

| Event | Emitted when |
|---|---|
| `TESTER_INVITED` | invitation minted (create and re-invite; `reinvited=true` distinguishes) |
| `INVITATION_CREATED` | the reused identity machinery records the flavor (`flavor=tester`, `testerId`) |
| `TESTER_ACTIVATED` | acceptance linked the account and flipped the record |
| `TESTER_STATUS_CHANGED` | suspend/reactivate with `from`/`to` |

## Configuration

| Property | Env | Default |
|---|---|---|
| `shipperdog.tester.invitation-ttl` | `TESTER_INVITATION_TTL` | `P7D` |
| `shipperdog.tester.portal-base-url` | `TESTER_PORTAL_BASE_URL` | `http://localhost:5173` |
| `shipperdog.tester.default-groups` | `TESTER_DEFAULT_GROUPS` | PRD 8.9's examples (see [tester groups](tester-groups.md)) |

The portal base URL prefixes the accept link; the tester-facing
portal experience arrives with E8. The default-groups list seeds
tenant creation with starter groups (S6-02).
