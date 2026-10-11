# Audit logging (S1-06)

The immutable audit trail behind every security-sensitive operation
(PRD section 27, FR-024): who did what, when, from where, and why,
recorded append-only and queryable by compliance reviewers. Implemented
by the `audit` module (`backend/src/main/java/com/shipperdog/api/audit`).
Every security event since S1-03 already flows through the module's
`AuditEventPublisher` seam — S1-06 makes that same seam durable and
transactional without changing any caller.

## Recording events

The one-line API is `AuditLogger` (inject it; it stamps `occurredAt`
from the shared clock when omitted):

```java
audit.record(AuditEvent.builder(AuditEventType.ORGANIZATION_UPDATED)
        .userId(actorId)
        .orgId(organizationId)
        .before(beforeJson)
        .after(afterJson)
        .reason("credential rotation")
        .success(true)
        .build());
```

The publisher binding enriches every event with request context
(correlation id, client IP — attached only where the event did not
carry one), scrubs it (see [Redaction](#redaction)), writes one
structured log line, and persists it. Events published inside a
transaction are persisted in that transaction; events outside one
(denials, login flows) persist immediately.

Every authenticated permission denial is captured automatically by the
RBAC enforcement point as an `ACCESS_DENIED` event carrying the actor,
the required permission, and the attempted method/URI — opaque denials
(concealed 404s and plain 403s alike; see
[authorization](authorization.md)) leave no HTTP trace of what was
denied, so the audit trail is the only record. Unauthenticated attempts
are 401s at the filter chain and are not audited there.

## Event catalog

One `audit_events` row per event (V5 migration; see
[database migrations](database-migrations.md)). Field set per FR-024:

| Field | Meaning |
|---|---|
| `eventType` | kind, from the `AuditEventType` vocabulary below |
| `occurredAt` / `createdAt` | when it happened / when it was persisted (within the 2 s SLO, PRD section 42) |
| `userId` | acting user (null for anonymous) |
| `orgId` / `tenantId` | tenancy context |
| `applicationId` / `versionId` / `artifactId` | affected entity references (plain UUIDs — the table is deliberately FK-free so history outlives its entities) |
| `ipAddress` | client address (first `X-Forwarded-For` hop when proxied), stored truncated to 45 chars |
| `correlationId` | request correlation id (see below) |
| `before` / `after` | JSON snapshots of the changed state (nullable) |
| `reason` | human/compliance-readable motivation (max 512 chars) |
| `success` | outcome |
| `detail` | machine-readable context as JSON (marker codes and similar) |

`AuditEventType` values are never removed; stories append kinds as they
integrate the framework. Current vocabulary: identity
(`USER_REGISTERED`, `INVITATION_CREATED`, `LOGIN_SUCCESS`,
`LOGIN_FAILURE`, `USER_LOCKED_OUT`, `PASSWORD_RESET_REQUESTED`,
`PASSWORD_RESET_COMPLETED`, `TOKEN_ISSUED`, `TOKEN_REFRESHED`,
`TOKEN_REVOKED`, `TOKEN_REUSE_DETECTED`, `PAT_CREATED`,
`PAT_REVOKED`, and since S14-01 the SSO vocabulary
(`SSO_LOGIN_SUCCESS`, `SSO_LOGIN_FAILURE` — carrying the distinct
refusal reason the browser's uniform redirect never reveals; SAML
refusals carry `saml_*` reasons since S14-02,
`SSO_IDENTITY_LINKED`, `SSO_USER_JIT_PROVISIONED`, `SSO_ROLE_MAPPED`,
`SSO_ACCOUNT_LOCKED`, `SSO_LOGOUT` — see
[authentication](authentication.md)),
organization (`ORGANIZATION_CREATED`, `ORGANIZATION_UPDATED`,
`ORG_MEMBER_ADDED`, `ORG_IDP_CONFIGURED`, `ORG_MEMBER_ROLE_CHANGED`,
`ORG_MEMBER_REMOVED`), tenant (`TENANT_CREATED`, `TENANT_UPDATED`,
`TENANT_MEMBER_ADDED`, `TENANT_MEMBER_ROLE_CHANGED`,
`TENANT_MEMBER_STATUS_CHANGED`, `TENANT_MEMBER_REMOVED`,
`CROSS_TENANT_ACCESS` — a platform administrator entering a
tenant-scoped route through the override, carrying actor, tenant, and
method/URI; see [authorization](authorization.md)), application
(`APPLICATION_CREATED`, `APPLICATION_UPDATED`,
`APPLICATION_SCOPE_CHANGED`, `APPLICATION_TENANT_AUTHORIZED`,
`APPLICATION_TENANT_DEAUTHORIZED`
— registry registration, metadata edits, the audited ownership-scope
transition carrying `ownershipScope.before/after` and
`tenantId.before/after` (S3-02)
and metadata/lifecycle edits, with identifier changes audited as
before/after pairs because they affect artifact matching, S3-01, and
the shared-application tenant authorization mapping's grant and
revocation (S3-03) carrying the authorized tenant, the pair's
resulting status, and the request's `reason` on the event's reason
field), version (`VERSION_CREATED` — issue #17/S4-01: version
creation under an application, carrying `versionName` and birth
`status`;
`VERSION_TAGS_CHANGED` — issue #18/S4-02: a replace-set of a
version's tags that actually changed something, carrying
`versionName` plus comma-joined `added` and `removed` lists (each
present only when non-empty; a PUT that changes nothing writes no
event and no rows). Concurrent replaces of one version are serialized
by a per-version advisory lock inside the mutation's transaction, so
the trail records one event per real transition, last writer's set
winning; the ownership-scope transition also counts the versions it
carries along as `versionsCarried` on `APPLICATION_SCOPE_CHANGED`;
`VERSION_ARCHIVED` — issue #19/S4-03: the archive transition,
carrying `versionName`, `fromStatus`, and `toStatus`; and
`VERSION_DISTRIBUTION_REVOKED` — issue #19/S4-03: the
revoke-distribution takedown, carrying `versionName`, `fromStatus`,
`toStatus`, and the mandatory `reason` (FR-024: the takedown explains
itself in the trail, and the same reason rides the version's
`version_status_history` timeline row). One event per real
transition: the lifecycle commits through a database compare-and-set,
so concurrent transitions never double-commit — a repeat or a
forbidden direction answers the precise 409 and writes nothing),
release (`RELEASE_CREATED` — S9-01/issue #55: a DRAFT release
created with its full target set, FR-024's governed-distribution
record — carrying `releaseRef` (the quotable R-number), `versionId`,
`status`, `ring`, and the complete target set as `artifacts`
(platform:artifactId per slot) and `targets` (tenantId:groupCount per
target, sorted); one event per real creation, an idempotency replay
writes nothing; `RELEASE_TRANSITIONED` — S9-02/issue #56: one
committed crossing of PRD 17's state machine, carrying `releaseRef`,
`fromStatus`, `toStatus`, and the `reason` of the failure and
rollback crossings (mandatory there, FR-024); one event per real
transition — the lifecycle commits through a database
compare-and-set, so concurrent crossings never double-commit, and a
forbidden direction answers the precise 409 writing nothing;
`RELEASE_MANIFEST_FROZEN` — S9-03/issue #57: the PRD 16 reproducibility
document frozen in the APPROVED crossing's committing transaction,
carrying `releaseRef`, the manifest's `sha256` content digest (the
value that pins WHAT the release shipped, AC4), the artifact and
target counts, and the `ring`; exactly one per release ever — the
document is write-once, so the event is too;
`RELEASE_GATE_RESULT_RECORDED` — S10-03/issue #42: one quality-gate
result recorded for a release, carrying `releaseRef`, `gateType`,
`outcome` (PASSED/FAILED as an external system submitted, or
PASSED/FAILED/NO_DATA as the evaluation resolved a metric-backed gate
from analytics — the `source` member says which), `evidenceUrl`, and
the `observedValue` of a threshold/ceiling gate; the submission,
pass, and fail events of the story's AC6 are this one event's outcome
member — one record per report, the append-only history behind the
current state's "latest wins"; and
`RELEASE_GATE_OVERRIDDEN` — S10-03/issue #42: the governed exception
of FR-026 — a blocking (FAILED or NO_DATA) gate overridden, carrying
`releaseRef`, `gateType`, the overridden `state`, and the MANDATORY
`reason` with the actor; the row keeps reporting the underlying
state, the override governs only whether it still blocks; beside the
definition surface's `GATE_DEFINITION_CREATED`/`GATE_DEFINITION_UPDATED`
— the required-gate catalog an organization defines, carrying
`gateType`, `required`, the expected-result `config`, and the `ring`
attachment), the ring surface's `RING_CREATED`/`RING_UPDATED`/
`RING_DELETED` — S10-04/issue #43: every ring edit audited because it
changes who can receive what — creation and deletion carry the full
facts (`code`, `name`, `position`, the eligibility summary, the three
references), edits carry only the CHANGED members, and the promotion
engine's pair — S10-05/issue #44: `RELEASE_PROMOTED`, one event per
committed hop, carrying `releaseRef`, the two rings' codes, the
tenants moved (the selective record), and whether the hop carried the
release onto PRD 17's PROMOTED crossing; and
`RELEASE_PROMOTION_BLOCKED`, a refused attempt with recorded evidence,
carrying `releaseRef`, the ring the hop would have entered, and the
complete blocker list (the append-only promotions timeline beside it;
INV-008 — the artifacts and manifest the release froze are untouched),
the policy engine's own family — S10-06/issue #45 (PRD 33):
`RELEASE_POLICY_RULE_CREATED` / `RELEASE_POLICY_RULE_UPDATED` /
`RELEASE_POLICY_RULE_DELETED` (the standing rules' authoring, audited
in full — rule edits change what mechanically refuses a promotion, the
rings' governance standing); `RELEASE_POLICY_SETTINGS_UPDATED` (the
skip-relaxation knob — widening the promotion walk is a governed act);
`RELEASE_POLICY_EVALUATED` (one per promote attempt: the ring, the
verdict, every fired rule named — the summary beside the evaluation
log's per-rule rows); and `RELEASE_POLICY_HOLD_ACKNOWLEDGED` (the
explicit privileged decision to proceed past a fired HOLD rule — rule,
ring, and the mandatory reason, with the actor),
the feedback landing — S11-01/issue #46 (FR-027, PRD 8.13):
`FEEDBACK_RECEIVED`, one event per committed submission, carrying the
full server-attached context as entity references (`orgId`,
`tenantId`, `applicationId`, `versionId`, `artifactId` when the tester
named their build, the acting tester's `userId`) with `releaseRef`,
`type`, the attachment count, and the REPORTED `deviceModel`/`os` in
the detail map — the description's content deliberately absent (PII
minimization: the audit trail records that a report landed and what it
was about structurally, never the tester's prose; the in-process
`FeedbackReceivedEvent` carries the same facts for S12-01's bus to
relay),
the feedback triage — S11-02/issue #47 (FR-027, the QA working
surface): `FEEDBACK_SEVERITY_CHANGED` (one per classification, the
severity replaced and the severity set — the CRITICAL crossing is the
fact the open-critical-bugs metric counts); `FEEDBACK_ASSIGNEE_CHANGED`
(the assignee set or cleared, with the actor — the working queue's own
trail); and `FEEDBACK_STATUS_TRANSITIONED` (one per real lifecycle
crossing, from/to with the mandatory note of the closing and
re-opening edges — re-opens are the same event, the from/to pair says
which direction the report moved and the gate metric flips with it;
the RELEASE_TRANSITIONED discipline, never a repeat),
the device registry — S11-03/issue #48 (FR-028, PRD 8.12):
`DEVICE_REGISTERED` (one per registration, carrying the tenant scope,
the owning tester, the platform, and the device id — the identifier
deliberately absent: it is the semi-sensitive fingerprint whose mass
disclosure only the export event records, by count, never by value);
`DEVICE_UPDATED` (the descriptive fields that moved — model,
os_version; the identity columns never move and never appear);
`DEVICE_STATUS_CHANGED` (the reversible park and release, from/to);
and `DEVICES_EXPORTED` (the provisioning CSV's disclosure, carrying
the slice and the row count — the audited act itself; an unchanged
descriptive update writes nothing, nothing happened);
`ANALYTICS_EVENTS_INGESTED` and `METRIC_ROLLUPS_RECOMPUTED` — the
release analytics pipeline, S11-04/issue #49 (FR-029): the first is one
accepted SDK events batch (the release ref and the counts by type —
device identifiers deliberately absent, the standing privacy posture);
the second is one rollup recompute pass (the hourly job's lookback or
the manual trigger's window, with the rows written — idempotent by
construction, so a repeated pass is the same line, not a signal),
and artifact (`ARTIFACT_UPLOAD_SESSION_CREATED` — issue #20/S5-01: the
resumable direct-to-storage handoff is granted, carrying the
pre-allocated `artifactId` entity reference with `platform`,
`fileName`, and `expiresAt` in the detail map; and
`ARTIFACT_UPLOADED` — issue #20/S5-01: an artifact registered through
either upload path, carrying `versionId` and `artifactId` entity
references with `platform`, `fileName`, `size`, `sha256`, and
`path` (`multipart` or `resumable`) in the detail map — the
FR-024 actor/version/platform/checksum record; since issue #23/S5-04
the detail also carries the incident-answer pair `gitCommit` and
`pipeline` when the uploader asserted provenance (never fabricated);
a finalize replay writes no second event, the registered row already
has its trail;
`ARTIFACT_METADATA_EXTRACTED` — issues #21/S5-02 and #22/S5-03: the
async extraction job's terminal outcome, success or failure, carrying
`versionId` and `artifactId` entity references with `platform` and
either the extracted fields (`bundleId`, `versionName`, `buildNumber`,
`minimumOs`, `parser`, `durationMs`; Android success legs also carry
the structurally detected `format`) or the failure's `errorCode` and
`error`; recorded once per artifact — the row's write-once extraction
guard makes a double schedule a no-op;
`ARTIFACT_VALIDATED` — issue #24/S5-05: the validation pipeline's
terminal outcome per attempt, carrying `versionId` and `artifactId`
entity references with `platform`, `outcome`
(`PASSED`/`FAILED`/`BLOCKED`), `attempts`, `durationMs`, the
structural `format` when a verdict exists, and — on a stage failure —
the failing `stage` and its `errorCode` (a BLOCKED leg carries the
infra code the same way and `success=false`); one event per attempt,
so a revalidation trail reads attempt by attempt;
`ARTIFACT_REVALIDATION_REQUESTED` — issue #24/S5-05: the retry entry
accepted (`FAILED -> VALIDATING`, a blocked validation re-driven, or
a blocked extraction re-armed), carrying `versionId` and `artifactId`
entity references with the `status` the row carried at acceptance);
`DOWNLOAD_ISSUED` — issue #38/S8-04: one ALLOWED tester download
issuance (who/what/artifact/result, beside the engine's own
`AUTHORIZATION_DECISION` event for the same call — a denial emits
no `DOWNLOAD_ISSUED`, nothing was issued), carrying the full entity
reference chain (`userId` of the caller through `organizationId`,
`tenantId`, `applicationId`, `versionId`, `artifactId`) with
`success=true` and the issuance facts in `detail`: `platform`,
`channel` (DIRECT), `behavior` (the channel routing table's resolved
row), `fileName`, `size`, `urlTtlSeconds`, and `mechanism`
(`backend-proxy` on emulator deployments, `gcs-v4` in production) —
the trail that reconciles against the per-artifact `download_count`
column (V23) and the `shipperdog.download.count` metric;
tester (`TESTER_INVITED` — issue #26/S6-01: an onboarding invitation
tester (`TESTER_INVITED` — issue #26/S6-01: an onboarding invitation
minted (create or re-invite, `reinvited=true` distinguishing the
latter), carrying the `email` and `testerId` entity reference;
`TESTER_ACTIVATED` — issue #26/S6-01: acceptance linked the account
and flipped the record; `TESTER_STATUS_CHANGED` — issue #26/S6-01:
the suspend/reactivate switch with `from`/`to`;
`TESTER_DEACTIVATED` — issue #29/S6-04: deactivation (revocation
posture) with `from`/`to`, the mandatory `reason` (FR-024), the
`suspendedMemberships` cascade count, and `refreshFamiliesRevoked`;
`TESTER_REACTIVATED` — issue #29/S6-04: the explicit DISABLED ->
ACTIVE re-enable with `from`/`to`),
tester group (`TESTER_GROUP_CREATED` — issue #27/S6-02: a group
created, carrying `name` and the tenant entity reference
(`seeded=true` marks tenant-creation seeding);
`TESTER_GROUP_UPDATED` — issue #27/S6-02: a rename with
`name.before`/`name.after`; `TESTER_GROUP_DELETED` — issue #27/S6-02:
the deletion with `name` and `membersRemoved`; and
`TESTER_GROUP_MEMBER_ADDED`/`TESTER_GROUP_MEMBER_REMOVED` —
issue #27/S6-02: a membership change, carrying `groupId` and
`testerId` — idempotent replays and no-op removals write nothing),
tenant-tester membership (`TENANT_TESTER_MEMBERSHIP_CREATED` —
issue #28/S6-03: a tester linked, carrying `tenantId`, `testerId`,
and `status` (`reactivated=true` marks a re-link of a retained
REVOKED row); `TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED` —
issue #28/S6-03: the reversible suspend/reactivate switch with
`status.before`/`status.after` (no-op switches audit with equal
before/after, mirroring tenant updates; the S6-04 deactivation
cascade writes the same event with `cascade=tester_deactivated` and
the deactivation `reason`); `TENANT_TESTER_MEMBERSHIP_REMOVED` —
issue #28/S6-03: removal, which RETAINS the row REVOKED — never a
delete — with `status.before`/`status.after=REVOKED`; idempotent
re-removals write nothing),
authorization (`AUTHORIZATION_DECISION` — issue #30/S7-01: one event
per layered-engine decision, allow and deny alike, carrying `action`,
`outcome`, the compact `chain` summary (layer:status pairs), and — on
a denial — `failingLayer` and `reason`; the audited user is the
deciding subject, and for the inspection API the inspected subject
(whose events carry `surface=inspect`, and S7-03's self-scoped
decision API carries `surface=decision-api` — both distinguishable
from serving decisions since S7-02); see
[authorization](authorization.md);
`AUTHORIZATION_GRANT_CREATED` / `AUTHORIZATION_GRANT_EXPIRY_CHANGED` /
`AUTHORIZATION_GRANT_REVOKED` — issue #31/S7-02, revocation since
issue #33/S7-04: every grant mutation carries the actor (mandatory
`reason`, 1..1000 trimmed), the grant's
`grantId`/`accessType`/target (`tag`/`latestN`/`versionId` context),
`testerId`, and expiry state — the expiry change adds
`expiry.before`/`expiry.after`, and the revocation adds
`status.before`/`status.after` (`ACTIVE` to `REVOKED`), with the
bulk pass marking each of its per-grant flips `bulk=true`;
idempotent replays, no-op changes, and repeated revocations write
and audit nothing; `AUTHORIZATION_GRANT_DELETED` is historical-only
(no writer emits it since S7-04 — removal became the audited REVOKED
flip, and the row itself is RETAINED with its revocation facts as
the durable history),
and
platform (`ACCESS_DENIED`, S1-06),
the distribution family — S13-01 through S13-06 (issues #61–#66, the
vocabulary sync since #61): `DISTRIBUTION_CREDENTIAL_BOUND` /
`DISTRIBUTION_CREDENTIAL_DELETED` (a store-account binding written or
removed on the PRD 25 delegation chain — org, app, or app+env level,
references only, never material), `DISTRIBUTION_CREDENTIAL_VERIFIED`
(the resolution probe's `{resolved}` answer, never what the reference
resolves to), `DISTRIBUTION_DISPATCHED` (one release crossing's
dispatch through a channel: the channel, the outcome — distributed,
failed, or a configuration fault's code — and the release ref;
credential material deliberately absent), `DISTRIBUTION_GROUP_SYNCED`
(a tester-group link's idempotent membership push with the
provider-side drift REPORTED, never force-removed — the never-remove
doctrine of #62, the Play split of #64), `DISTRIBUTION_RECORD_REFRESHED`
(the explicit refresh or the reconciliation pass's provider-status
observation, the provider's own lifecycle word in the detail),
`DISTRIBUTION_METADATA_SAVED` / `DISTRIBUTION_METADATA_REMOVED` (the
App Store submission-metadata authoring surface, #63),
`DISTRIBUTION_PHASED_RELEASE_CONTROLLED` (one Apple phased-release
action — pause, resume, halt — with the provider's resulting curve
state, #63), `STORE_EVENT_APPLIED` (a verified store notification
mapped and its records refreshed — the webhook leg of the
review-state sync, #63), `DISTRIBUTION_ROLLOUT_CONTROLLED` (one Play
staged-rollout action — start, advance, halt, resume, reapply — with
the curve's from/to percentages and the actor, #65),
`DISTRIBUTION_ROLLOUT_DRIFT` (the reconciliation's first observation
of intended ≠ observed — the report; the re-apply of intent is always
an explicit operator action, #65), and the staged TENANT rollout
family of #66: `STAGED_ROLLOUT_PLANNED` (a plan authored with its
mode and stage count — the deterministic cohorts are inspectable on
the plan's own view), `STAGED_ROLLOUT_ADVANCED` (the commencement's
STARTED and every stage's execution — the stage's delta, cumulative
coverage, and channel arms' outcomes, with the actor on a checkpoint
approval; the plan's completion records the reached-stages summary),
`STAGED_ROLLOUT_BLOCKED` (a refused advancement — the policy rule's
reference and evidence, or the failed dispatches' codes under the
dispatch-failure rollback ownership doctrine; the refusal's evidence
commits in its own transaction, the #45 discipline), and
`STAGED_ROLLOUT_ROLLED_BACK` (the ONE-action orchestrated rollback —
the mandatory reason, every channel arm's outcome, the release's
ROLLED_BACK crossing, and the artifacts untouched, INV-008); see
[distribution](distribution.md),
the notification subscription family
(`NOTIFICATION_SUBSCRIPTION_CREATED`/`UPDATED`/`DELETED`/`TESTED` —
S12-01/S12-02, documented with its surface in
[notifications](notifications.md)),
and the CI-integration receiver family — S12-03/issue #53 and
S12-04/issue #54:
`INBOUND_WEBHOOK_ACCEPTED`/`IGNORED`/`REJECTED`/`DUPLICATE`, one per
acted-on, ignored, signature-rejected, or duplicate delivery
(carrying provider, event type, and delivery id — the
signing secret and raw payload never ride the trail),
`ARTIFACT_PROVENANCE_ENRICHED`, the audited record of the receiver's
backfill-only provenance exception ([ci](ci.md)), and
`STORE_EVENT_MAPPED` (S12-04), the record that a verified store
notification (App Store / Google Play) was mapped to a normalized
internal event — provider, event, delivery id, and the store-side
identifiers, thin by design because E13's connectors own the
semantics ([webhooks](webhooks.md)).

## Persistence and transactional semantics

The durable sink listens for republished events with a
`BEFORE_COMMIT` transactional listener: inside a transaction the
audit insert joins the caller's transaction (transactional-outbox
behavior), so an audited change and its audit row commit atomically —
a business rollback leaves no orphan audit row, and a failed audit
insert fails the business change with it. Outside any transaction the
insert executes immediately. Persistence is synchronous and well
inside the 2 s SLO. The async relay of persisted rows to the event bus
arrives with S12-01, consuming this table as its outbox.

## Append-only guarantees

Three layers in the application, one in deployment:

- The repository surface declares only `save`/`saveAll` (plain Spring
  Data `Repository` marker — no delete/update/flush-capable parent).
- The JPA entity has no mutating setters; `@PreUpdate`/`@PreRemove`
  callbacks throw on any update or removal attempt.
- No code path constructs update/delete SQL for the table; the read
  side uses the criteria API only.
- Production deployments should additionally run the API under a role
  distinct from the migration owner and `REVOKE UPDATE, DELETE ON
  audit_events` from it (documented in the V5 migration); local/CI
  schemas do not enforce this.

Hash-chain or periodic-anchoring tamper evidence is documented future
hardening, deliberately out of M1 scope (issue #6). The table carries
no `updated_at` by design — rows are never updated.

## Redaction

Applied centrally by the publisher binding to every event's `detail`
map and `before`/`after` snapshots; no caller can bypass it. Key-based:
any key whose normalized name (lower-cased, separators stripped)
contains one of the markers `password`, `secret`, `token`,
`authorization`, `credential`, `cookie`, `apikey`, `privatekey` is
redacted — casing and separator style (`apiKey`, `PRIVATE_KEY`,
`client-secret`) do not evade it. In detail maps the value becomes
`[REDACTED]`; in JSON snapshots the whole subtree under a secret key
is replaced, at any depth. A snapshot that does not parse as JSON is
replaced wholesale with `{"redacted":"unparseable-snapshot"}` so
nothing smuggles past the scrubber. Ordinary keys pass through
untouched.

## Correlation ids

Every request gets a correlation id before anything else runs — the
filter registers at highest precedence, ahead of the security chains,
so 401/403 denials are correlated too. Inbound `X-Correlation-Id` is
accepted verbatim only when it is 8-64 characters over the
URL/filename-safe alphabet (`A-Z a-z 0-9 - _ .`); anything hostile or
malformed is discarded and replaced with a fresh UUID. The id is
echoed on every response, placed in the SLF4J MDC under
`correlationId` (the log pattern renders it as `[<id>]` on every
line), attached to audit events during enrichment, and cleared after
the request. Traces join the same id since S1-07: every span of the
request carries it as the `correlation_id` attribute (see
[observability](observability.md)).

## Querying the trail

`GET /api/v1/audit` — platform administrators only
(`platform:audit:read`; per the PRD 7.1 wildcard this is platform
admins alone). Non-admins receive an opaque `403` problem+json envelope
(since S1-08; see [API conventions](api-conventions.md)) and their
attempt is audited. All filters are optional and conjunctive:

| Parameter | Type | Filters on |
|---|---|---|
| `actor` | UUID | `userId` |
| `organization` | UUID | `orgId` |
| `tenant` | UUID | `tenantId` |
| `eventType` | `AuditEventType` | event kind |
| `applicationId` / `versionId` / `artifactId` | UUID | entity reference |
| `from` / `to` | ISO-8601 instant | `occurredAt`, inclusive |
| `limit` / `cursor` | int / opaque token | pagination (`limit` default 50, 1..100, rejected outside; `cursor` from a prior `next_cursor`) |

Results are newest-first, one page per response:
`{items, next_cursor}` where each event carries the full catalog above
with `detail`/`before`/`after` as raw JSON, and `next_cursor` is
present exactly when another page follows (the platform's cursor
convention, S1-08). Malformed filter values (non-UUID, unknown event
type) and out-of-range limits fail fast with `400` problem+json.
