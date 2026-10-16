# Security model

ShipperDog's security posture is fail-closed by construction: deny by
default, conceal what callers may not know, record everything, and
never mutate what has been promised. This page is the user-facing
summary of the guarantees your security and compliance reviewers will
ask about.

## Immutable artifacts, permanent history

- **Artifacts are write-once.** A binary is stored once under a
  content-addressed key; re-uploading identical content registers a
  new artifact, and there is no overwrite or delete anywhere. The
  SHA-256 is proven at upload, re-proved at validation, and pinned in
  every release manifest.
- **Version sets freeze with distribution.** The moment a version
  carries any release — a draft counts — its artifact set is frozen.
  Corrections are new versions, never edits in place.
- **History is never rewritten.** Audit events, release timelines,
  promotion timelines, gate results, approval rounds, and revocation
  records are append-only. Rollback and revocation stop distribution;
  they delete nothing.

## The signed-URL download path

Testers never receive storage locations, keys, or proxy tokens.
Downloading runs the full seven-layer authorization decision
([authorization](/concepts/authorization.md)), and only an ALLOW mints
a short-lived signed URL — bounded at or under 15 minutes, and
structurally never outliving the revocation backstop: the platform
refuses to start if the URL lifetime would exceed the decision-cache
window that bounds revocation latency. A revoked grant, a suspended
tester, or a rolled-back release denies the very next decision; any
URL already minted dies within its bounded minutes.

## Concealment

Authorization failures are indistinguishable from absence: a denied
caller receives the byte-identical `404` a genuine miss produces, with
no hint of which check failed. Existence is treated as information.
The details of who was denied what live in the audit trail, available
to administrators — never in HTTP responses to strangers.

## Tenant isolation

Isolation is enforced at two layers (endpoint and database query
construction — see
[tenancy and isolation](/concepts/tenancy-isolation.md)), guarded by
architecture tests, and proven continuously by an automated
cross-tenant test suite that runs on every build. Cross-tenant access
by platform administrators is the sanctioned, explicitly audited
exception — never available to organization roles.

## Credentials and secrets

- Passwords are Argon2id-hashed with parseable parameters (raisable
  without invalidating accounts); lockout and reset flows are
  timing-uniform.
- Provider client secrets, store credentials (Apple, Google Play), and
  webhook HMAC secrets live behind secret-store references the
  platform holds — secret material is written once at configuration
  time and never echoed again. Store credentials follow a delegation
  chain (organization default, application override, environment
  override — production credentials stay distinct from QA).
- Personal access tokens, SCIM connector tokens, and telemetry tokens
  are shown exactly once and stored only as SHA-256 digests.
- Session tokens rotate on every refresh; reuse of a rotated token
  revokes the whole family. Role demotions, membership removals, and
  tester deactivation revoke sessions in the same act.

## The audit trail and SIEM

Every security-relevant operation writes an append-only audit event —
who, what, when, from where, against which tenant/application/version/
artifact, with before/after and reason where applicable: logins and
denials, role changes, artifact uploads, approval signatures, gate
overrides, promotions, downloads, grant revocations, SSO events. Audit
streams export to your SIEM over HTTPS collectors or batched bucket
deliveries, with a delivery ledger, dead-letter handling, and replay.

## Fail-closed governance

- **Gates and policies block on missing data** — `NO_DATA` is not a
  pass.
- **Unresolvable approval policies fail closed** — a dangling policy
  name refuses the crossing rather than silently running a weaker
  chain.
- **Overrides and acknowledges are exceptional, audited, and
  reason-mandatory** — one governed exception per fact, never a
  routine bypass.
- **ABAC policies can only narrow access** — there is no allow
  outcome.

## Hardening details

Rate limiting guards the login and download tiers. Security headers
(strict CSP, HSTS, frame denial) ship on the console, portal, and API
origins. Webhook receivers verify HMAC signatures constant-time over a
rotating credential list with a replay window. SAML assertions
verify against pinned certificates with wrapping and replay defenses.
The platform's own e2e surfaces run against the same posture CI
grades.

## Where to go deeper

- [Authorization](/concepts/authorization.md) — the decision chain
- [Identity and SSO](/concepts/identity-sso.md) — the credential
  postures
- Developer detail: the
  [authentication](https://github.com/ShristiLabs/shipperdog/blob/main/docs/authentication.md),
  [audit](https://github.com/ShristiLabs/shipperdog/blob/main/docs/audit.md),
  [SIEM](https://github.com/ShristiLabs/shipperdog/blob/main/docs/siem.md),
  and
  [webhooks](https://github.com/ShristiLabs/shipperdog/blob/main/docs/webhooks.md)
  docs, plus the ADRs (token storage, object storage, tenant isolation)
  in the repository's docs directory.
