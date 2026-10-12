# Testers — onboarding and invitations (S6-01)

Testers are the people who exercise application versions (PRD 8.8):
each tester record is the ORGANIZATION-scoped person record — one
record per email address per organization, so the same person can be a
tester of two organizations. Tenant attachment is deliberately not
modeled here: it lives in the separate tenant-tester membership join
(S6-03, [tenant-tester memberships](tenant-tester-memberships.md))
and tester grouping in [tester groups](tester-groups.md) (S6-02);
S6-01 delivers the invitation funnel (FRD FR-010) and S6-04 the
lifecycle's revocation end — deactivation, cascade, and the
revocation latency SLA (see [Revocation semantics](#revocation-semantics-s6-04)
below): an organization administrator invites by email, the
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
memberships attach per tenant in S6-03, which also requires the
person record ACTIVE before a tenant may link it).

## Endpoints (base `/api/v1/testers`)

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/` | `org:testers:invite` at the body's `organizationId` | `201` tester view (born `INVITED`) with the invitation block (`invitationToken`, `acceptUrl`, `expiresAt` — shown exactly once); duplicate address `200` with the existing tester and its live invitation state (`null` when no invitation is live — expired or revoked; no token is ever re-shown); `400` invalid email/missing `fullName`; `404` unknown organization. Honors `Idempotency-Key` (S1-08) |
| GET | `/?organizationId=...&status=...` | `org:testers:read` at that organization | `200` the organization's testers, cursor-paginated (`limit`/`cursor`), email-ordered; optional `status` filter (`INVITED`/`ACTIVE`/`SUSPENDED`/`DISABLED`) |
| GET | `/{id}` | `org:testers:read` at the tester's owning organization (`tester` target) | `200` tester view |
| POST | `/{id}/invitations` | `org:testers:invite` at the tester | `201` fresh invitation (revokes the tester's live one first); `409 TESTER_ALREADY_ACTIVE` (accepted onboarding) / `TESTER_INVALID_TRANSITION` (SUSPENDED or DISABLED) / `TESTER_INVITATION_IN_FLIGHT` (a concurrent re-invitation won the race — retry) |
| PUT | `/{id}/status` | `org:testers:manage` at the tester | `200`; `ACTIVE <-> SUSPENDED` (reversible switch), `ACTIVE/SUSPENDED/INVITED -> DISABLED` (deactivation — body must carry `reason`, 1-500 chars), `DISABLED -> ACTIVE` (explicit re-enable, account-linked records only, memberships stay SUSPENDED); `400 INVALID_REQUEST` when a deactivation reason is missing/oversized; `409 TESTER_INVALID_TRANSITION` otherwise (see lifecycle below) |

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
  block never applies — tester-record lifecycle blocks are this
  module's own (suspension S6-01, deactivation S6-04 below). SCIM
  provisioning (S14-03) drives the same guarded transitions from the
  IdP side: deprovisioning deactivates a linked tester record through
  the S6-04 cascade (reason `scim_deactivated`), and a re-provision
  returns a DISABLED record to ACTIVE — the IdP's push is the
  organization's authoritative onboarding signal; memberships stay
  suspended ([scim](scim.md)).

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
                       +---- suspend ----> SUSPENDED --reactivate--+
                       |                                           |
INVITED --accept--> ACTIVE                                        |
     |                 |                                           |
     |                 +--- deactivate (reason) ---> DISABLED <---+
     |                                                        |
     +--- deactivate (reason) ---+               re-enable (account-linked only)
```

- `INVITED -> ACTIVE` belongs to the accept flow alone (the manual
  status surface refuses it).
- `ACTIVE <-> SUSPENDED` is the admin surface, audited as
  `TESTER_STATUS_CHANGED` with before/after (an optional `reason` is
  recorded when supplied).
- A SUSPENDED tester cannot authenticate by ANY credential path:
  login, refresh, and personal access tokens are all refused (uniform
  401 / null PAT authentication; audit reason `tester_suspended`).
  Platform administrators stay exempt, mirroring the
  deactivated-organization block. Suspension is reversible and revokes
  nothing — the refresh family survives parked, and memberships keep
  their rows and status.

## Revocation semantics (S6-04)

Deactivation (`DISABLED`, issue #29 — FR-010/INV-007) is a different
gesture from suspension, not a stronger one:

| | SUSPENDED (S6-01) | DISABLED (S6-04) |
|---|---|---|
| Posture | reversible access park | offboarding / emergency revocation |
| Reason (`reason` field) | optional, recorded | **mandatory** (1-500 chars; `400 INVALID_REQUEST` without) |
| Membership effect | none (rows untouched) | every ACTIVE membership cascades to SUSPENDED — retained, never deleted, never REVOKED |
| Sessions | refresh refused on next use; family kept | refresh families **revoked in the deactivating transaction** (`TOKEN_REVOKED`, reason `tester_deactivated`) |
| Login / PAT | blocked (`tester_suspended`) | blocked (`tester_deactivated`) |
| Return path | `SUSPENDED -> ACTIVE` restores everything | `DISABLED -> ACTIVE` is the explicit re-enable; **memberships stay SUSPENDED** until each is re-enabled per tenant (no silent restore — the documented default is manual re-enable) |
| Pre-account (INVITED) | not reachable | allowed — kills the invitation funnel (acceptance requires INVITED); a record with no linked account can never be reactivated |

- Entering `DISABLED` is allowed from `ACTIVE`, `SUSPENDED`, and
  `INVITED`; re-deactivating an already-DISABLED tester is the
  idempotent emergency re-issue (`200`, re-audited with the fresh
  reason, cascade and revocation re-run as no-ops); `DISABLED ->
  ACTIVE` requires a linked account (`userId`); nothing else leaves
  `DISABLED` (`409 TESTER_INVALID_TRANSITION`).
- INV-007 end-to-end: revocation deletes NOTHING — the person record,
  every membership row (SUSPENDED and REVOKED alike), group history,
  and audit events all remain queryable; deactivated testers keep
  appearing in the administrative views (`status=DISABLED` filter on
  the tester listing).
- Decision layer: effectiveness is the live two-switches rule (person
  ACTIVE AND membership ACTIVE — see
  [tenant-tester memberships](tenant-tester-memberships.md)), so a
  deactivated tester is denied at the next evaluation: no new
  memberships (`409 TESTER_NOT_ACTIVE`), no group joins (`409
  TESTER_NOT_TENANT_MEMBER`), no effective access. The full
  authorization-chain suite lands with S7-05.
- Audited distinctly: `TESTER_DEACTIVATED` (with `from`/`to`,
  `reason`, `suspendedMemberships` count, `refreshFamiliesRevoked`)
  and `TESTER_REACTIVATED`; each cascaded membership flip is a
  `TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED` carrying
  `cascade=tester_deactivated` and the reason (FR-024).

### Revocation latency (the SLA)

"Immediate" holds at every layer that consults state — login, refresh,
PAT authentication, and the authorization decision path are LIVE
queries (documented staleness TTL: zero). Three surfaces cannot be
revoked retroactively and are bounded instead:

- **Access tokens** are stateless JWTs with a 15-minute default TTL
  (`shipperdog.auth.access-token-ttl`, S1-03's cap); one already in
  the wild dies at expiry — the "within one token lifetime maximum"
  bound. Every protected surface it reaches still consults live state,
  so the token alone grants nothing a deactivated tester has lost.
- **Signed download URLs** (FR-014, S4-03) do not exist yet; when they
  land, their TTL is the revocation latency bound for URLs issued
  before the deactivation — 15 minutes default, configurable per
  organization at that story.
- **Emergency purge for sub-TTL latency**: drop the applicable TTL
  (access-token or signed-URL) for the organization/issuance window
  and rotate the signing secret / re-key the affected object prefix —
  already-issued URLs and tokens die with the key. This is the
  documented break-glass path until per-object revocation registries
  arrive with S4-03.

## Audit events

| Event | Emitted when |
|---|---|
| `TESTER_INVITED` | invitation minted (create and re-invite; `reinvited=true` distinguishes) |
| `INVITATION_CREATED` | the reused identity machinery records the flavor (`flavor=tester`, `testerId`) |
| `TESTER_ACTIVATED` | acceptance linked the account and flipped the record |
| `TESTER_STATUS_CHANGED` | suspend/reactivate with `from`/`to` (optional `reason`) |
| `TESTER_DEACTIVATED` | deactivation with `from`/`to`, mandatory `reason`, `suspendedMemberships`, `refreshFamiliesRevoked` (S6-04) |
| `TESTER_REACTIVATED` | the explicit DISABLED -> ACTIVE re-enable, with `from`/`to` (S6-04) |

## Configuration

| Property | Env | Default |
|---|---|---|
| `shipperdog.tester.invitation-ttl` | `TESTER_INVITATION_TTL` | `P7D` |
| `shipperdog.tester.portal-base-url` | `TESTER_PORTAL_BASE_URL` | `http://localhost:5174` |
| `shipperdog.tester.default-groups` | `TESTER_DEFAULT_GROUPS` | PRD 8.9's examples (see [tester groups](tester-groups.md)) |

The portal base URL prefixes the accept link into the tester portal
([portal](portal.md), shipped with S8-01 on its own :5174 origin,
distinct from the console's :5173). The default-groups list seeds
tenant creation with starter groups (S6-02).
