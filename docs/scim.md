# SCIM 2.0 provisioning and deprovisioning

The joiner/leaver automation (FR-025, PRD 25, RFC 7644): an identity
provider pushes Users and Groups to `/scim/v2/**`, authenticated by a
per-organization connector bearer token, and ShipperDog derives
accounts, organization roles, and tenant memberships from the pushed
state — including the security-critical leaver cascade. The surface is
deliberately outside `/api/v1` (the webhook-receiver doctrine): the
routes are anonymous at the security chain, and the connector token IS
the request's authentication, verified by the identity module before
anything is served.

## The surface

| Route | Meaning |
|---|---|
| `GET /scim/v2/ServiceProviderConfig` | Capability metadata (anonymous: deployment-level facts only) |
| `GET /scim/v2/ResourceTypes` | The resource types served (anonymous) |
| `GET /scim/v2/Schemas` | The attribute contracts served (anonymous) |
| `POST /scim/v2/Users` | Provision (the externalId-first upsert: `201` fresh, `200` replay) |
| `GET /scim/v2/Users` | List (the filter subset below; `startIndex`/`count` paging) |
| `GET/PUT/PATCH/DELETE /scim/v2/Users/{id}` | Read, replace, patch, deprovision one user |
| `POST /scim/v2/Groups` | Push a group (the externalId upsert; `displayName` unique per organization) |
| `GET /scim/v2/Groups` | List groups |
| `GET/PUT/PATCH/DELETE /scim/v2/Groups/{id}` | Read, replace membership, patch membership, delete |

Responses are `application/scim+json`. Errors are the RFC 7644
section 3.12 document (`urn:ietf:params:scim:api:messages:2.0:Error`)
on every leg — including framework faults (unreadable bodies, wrong
methods) — a deliberate, documented deviation from the platform's
[problem+json envelope](api-conventions.md): SCIM clients parse SCIM
errors and nothing else. The one exception: the rate limiter's 429
stays the platform envelope with `RATE_LIMITED` and `Retry-After` —
that tier is deployment machinery, not SCIM protocol.

Authentication is uniform by design: a missing, malformed, unknown,
rotated-away, or disabled-connector request all answer the same
**401** SCIM error. Which organization refused, whether a connector is
configured, and which leg failed never appear in the answer; the
distinct reason (`missing_token`, `invalid_token`, `connector_disabled`)
rides the `SCIM_REQUEST_REJECTED` audit line only — exactly like the
[webhook receivers'](webhooks.md) uniform-rejection discipline.

## The connector token

One live bearer token per organization, managed on the configuration
surface (below) by organization administrators holding
`org:idp-configs:write` — the identity-provider configuration family
the SSO settings already use:

| Route | Meaning |
|---|---|
| `GET /api/v1/organizations/{id}/scim-config` | Read the settings, mapping rules, and token metadata (`org:idp-configs:read`) |
| `PUT /api/v1/organizations/{id}/scim-config` | Author the settings and rules (the write is the rules' complete declarative state) |
| `POST /api/v1/organizations/{id}/scim-config/token` | Generate (or rotate) the connector token — the plaintext appears exactly once, in this response |
| `DELETE /api/v1/organizations/{id}/scim-config/token` | Revoke the live token (SCIM freezes answering until a new one is generated) |

The token follows the personal-access-token posture: server-generated
(`sds_` prefix), stored as its SHA-256 hex hash only, returned as
plaintext exactly once at generation, never on any later read — the
metadata block carries the operator label and timestamps only.
Rotation replaces the live row's hash atomically in one transaction,
so the rotated-away credential dies immediately (one live token per
organization, by constraint). Revocation deletes the row: every
in-flight connector request after it answers the uniform 401.

The `enabled` switch is the provisioning freeze: a resolved token
whose organization carries no ENABLED configuration answers the same
uniform 401 — a paused connector reveals nothing and provisions
nothing.

## The mapping rules

Two rule families, both keyed by the SCIM group `displayName`:

- **Organization role** (`groupRoleMappings`): when any of the user's
  current groups carries a rule, the most privileged mapped value wins
  — a present mapping is authoritative up or down; an absent mapping
  never demotes (the SSO role-mapping discipline verbatim). A mapping
  the last-ORG_ADMIN guard refuses is recorded on the audit line
  (`roleChangeRefused`), never fatal to the provisioning.
- **Tenant membership** (`groupTenantMappings`): each (group, tenant)
  rule of a matching group is ensured for the user. Several groups
  mapping to the same tenant resolve to the more privileged role
  (`TENANT_ADMIN` outranks `TENANT_TESTER`).

**Granting is deliberately one-directional** (documented assumption):
unmapping a group — or removing the user from it — does NOT suspend
the tenant membership the mapping granted, because a tenant membership
row carries no provenance: a manual console grant and a mapping grant
are the same row, and a declarative removal would clobber the manual
one. Revocation stays with the leaver cascade (below) and the tenant
administration routes. Provenance-tracked declarative removal is a
recorded follow-up.

A provisioned user with no matching role mapping receives the
configured `defaultRole` (the membership floor; `VIEWER` by default).
Mapping rules may only reference tenants owned by the configuration's
organization — a cross-organization tenant reference is a validation
refusal.

## Provisioning semantics

**Create is the externalId-first upsert.** The SCIM id is a separate
stable UUID from the account's; `externalId` (the IdP's own stable
person id) is the join key, unique per organization:

- A fresh `externalId` provisions: a password-less account is created
  (or, when the payload's primary email already belongs to an account,
  that account LINKS — one account, no duplicate), the org membership
  floor is granted, and assignments derive from the current groups.
  Answers `201`.
- A known `externalId` applies the payload onto the existing resource
  and answers `200` — the same stable SCIM id, never a duplicate.
- A `userName` that exists under a different `externalId` is a
  `409 uniqueness` refusal; so is the email of another account, and so
  is any platform administrator's address (the IdP of one organization
  does not own the platform's operators).
- `active=false` on create provisions and then immediately takes the
  leaver state — the honest reading of the payload.

Concurrent duplicates are idempotent by construction (the lost-race
discipline of issues #26/#27): concurrent creates of the same
`externalId` race past the checks, and the losers re-derive the
committed winner — one `201` and `200`s, never a 500.

**Email is the primary `emails[type=primary]` value;** absent that,
an email-shaped `userName`. The account's address follows the IdP's
email changes; a change onto another account's address is a 409.

**PATCH** supports the operations IdPs actually send: attribute
replaces (Entra sends `active` as the string `"True"`/`"False"` —
accepted), whole-object replaces on a blank path (Okta's shape), name
sub-attribute adds, and email array replaces. `remove` operations on
User attributes are refused loudly (`400 invalidPath`): this subset
has no removable optional attribute, and a quiet acceptance would lie
about state. Deprovisioning is `PATCH active=false` or `DELETE`.

**DELETE is deactivation, never a row delete.** The SCIM id and the
join stay stable for the re-provision.

## The leaver cascade and the re-provision doctrine

`active=false` (PATCH/PUT) or `DELETE` crosses the account to the
hard lock — the same machinery an IdP session-kill uses:

- the account becomes `LOCKED` with no expiry: password login, token
  refresh, and PAT authentication all refuse it immediately;
- every refresh token family is revoked (reason `scim_deactivated`);
- every tenant membership is suspended (one bulk statement);
- a linked tester record is deactivated (its membership cascade and
  the revocation latency SLA ride along).

**Re-provision lifts the lock but restores nothing silently.**
`active=true` (or a re-create with the same `externalId`) returns the
account to `ACTIVE` and re-derives assignments from the CURRENT group
state; the linked tester record returns to `ACTIVE` (the IdP's
re-provision is the organization's authoritative onboarding signal).
Suspended tenant memberships stay suspended — the S6-04
manual-restoration doctrine: a tenant administrator re-grants
deliberately, nothing is silently restored by an IdP push.

## Identity-provider setup

Per provider, the operator: (1) generates the connector token on the
scim-config surface and stores it in the IdP's credential vault, (2)
points the IdP's SCIM provisioning endpoint at
`https://<deployment>/scim/v2` with the bearer token, (3) maps the
IdP's groups to ShipperDog roles/tenants in the mapping rules, and
(4) enables the connector.

- **Microsoft Entra ID**: Enterprise application -> Provisioning ->
  automatic. The endpoint URL is the tenant's SCIM base; Entra sends
  its GUID `externalId`, the UPN as `userName`, PATCHes `active` as a
  string, and tolerates the enterprise extension block (accepted and
  ignored). Enable both User and Group sync; group pushes carry the
  `displayName` the mapping rules key on.
- **Okta**: Application -> Provisioning -> SCIM 2.0. Okta sends
  whole-object PATCH values on a blank path (supported) and the
  remove-with-filter-path membership operations on Groups
  (`members[value eq "..."]`, supported). The `userName` is the Okta
  login; give it the account's email shape or send a primary email.
- **Google Workspace**: Google sends no SCIM by default — through a
  provisioning bridge, set `externalId` to the Google user id and
  send the account address as the primary email; the primary email
  wins over `userName` and over secondary email entries.

The listing subset IdPs rely on: filters `userName eq "value"` and
`externalId eq "value"` (single- or double-quoted; anything else is a
loud `400 invalidFilter`), one-based `startIndex`/`count` pagination
(default 100, ceiling 200), and RFC 7644 ListResponse envelopes.
Discovery is anonymous: Entra and Okta probe `/ServiceProviderConfig`
before their first write.

## Audit, rate limits, and configuration

Every provisioning event is audited (queryable per organization on
the admin trail): `SCIM_CONFIGURED`, `SCIM_TOKEN_ROTATED` (never the
token or its hash), `SCIM_USER_PROVISIONED`, `SCIM_USER_UPDATED`
(attribute updates that change nothing emit no event),
`SCIM_USER_DEACTIVATED`, `SCIM_USER_REACTIVATED`, `SCIM_GROUP_SYNCED`,
and `SCIM_REQUEST_REJECTED` (the uniform 401's reasons — the only
place they exist). See [audit](audit.md).

`/scim/v2/**` is throttled per client IP (the S8-05 limiter): burst
120, refilling every 6 seconds by default — sized above a healthy
connector's steady state (Entra batches creates; Okta syncs per
change) while throttling a mis-wired one before it costs token-hash
or provisioning work. The 429 is the platform envelope, not the SCIM
error shape (above).

| Property | Env | Default | Meaning |
|---|---|---|---|
| `shipperdog.rate-limit.rules.scim.capacity` | `RATE_LIMIT_SCIM_CAPACITY` | 120 | The per-client burst bound on `/scim/v2/**` |
| `shipperdog.rate-limit.rules.scim.refill` | `RATE_LIMIT_SCIM_REFILL` | PT6S | The refill cadence |

The rest of the surface carries no environment configuration: tokens
and mapping rules are per-organization state, authored through the
scim-config API (environment would be the wrong grain).
