# Database migrations

Flyway owns the PostgreSQL schema (ADR-0001). This page records the binding
conventions; every migration must follow them.

## Layout

```text
backend/src/main/resources/db/
  migration/            schema migrations, always applied
    V1__baseline_schema.sql
    V2__identity_auth.sql
    V3__organization_management.sql
    V4__rbac_roles.sql
    V5__audit_framework.sql
    V6__api_conventions.sql
    V7__tenant_lifecycle.sql
    V8__tenant_member_status.sql
    V9__application_registry.sql
    V10__application_ownership_scope.sql
    V11__tenant_application_authorization.sql
    V12__version_management.sql
    V13__version_tags.sql
    V14__version_lifecycle.sql
    V15__artifact_upload.sql
    V16__artifact_extraction.sql
    V17__artifact_provenance.sql
    V18__artifact_validation.sql
    V19__tester_onboarding.sql
    V20__tenant_tester_membership_suspend.sql
    V21__version_access_grants.sql
    V22__grant_revocation.sql
    V23__artifact_download_count.sql
    V24__release_model.sql
    V25__release_state_history.sql
    V26__release_manifest.sql
    V27__environment_management.sql
    V28__approval_workflows.sql
    V29__approval_modes.sql
    V30__approval_skipped_shape.sql
    V31__quality_gates.sql
    V32__release_rings.sql
    V33__release_promotion.sql
    V34__release_policy_engine.sql
    V35__feedback_submission.sql
    V36__feedback_triage.sql
    V37__device_management.sql
    V38__release_analytics.sql
    V39__event_bus_notifications.sql
    V40__slack_teams_notifiers.sql
    V41__inbound_webhook_deliveries.sql
    V42__store_status_events.sql
    V43__distribution_adapter_framework.sql
    V44__testflight_connector.sql
    V45__appstore_submission.sql
    V46__google_play_connector.sql
    V47__play_production_rollout.sql
    V48__staged_tenant_rollout.sql
    V49__oidc_sso.sql
    V50__saml_sso.sql
    V51__scim_provisioning.sql
    V52__abac_policy_engine.sql
    V53__siem_audit_stream.sql
    V54__telemetry_sdk.sql
    V55__ota_bundles.sql
    V56__ota_rollout.sql
    V57__ota_telemetry.sql
    V58__ota_signing_keys.sql
    V59__ota_release_integration.sql
    V60__ota_rollout_paused_event.sql
    V61__ota_alert_rules_org_grain.sql
  seed/                 dev fixture, applied only when the seed location is active
    R__dev_seed.sql
```

- Schema migrations: `V<n>__description.sql`, snake_case, monotonically
  increasing `n`. Never renumber, edit, or delete a migration that has landed.
- The `dev` profile appends `classpath:db/seed` to
  `spring.flyway.locations` (see `backend/src/main/resources/application.yml`);
  the default (production, clean CI) location list is `classpath:db/migration`
  only. Override with the `FLYWAY_LOCATIONS` environment variable if needed.
- The seed is a repeatable migration (`R__`) with fixed UUIDs and
  `ON CONFLICT DO NOTHING`, so re-runs are no-ops. It provides one
  organization (`external_id demo-shipperdog`), two tenants
  (`demo-acme`, `demo-contoso`), and one application; the bootstrap
  admin is a platform administrator and `ORG_ADMIN` of the organization
  (S1-04). Later integration tests rely on these identifiers. Since
  S8-05 it also provisions the demo tester persona
  (`tester@shipperdog.local`, local-only password documented in the
  seed file): user, tester record, ACTIVE Acme membership, one
  working-set version (`1.0.0`), and an application-wide
  `version_access` grant — the post-deploy smoke test's login and
  catalog legs run as this tester ([deployment.md](deployment.md)).

## Table conventions

- Primary keys: `id uuid PRIMARY KEY DEFAULT gen_random_uuid()`. Pure
  junction tables use composite primary keys over their foreign keys
  (`tenant_tester_memberships`, `tester_group_members`, `version_access`,
  `release_targets`, `release_approvals`, `distribution`,
  `environment_config`).
- Audit columns on every table: `created_at timestamptz NOT NULL DEFAULT
  now()` and `updated_at timestamptz NOT NULL DEFAULT now()`. The
  application layer maintains `updated_at` (planned JPA auditing); there are
  no database triggers by decision. The one exception is the append-only
  `audit_events` table (V5), which carries no `updated_at` because its
  rows are never updated.
- Foreign keys are declared bare (`REFERENCES` with no explicit action),
  so PostgreSQL applies its default referential action `NO ACTION`:
  deleting (or updating the key of) a referenced row is rejected. There
  is no `ON DELETE CASCADE` anywhere. Deleting rows with dependents is
  intentionally hard.
- Future migrations should name foreign keys explicitly
  (`CONSTRAINT fk_<table>_<column> FOREIGN KEY ...`). The V1 baseline
  predates this rule and carries PostgreSQL-generated
  `<table>_<column>_fkey` names; it stays untouched per the
  forward-only policy.
- Every tenant-scoped table has an index on `organization_id` /
  `tenant_id` as applicable (`idx_<table>_<column>`). Where a UNIQUE
  constraint's leading column already serves the lookup, no extra index is
  created.
- `tenant_id` on `applications` is NULL for organization-owned applications;
  the explicit `ownership_scope` discriminator arrives with S3-02.
- Forward references: `testers.user_id`, `artifacts.uploaded_by`,
  `releases.created_by`, `release_approvals.approver` referenced the
  identity user table before it existed; `V2__identity_auth.sql` (S1-03)
  created `users` and added the promised foreign keys with explicit
  `fk_<table>_<column>` names.
- `V3__organization_management.sql` (S1-04) is the template for these
  conventions: it creates `organization_members` (uuid PK, unique
  `(organization_id, user_id)`, `idx_organization_members_user_id`) and
  `organization_idp_configs` (unique `organization_id`), adds
  `organizations.external_id` (unique), `users.platform_admin`, and the
  optional invitation scope `user_invitations.organization_id` +
  `role` guarded by `ck_user_invitations_org_role_pair` (both set or
  both null). All constraints use the explicit `fk_`/`uq_`/`ck_`/`idx_`
  names.
- `V4__rbac_roles.sql` (S1-05) is the RBAC vocabulary migration: it
  expands `org_role` with the seven PRD section 7 functional roles
  (`APP_OWNER`, `DEVELOPER`, `QA_ENGINEER`, `RELEASE_MANAGER`,
  `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`, `VIEWER`) via
  expand-only `ALTER TYPE ... ADD VALUE IF NOT EXISTS`, creates the
  `tenant_role` enum (`TENANT_ADMIN`, `TENANT_TESTER`) and the
  `tenant_members` table (uuid PK, unique `(tenant_id, user_id)` as
  `uq_tenant_members_tenant_user`, named `fk_`s to `tenants` and
  `users`, `idx_tenant_members_user_id`). Tenant membership is
  independent of organization membership. No statements in the
  migration use the new `org_role` values: PostgreSQL forbids using an
  enum value added in the same transaction, and the permission catalog
  lives in code, so no data migration is needed.
- `V5__audit_framework.sql` (S1-06) creates the append-only
  `audit_events` table (see [audit](audit.md) for behavior). It is the
  deliberate exception to two conventions above: the table has **no
  `updated_at`** (rows are never updated — insert-only by design) and
  **no foreign keys** (audit history must survive the entities it
  references; actors and context are recorded as plain UUIDs).
  `event_type` is `varchar(64)`, not a PostgreSQL enum, mirroring the
  `release_gates.gate_type` precedent for open vocabularies — the Java
  `AuditEventType` enum validates on write. Indexes follow
  `idx_audit_events_<column>` on `user_id`, `org_id`, `tenant_id`, and
  `occurred_at`; no unique constraints (identical events are
  legitimate). Production deployments should additionally `REVOKE
  UPDATE, DELETE` on the table from the runtime role (documented in
  the migration itself).
- `V6__api_conventions.sql` (S1-08) creates the `idempotency_keys`
  table backing the `Idempotency-Key` contract (SHA-256 hashed keys,
  24 h retention, swept by expiry; deliberately no FK on
  `principal_id`).
- `V7__tenant_lifecycle.sql` (S2-01) adds the tenant lifecycle value:
  `ALTER TYPE tenant_status ADD VALUE IF NOT EXISTS 'DEACTIVATED'`,
  mirroring the `org_status` precedent (V3); V1's `DISABLED` label
  stays reserved-unused. The `tenants` table itself is complete from
  V1 — the story needed no column or constraint changes.
- `V9__application_registry.sql` (S3-01) extends `applications` with
  `description` and `platforms` (checked `varchar(30)[]`, at least one
  platform, vocabulary IOS/ANDROID — the array keeps Hibernate's
  mapping and `ddl-auto: validate` green; the native `platform_type`
  enum stays reserved for E5's singular `artifacts.platform`), plus
  the organization-scoped identifier guards
  `uq_applications_organization_bundle` /
  `uq_applications_organization_package` (partial unique indexes —
  issue #13 acceptance criterion 2).
- `V10__application_ownership_scope.sql` (S3-02, issue #14) adds the
  PRD 14 ownership discriminator: the `application_ownership_scope`
  enum (`TENANT`/`ORGANIZATION`), the INV-002 pairing check
  `ck_applications_ownership_scope_owner` (TENANT rows carry the
  owning tenant, ORGANIZATION rows carry none), and the org-wide name
  guard `uq_applications_organization_name`, which replaces V1's
  per-scope `uq_applications_scope_name`: the registry's documented
  name uniqueness is organization-wide (S3-01, error registry), and
  the per-scope index silently under-enforced cross-scope races. The
  replacement is a constraint tightening — expand-only in effect: no
  data is dropped, and it fails loudly if an organization ever spans
  a name across tenants (none does).
- `V11__tenant_application_authorization.sql` (S3-03, issue #15) adds the
  PRD 15 distribution mapping: `tenant_application_authorizations`, one
  retained row per application x tenant pair
  (`uq_tenant_application_authorizations_pair` is the final authority on
  concurrent grants), with its own `application_authorization_status`
  vocabulary (`ACTIVE`/`REVOKED`, the V8 one-enum-per-surface precedent)
  and the revocation-pairing check
  `ck_tenant_application_authorizations_revocation` (ACTIVE rows carry no
  revocation, REVOKED rows carry both stamps). Revocation is a status
  flip, never a deletion — grant/revocation history with actor and
  reason lives in the audit trail (INV-007 philosophy). The owning
  organization is deliberately not denormalized; the application row
  stays the single source of truth.
- `V12__version_management.sql` (S4-01, issue #17) completes the V1
  `versions` table for the version layer: the owning-dimension
  snapshot (`organization_id` NOT NULL with `REFERENCES
  organizations`, nullable `tenant_id` with `REFERENCES tenants` —
  the same shape and referential ties as `applications` itself) so
  version rows are served by the tenant-isolation seam in both
  ownership modes, backfilled from each version's application (a
  no-op on the empty greenfield table but correct on any state); and
  the normalized sort key `sort_key` — five-digit zero-padded
  segments whose lexicographic order is exactly semver numeric order
  (`10.0.0` after `9.9.9`). Checks pin both shapes at the database
  (`ck_versions_version_name_semver` — numeric triple, segments
  0..99999, no leading zeros; `ck_versions_sort_key_shape`), and the
  seam predicates get `idx_versions_tenant_id` plus the partial
  `idx_versions_organization_scope` for null-tenant rows. V1's
  `uq_versions_application_version` stays the per-application
  duplicate-name authority; the one-artifact-per-platform slot
  constraint lands with E5's artifacts. When an application's
  ownership scope transitions (S3-02), its version rows carry the
  snapshot along in the same transaction.
- `V13__version_tags.sql` (S4-02, issue #18) creates the `version_tags`
  junction the story's technical note specifies — `(version_id, tag)`,
  no tag metadata in M1 — as a composite-primary-key table over its
  foreign keys (the junction convention above). The primary key is the
  duplicate authority the service's insert-if-absent composes with
  (`ON CONFLICT DO NOTHING`, the idempotency-claim precedent);
  `ck_version_tags_tag_shape` pins the public vocabulary at the
  database (UPPER normalization — only `A-Z` survive the pattern — 1
  to 50 characters, letter/digit first then letters, digits,
  underscore, hyphen; the reserved FR-009 words and arbitrary custom
  tags alike fit); `idx_version_tags_tag` serves the tag-first
  lookups (the listing filter's subquery today, the organization-wide
  tag questions of S7-02 later). The rows carry no owning-dimension
  snapshot: they are children of the seam-scoped version (ADR-0002
  seventh amendment). Timestamps are database-defaulted on insert and
  rows are never updated — a replace-set deletes and inserts.
- `V14__version_lifecycle.sql` (S4-03, issue #19) is the lifecycle and
  permanent-history migration, in two parts. First, `version_status`
  gains `REVOKED` by additive `ALTER TYPE ... ADD VALUE` — the enum
  rule this file pins, applied exactly as prescribed: appended, never
  renamed or removed, and unused elsewhere in its own migration (the
  new value cannot be referenced in the transaction that creates it).
  `REVOKED` is the strongest terminal: revoke-distribution blocks new
  downloads (E7 consumes the status) while deleting nothing (INV-006);
  `ARCHIVED` stays the softer terminal. Second,
  `version_status_history` records the chronological status timeline
  the history query returns for audit — one row per real transition
  (creation writes `DRAFT`; archive and revoke append theirs), with
  the revoke action's mandatory reason on its row
  (`ck_version_status_history_reason_length` caps it at 1000,
  matching the service validation). Like `version_tags`, the table is
  a plain child of the seam-served version: no owning-dimension
  snapshot, no seam repository, isolation enforced at the parent
  (ADR-0002 eighth amendment). The lifecycle's compare-and-set lives
  on its repository (`transitionStatusIfCurrent`): the transition
  commits only while the row's committed status still matches the
  source state the attempt read, so concurrent transitions can never
  double-commit or un-revoke a pulled version.
- `V15__artifact_upload.sql` (S5-01, issue #20) completes the V1
  `artifacts` table for the upload layer and adds the session table of
  the resumable path. Artifacts join the seam with the full
  owning-dimension snapshot — `organization_id` NOT NULL with
  `REFERENCES organizations`, nullable `tenant_id` with `REFERENCES
  tenants` (the V12 versions shape), backfilled from each artifact's
  version — because they break the eighth amendment's plain-child
  invariant twice: the body-addressed collection route
  (`POST /api/v1/artifacts`) addresses them directly, and the
  same-content detection reads them across every version of the
  application (ADR-0002 ninth amendment). `build_number` becomes
  nullable (FR-005: extracted metadata, pinned at upload only when a
  CI client knows it) under `ck_artifacts_build_number_nonnegative`;
  PostgreSQL's NULLs-distinct semantics keep V1's
  `uq_artifacts_version_platform_build` the duplicate authority — a
  pinned triple lands once, unpinned rows re-upload freely as NEW
  artifacts (INV-003's no-replace corollary, the story's third
  acceptance criterion). The #19 follow-up's narrower UNIQUE
  `(version_id, platform)` was evaluated and REJECTED: it would 409
  every re-upload and contradict that criterion. Seam predicates get
  `idx_artifacts_tenant_id` plus the partial
  `idx_artifacts_organization_scope` for null-tenant rows, and the
  dedup probe gets `idx_artifacts_sha256`. The new
  `artifact_upload_sessions` table (the >100 MB direct-to-storage
  handoff) carries the same snapshot — ephemeral is not exempt from
  INV-001 — with `uq_artifact_upload_sessions_artifact` and
  `..._storage_key` (one session, one future artifact), the build
  check mirrored, an `idx_..._expires_at` for the follow-up sweeper,
  and a plain-uuid `created_by` with no foreign key (the
  `idempotency_keys` precedent: transient rows do not wedge the user
  lifecycle). No binaries anywhere (FR-007): metadata plus
  `storage_key`; bytes live in object storage under the PRD 24 layout.
- `V16__artifact_extraction.sql` (S5-02, issue #21) adds the
  extracted-metadata tier to `artifacts` — five nullable columns,
  nothing dropped or tightened. The fields S5-05 validation matching
  compares become first-class columns (`bundle_id`, `version_name`,
  `minimum_os`; `build_number` already exists since V15 made it
  nullable for exactly this story — extraction fills it only when the
  uploader did not pin it), while the open-ended per-platform payload
  (signing information, entitlements, parser attribution, timings)
  lands in one `extracted_metadata` jsonb document — the same
  scalar-columns-plus-context-document split the audit framework
  chose. `extraction_error` jsonb is the actionable failure record:
  a malformed binary records its machine-readable code and operator
  message there while the status moves `VALIDATING` to `FAILED` (the
  one transition S5-02 owns); an infrastructure miss (queue full,
  storage unreadable after retries) records there too while the
  status deliberately STAYS `VALIDATING` — the document is what makes
  a missed extraction visible instead of silent. S5-03 (issue #22,
  Android) added no migration of its own: the Android payload
  (detected format, target SDK, signing-block presence,
  BundleConfig-sourced builds) rides the same `extracted_metadata`
  jsonb, and the identity columns it shares with iOS already exist.
- `V17__artifact_provenance.sql` (S5-04, issue #23) adds the
  build-provenance document — one nullable `provenance` jsonb column
  on `artifacts` and one on `artifact_upload_sessions`, each with an
  object-type CHECK, nothing dropped. A DEDICATED column, not a
  namespaced key inside `extracted_metadata`: that document is
  written once by the async extraction job over a write-once guard,
  so an upload-time provenance document cannot ride inside it —
  provenance is client input captured at upload (multipart form or
  session creation; finalize replays the session's capture), the
  extraction document is a server-derived outcome. The document keys
  (`gitCommit`, `gitBranch`, `pipeline`, `ciRunUrl`,
  `buildTimestamp`) are the frozen M2 CI-integration contract
  (S12-03/04); only asserted members are stored — an absent claim is
  an absent key, never a fabricated value — and the column is written
  with the registration insert and never updated (INV-003).
- `V18__artifact_validation.sql` (S5-05, issue #24) adds the
  validation pipeline's two nullable columns on `artifacts`, nothing
  dropped: `format` (varchar 8, CHECK-bounded to `IPA`/`APK`/`AAB`)
  — the STRUCTURAL container verdict the pipeline's FORMAT stage
  records once (the binary is immutable, so the verdict cannot
  change; write-once in code, null until the first verdict) — and
  `validation` (jsonb, object-type CHECK) — the per-stage record of
  the LATEST attempt, REWRITTEN per attempt (outcome, attempts
  counter, timings, and every stage's status/code/reason in FR-006
  order) because the row carries current state while the audit trail
  carries history. The `artifact_status` enum needs NO change: PRD
  18's six values are exactly the V1 vocabulary — this story
  formalizes the TRANSITIONS in code (`ArtifactStateMachine`), not
  the labels, and PRD 18 has no REVOKED state (revocation is E7's
  authorization lifecycle of INV-007, never an artifact-row state).
- `V19__tester_onboarding.sql` (S6-01, issue #26) completes the V1
  `testers` table for the tester funnel. The tester record gains the
  organization dimension PRD 13's sketch omitted (`organization_id`
  NOT NULL with `fk_testers_organization_id`, `uq_testers_organization_email`
  as the within-org identifier guard) plus `email`/`full_name`
  (normalized email, the users-table convention), and `user_id`
  becomes NULLABLE — an INVITED tester exists before any account, the
  V15 `build_number` widening precedent (V1's `uq_testers_user` stays
  valid under NULLs-distinct semantics). `tester_status` gains
  `INVITED` (the born state) and `SUSPENDED` (the reversible park,
  the V8 vocabulary precedent); V1's `DISABLED` stays reserved-unused
  for S6-04. The invitation machinery is REUSED, not duplicated:
  `user_invitations` gains `tester_id` (named `fk_`), V3's
  `ck_user_invitations_org_role_pair` is REPLACED by the broader
  three-flavor `ck_user_invitations_scope` (the V10
  constraint-replacement precedent — a widening: every previously
  valid row stays valid), and the partial unique index
  `uq_user_invitations_pending_tester` collapses concurrent mints
  onto one live PENDING row per tester. Application-side consequence:
  re-minting flushes the revocation before the replacement insert
  (Hibernate action-ordering runs inserts before updates, and the
  partial index would otherwise see two PENDING rows).
- S6-02 (issue #27) adds NO migration: `tester_groups` (with
  `uq_tester_groups_tenant_name`) and `tester_group_members` (composite
  PK `group_id`+`tester_id`, `idx_tgm_tester_id`) landed complete in
  the V1 baseline exactly as PRD 13 sketched them, and default-group
  seeding is application-level work inside the tenant-creation
  transaction, not data migration. The dev seed fixture grew a
  repeatable block provisioning the demo tenants' default groups to
  match what the API now produces; tenants created before S6-02 keep
  their original state (no retro-seeding).
- `V20__tenant_tester_membership_suspend.sql` (S6-03, issue #28)
  appends ONE value: `membership_status` gains `SUSPENDED` (the V8
  `tenant_member_status` vocabulary precedent) so the issue's three
  operations — create/suspend/remove — stay three distinct states:
  ACTIVE is effective, SUSPENDED parks the membership reversibly
  without unlinking the tester, REVOKED is removal (the row is
  retained as the historical record; a re-link reactivates it). The
  table itself is V1 baseline (`tenant_tester_memberships`, PK
  `(tenant_id, tester_id)`, `idx_ttm_tester_id`). No new index: the
  PK prefix already serves the hot authorization probe the issue's
  technical note names `(tenant_id, tester_id, status)` — an equality
  descent reads the row and therefore its status; the S7-03 cache
  landed above these probes with no schema change of its own (Redis,
  not PostgreSQL), so the index verdict stands.

- `V21__version_access_grants.sql` (S7-02, issue #31) completes the
  V1 `version_access` junction for FR-011's four grant shapes. The V1
  sketch (`PRIMARY KEY (version_id, tester_id)`, `access_type`
  `DIRECT`/`GROUP`) was a PRD 13 placeholder no code ever wrote —
  S7-02 is the table's first writer, so the table is provably empty
  and the constraint REPLACEMENT follows the V10/V19 precedent (a
  widening: no data is dropped): the surrogate `id` primary key
  replaces the composite (dropped BEFORE `version_id` relaxes its NOT
  NULL — a keyed column cannot), and `version_id` becomes nullable
  (only `EXPLICIT_VERSION` rows carry it). New columns: the granting
  `tenant_id` (the row joins the tenant-isolation seam; endpoint
  paths read it through `VersionAccessGrantScopedRepository`, the
  engine's decision probe through the module's
  `@CrossTenantByDesign` catalog), `application_id`, `tag` (V13's
  shape check pinned again), `latest_n` (1..100), `expires_at`
  (enforced at evaluation time, never swept), and `created_by`.
  `access_type` gains the four labels by additive `ALTER TYPE ... ADD
  VALUE` — unused elsewhere in the same migration (the PG enum rule,
  V4's discipline), which is why the per-shape pairing is the
  service's contract over the literal-free database guards
  (`ck_version_access_single_target` — at most one target column —
  plus the tag-shape and latest-N bound checks); V1's DIRECT/GROUP
  stay reserved-unused. `uq_version_access_grant` (UNIQUE NULLS NOT
  DISTINCT over the full grant identity, PG 15+ semantics) is the
  duplicate authority behind the idempotent create and the 409
  renewal conflict; `idx_version_access_grant_subject`
  (tenant_id, tester_id, application_id) serves the seam predicate
  and the per-tenant probes, `idx_version_access_tester_application`
  (tester_id, application_id) the shared-application decision probe,
  and `idx_versions_application_sort` (application_id, sort_key) the
  latest-N rank counts over V12's sort key.

- `V22__grant_revocation.sql` (S7-04, issue #33) turns grant removal
  into revocation (INV-007): removal is row state, never row death.
  New enum type `version_access_status` (`ACTIVE`, `REVOKED` — the
  `membership_status` precedent); the `status` column arrives NOT
  NULL `DEFAULT 'ACTIVE'`, which IS the backfill (V21 rows are all
  ACTIVE by construction). The revocation facts ride the row —
  `revoked_at` (timestamptz), `revoked_by` (FK to `users`), and
  `revocation_reason` (varchar(1000), the FR-024 mandatory reason
  kept on the row so the revocation report answers from the data) —
  shape-guarded by `ck_version_access_revocation_shape` (all three
  set or none: revocation is all-or-nothing). The one constraint
  replacement follows the V21 drop-and-recreate precedent and drops
  no data: `uq_version_access_grant` is recreated as a PARTIAL
  unique index over the same full identity (`NULLS NOT DISTINCT`
  carries over) scoped `WHERE status = 'ACTIVE'` — the duplicate
  authority behind idempotent create and the 409 renewal conflict
  now admits a re-grant after revocation as a NEW row while the
  revoked twin stays retained for auditors. Expand-only and
  forward-only (ADR-0001): additive columns, one enum type, checks,
  and the index swap; no code path deletes `version_access` rows.

- `V23__artifact_download_count.sql` (S8-04, issue #38) adds the
  per-artifact download counter: `artifacts.download_count`
  (`BIGINT NOT NULL DEFAULT 0`, the default IS the backfill — no
  downloads existed before issuance did). One increment per ALLOWED
  download issuance, written only by the entity's guarded
  `recordDownloadIssued()`; the `DOWNLOAD_ISSUED` audit trail (not
  this column) remains the history of record, and the
  `shipperdog.download.count` metric is the live signal. Purely
  expand-only: one additive column, nothing dropped or tightened.

- `V24__release_model.sql` (S9-01, issue #55) lands the release model,
  creation, and targets — the release tables' first writer since the
  V1 sketch, expanding rather than duplicating: the `rings` reference
  table (S10-04's data model pulled forward, seeded with FR-017's five
  system defaults as fixed-uuid NULL-organization rows — the first
  reference-data seed inside a versioned migration); the `releases`
  ownership snapshot (`organization_id` NOT NULL backfilled from the
  application, nullable `tenant_id` — the V12 versions shape; releases
  are route-addressed, so the ninth amendment applies), the quotable
  `release_number` (one global `release_number_seq`, unique, rendered
  `R-<number>`), and `starting_ring_id`; `release_targets`'s V1
  `ring_id` varchar renamed to legacy nullable `ring_code` (the one
  constraint relaxation — the V15 build_number precedent) with the
  real `ring_id` uuid FK as the authority; new plain children
  `release_target_groups` (composite FK to its target) and
  `release_artifacts` (one per platform per release). Expand-only and
  forward-only: additive columns, three tables, one rename, one
  relaxation, checks and indexes; no release row is ever deleted.

- `V25__release_state_history.sql` (S9-02, issue #56) adds
  `release_status_history` — the release state machine's append-only
  timeline (PRD 17, the V14 `version_status_history` doctrine applied
  to releases): one row per real transition, from the creation's DRAFT
  row (`from_status` NULL — a release is born, it does not transition
  into DRAFT), with the actor uuid on the row (the issue's
  actor/timestamp demand; the version timeline left who to the audit
  log, the release timeline carries it because it is the surface the
  dashboard renders), the optional reason capped at 1000 by a check
  (mandatory in the service for the failure and rollback crossings),
  and `occurred_at`. A pure child of the seam-served release: no
  snapshot columns, no seam repository. No enum change — V1's
  `release_status` has carried the full eleven-value PRD 17 vocabulary
  since the baseline; S9-02 is its first mover. The transitions
  themselves commit through a database compare-and-set (the V14 CAS
  discipline, held in the repository layer), which is why the schema
  needs no constraint beyond the table: the machine's serialization is
  behavioral, not declarative. Rows pre-dating V25 (dev only) begin
  their timeline with the first post-V25 transition — history is
  recorded going forward, never reconstructed.
- `V26__release_manifest.sql` (S9-03, issue #57) adds
  `release_manifests` — the PRD 16 frozen reproducibility document:
  the complete record of what a release ships, deep-copied at the
  APPROVED crossing and never updated (the freeze joins the
  transition's transaction through the S9-02 event hook). One row per
  release EVER: `uq_release_manifests_release` is the immutability
  authority, and the repository declares insert and read operations
  only. `content` is jsonb for queryability, but the stored `sha256`
  is over the application's canonical serialization (object keys
  sorted recursively, ISO-8601 instants, nulls included) — jsonb
  re-orders keys on storage, so byte-stability lives in the code and
  read-time verification re-canonicalizes before comparing. A pure
  child of the seam-served release (the V25 doctrine): no snapshot
  columns, no seam repository. `frozen_by` is a bare uuid (the V25
  actor_id precedent; the audit event keeps the full who). No
  backfill, by design: a manifest is a transition-time act, and a
  retro-frozen document would carry a falsified freeze time.

- `V27__environment_management.sql` (S9-04, issue #58) completes the
  V1 `environments` sketch (its first writer) and lands the
  environment module's schema: the stable `code` (kebab, unique per
  organization beside V1's name guard), the progression `position`,
  and the two name-only bindings `distribution_channel` /
  `approval_policy` (nullable references-by-name — E13 owns channels,
  S10-01 owns policies); FR-025's five defaults seeded for every
  organization existing at migration time (later organizations are
  seeded inside the organization-creation transaction by the
  environment module's provisioning callback, the S6-02 default-group
  precedent; the dev seed carries the same block). New tables:
  `environment_config_versions` — the append-only snapshot ledger
  (PK `(environment_id, version)`, the FULL resolved key-value map as
  a jsonb object, no `updated_at` per the append-only exception); V1's
  unversioned `environment_config` junction stays untouched legacy
  scaffolding, never written (the `release_targets.ring_code`
  precedent). `environment_tenants` — the release-target allow-list
  junction (deny-by-default; validated at release creation). New table:
  `environment_credentials` — reference-only credential bindings
  (`secret_reference` pinned printable-no-whitespace by
  `ck_environment_credentials_reference_shape`; the length bound is
  the varchar — PostgreSQL caps regex repetition counts at 255, so
  the shape check carries no explicit count). `releases.environment_id`
  — the optional environment a release targets (nullable, FK), whose
  code and RESOLVED configuration the S9-03 manifest freezes at
  APPROVED. Expand-only and forward-only: additive columns, three
  tables, checks, indexes; nothing dropped.

- `V28__approval_workflows.sql` (S10-01, issue #40) lands the approval
  workflow engine's schema — workflow as data, the issue's technical
  note (S10-02 extends the same tables with mode flags). New tables:
  `approval_workflows` — the organization-grain named chain
  definitions (one name and one ring attachment per organization,
  `uq_approval_workflows_org_name` / `uq_approval_workflows_org_ring`
  partial unique); `approval_workflow_steps` — the ordered chain (PK
  `(workflow_id, position)`, one occurrence of each role per chain,
  optional `tenant_id` scope narrowing a step to releases targeting
  that tenant); `release_approval_rounds` — one row per approval round
  (round 1 instantiates inside the READY to APPROVAL_PENDING crossing
  via the S9-02 event seam; a rejected round's rework is a NEW round,
  the rejected one stays for PRD P5 history). `release_approvals` —
  V1's never-written PRD 13 sketch EXPANDED (the releases precedent):
  `ALTER TYPE approval_type ADD VALUE` for `COMPLIANCE` and
  `RELEASE_MANAGER` (the FR-016 chain kinds V1 lacked), the id re-key
  replacing V1's `(release_id, approval_type)` primary key with the
  round-position uniqueness that admits the same type in a later
  round (the V15 constraint-relaxation precedent on a table no writer
  ever touched), plus `round_id`, `position`, `tenant_id`,
  `approver_role` (the role the approver signed under at decision
  time), `reason` (mandatory on rejects,
  `ck_release_approvals_reject_reason`), and the decision-shape check
  (`ck_release_approvals_decision_shape`: a decided row carries its
  signature, a pending one carries none). New enum types:
  `approval_step_role` — exactly the five PRD 7 roles the RBAC matrix
  grants an `app:releases:approve-*` permission (a step outside it
  would be unsatisfiable) — and `release_approval_round_status`
  (`PENDING`/`SATISFIED`/`REJECTED`); step status reuses V1's
  `approval_status` verbatim. Expand-only and forward-only.

- `V29__approval_modes.sql` (S10-02, issue #41) extends the same
  tables with FR-016's advanced modes — a definition is an ordered
  list of GROUPS. New enum types: `approval_group_mode` (`ALL` — the
  PARALLEL mode, every step must approve; `ANY` — the OR mode, the
  first approval satisfies and the remaining siblings become
  optional-skipped) and `approval_step_condition_type` (the trigger
  predicate stub vocabulary S10-06's expression evaluator migrates:
  `ENVIRONMENT_EQUALS`, `FEATURE_FLAG_CONTAINS`).
  `approval_workflow_steps` gains `group_position` (1..m contiguous;
  V28 chains backfill to their own position — every one a sequential
  group of one), `group_mode` (default `ALL`), and the optional
  condition columns (`condition_type`/`condition_key`/
  `condition_value`, shape-checked: a flag condition names its key, a
  conditionless step carries none of the three).
  `release_approvals` copies its rows' group facts at instantiation
  (`group_position`, `group_mode`) — the round freezes the
  definition's shape it ran, the same discipline as the per-step
  position copy. `ALTER TYPE approval_status ADD VALUE 'SKIPPED'` (an
  ANY-group sibling after the group's first approval, or a
  condition-excluded step at instantiation — terminal, no signature,
  always a reason). Expand-only and forward-only; every backfill is a
  no-op on greenfield rows.

- `V30__approval_skipped_shape.sql` (S10-02, issue #41) lands the
  decision-shape CHECK that references `SKIPPED` — the pair exists
  because a new enum value cannot be USED in the transaction that
  adds it and Flyway runs each migration as one transaction (the V14
  enum-precedent's transactional edge). The V28 two-leg check is
  replaced by the three-leg widening: PENDING carries nothing
  decided; SKIPPED carries no signature but must explain itself and
  when; the signed statuses (APPROVED/REJECTED) carry the full
  signature. Every existing row satisfies the new legs unchanged, so
  the drop-and-re-add carries no data.

- `V32__release_rings.sql` (S10-04, issue #43) gives the V24 rings
  reference table its PRD 8.10 behavior columns — `eligibility`
  (jsonb, NULL = unconstrained; the service validates the shape and
  the organization membership of every id), the NAME-ONLY
  `approval_policy` and `distribution_channel` references (the V27
  environments precedent — not foreign keys, so renaming a workflow
  never strands a ring row; an unresolved policy name fails closed at
  resolution), and `promotion_policy` (`MANUAL`/`AUTOMATIC`, default
  MANUAL — AUTOMATIC acts with S10-06) — then materializes FR-017's
  five defaults organization-grain for every EXISTING organization
  (CROSS JOIN ... ON CONFLICT DO NOTHING, the V27 precedent); new
  organizations seed through the API's creation hook
  (`RingProvisioning`). The V24 system rows stay untouched as the
  read-only fallback vocabulary. Expand-only and forward-only.
- `V33__release_promotion.sql` (S10-05, issue #44) lands the promotion
  engine's facts: `release_promotions` — the append-only timeline, one
  row per governed promote ATTEMPT (`COMPLETED` hops and `BLOCKED`
  precheck refusals both record, the `blockers` jsonb carrying the
  complete named list the 409 answered; pre-ring refusals — state,
  split, final, skip — answer alone and record nothing, request-shape
  conflicts are not governed evidence) — and the nullable
  `release_approval_rounds.promotion_ring_id` marking a round the
  PROMOTION engine opened (the ring it gates the release INTO; the
  engine resolves its round by release AND ring, so each hop's
  approvals are its own and the crossing's latest-round rule is
  untouched). Expand-only and forward-only: one new table, one new
  nullable column.

## Status and type columns

Status (and fixed-vocabulary type) columns use **PostgreSQL enum types**
(e.g. `release_status`, `artifact_status`). Enum labels mirror the PRD state
machines where they exist (`releases` per section 17, `artifacts` per
section 18); other tables carry minimal documented sets that later stories
extend.

- To add a value: new migration with `ALTER TYPE <type> ADD VALUE '...'`.
  `V3` did this for `org_status` (`DEACTIVATED`, S1-04); the V1 label
  `DISABLED` stays reserved-unused — the application never assigns it.
  `V14` did it for `version_status` (`REVOKED`, S4-03) alongside the
  lifecycle that emits it.
- Values are never removed or renamed (see rollback policy).
- `org_role` (`ORG_ADMIN`, `ORG_MEMBER` since V3) is the membership-role
  vocabulary; V4 (S1-05) extended it to nine values with the PRD
  section 7 functional roles — the full vocabulary and its
  permission mapping are documented in
  [authorization](authorization.md). `tenant_role` (`TENANT_ADMIN`,
  `TENANT_TESTER`) is the tenant-scoped counterpart, also from V4.
- Exception: `release_gates.gate_type` is `varchar(64)` because gate kinds
  are an open, integration-defined vocabulary.

## Rollback strategy (forward-only policy)

There are no down-migrations and no automated rollback.

- A bad migration is fixed by a **new** migration that corrects the damage.
- Destructive changes (drop column/table, type narrowing) follow
  expand/contract: first migrate to the new shape while keeping the old one
  writable, deploy, then remove the old shape in a **second** migration —
  never in the same step as the introduction.
- Point-in-time recovery of the database is the escape hatch for true
  disasters; Flyway history is rebuildable from source.

- `V34__release_policy_engine.sql` (S10-06, issue #45) lands the
  declarative policy engine's five tables — `release_policy_rules`
  (the org-grain rule store: name unique per organization, the
  shape-checked scope with `application_id`/`environment_id` foreign
  keys and the NAME-ONLY `ring` code, the validated `condition` jsonb,
  the evaluation position), `release_policy_holds` (the HOLD ledger,
  UNIQUE (release, rule, ring), the acknowledge triple shape-checked),
  `release_policy_evaluations` + `release_policy_evaluation_rules`
  (the append-only per-attempt log; the child rows keep LOOSE rule ids
  beside name snapshots so history survives a rule's deletion), and
  `release_policy_settings` (the skip-relaxation knob, one row per
  organization created lazily — absence IS the empty set). Expand-only
  and forward-only: five new tables, nothing dropped or narrowed.

- `V35__feedback_submission.sql` (S11-01, issue #46) EXPANDS the V1
  `feedback` table — its six-value `feedback_type` enum and
  OPEN/RESOLVED/DISMISSED status vocabulary are untouched (triage
  transitions are S11-02; submissions are born OPEN and immutable) —
  with the submission-time context the API attaches SERVER-side: the
  owning `organization_id`, the denormalized
  `application_id`/`version_id` beside V1's release/artifact/tester
  references, the SUBMITTING `submitter_tenant_id` (the release
  target the report came from — the release row's owning-application
  snapshot is a different tenant's, so both dimensions coexist), and
  the REPORTED `device_model`/`os` free text (registered device rows
  link in with S11-03; the `device_id` FK stays null until then). The
  table was empty in every environment, so the new columns arrive
  nullable and tighten to NOT NULL in the same migration (the V24
  add-then-tighten shape with a zero-row backfill). Plus
  `feedback_attachments` (new): one metadata row per uploaded file —
  the extension-resolved `category` (SCREENSHOT/VIDEO/LOG), byte
  size, streamed SHA-256 `checksum`, and the server-derived
  `storage_key` under the tenant-scoped prefix
  `{organizationId}/{submitterTenantId}/feedback/{feedbackId}/
  {attachmentId}` — the bytes live in object storage, never
  PostgreSQL. UNIQUE (feedback, file_name); expand-only, nothing
  dropped.

- `V36__feedback_triage.sql` (S11-02, issue #47) EXPANDS the V1
  `feedback_status` enum with the triage vocabulary — `TRIAGED` (the
  under-review state) and `REJECTED` (the wont-fix answer beside
  RESOLVED); OPEN keeps its name as the born NEW state and DISMISSED
  keeps its historical rows with no new incoming edge (every closed
  state re-opens through TRIAGED; `ALTER TYPE ... ADD VALUE` is legal
  inside the migration transaction because nothing here uses the new
  values). Plus the working columns on `feedback`: `severity` (a new
  `feedback_severity` enum, CRITICAL/MAJOR/MINOR — null until triage
  assigns it; the SUBMITTING tester does not classify), the
  `assignee_user_id` FK to `users` (named
  `fk_feedback_assignee_user_id`), and `resolution_note`
  (varchar(4000), the mandatory explanation a closing crossing
  records). Submissions stay immutable: these columns mutate only
  through the triage actions, never the description, type, or
  attachments. The composite index `(release_id, severity, status)`
  serves the open-critical-bugs metric — the real-time count the
  `open-critical-bugs` gate and the PRD 33 policy rules read — and
  the dashboard rollup alike. Expand-only, nothing dropped.

- `V37__device_management.sql` (S11-03, issue #48) EXPANDS V1's
  `devices` table with the one attribute PRD 8.12 lists that the
  baseline lacked: `status`, a new `device_status` enum
  (`ACTIVE`/`DEACTIVATED`) with `DEFAULT 'ACTIVE'` — the V8
  `tenant_member_status` precedent of a reversible park, because
  device records are history (deactivating a tester keeps them, the
  INV-007 posture; deactivating a device keeps its feedback links).
  Nothing else moves: V1 already carries the full registration
  vocabulary (`platform`/`model`/`os_version`/`identifier`) and the
  tenant-scoped uniqueness `uq_devices_tenant_identifier` (the AC1
  duplicate guard), and `feedback.device_id` — V1's own nullable FK —
  becomes live data this story (a submission may attach one of the
  tester's OWN ACTIVE devices; V35's reported `device_model`/`os`
  free text stays beside it as history, and legacy nulls stay
  valid; the FK keeps its V1 shape — renaming a baseline constraint
  is a rebuild, not an expansion). No new index: the tenant grain is
  served by the unique constraint's leading column, the tester grain
  by `idx_devices_tester_id`, and per-device feedback reads resolve
  by primary key (device-scoped analytics joins are S11-04's to
  index when they land). Expand-only, nothing dropped.
- `V38__release_analytics.sql` (S11-04, issue #49) adds the release
  analytics pipeline's store, all new tables: `analytics_events` (the
  frozen S15-01 SDK event contract's append-only store — installs,
  session summaries with their crash/ANR counts, uninstalls, tester
  verdicts; release/application/tenant/device keyed, shape checks per
  type), and the `metric_hourly` / `metric_daily` rollups (one row per
  application, release, ring, metric, bucket; the nil-uuid sentinel is
  the no-ring dimension — the rings table's own COALESCE discipline
  inverted). The V37-deferred promise lands here:
  `idx_analytics_events_device (device_id, occurred_at)` is the
  device-scoped analytics join index. The migration also BACKFILLS the
  rollups from the pre-existing `DOWNLOAD_ISSUED` audit trail (a no-op
  on every greenfield database; correct on any state), with ring
  attribution resolved from the promotions timeline. Expand-only,
  nothing dropped.
- `V39__event_bus_notifications.sql` (S12-01, issue #51) adds the
  domain-event bus and the notification service, all new tables (see
  [notifications.md](notifications.md) and
  [ADR-0007](adr/ADR-0007-event-bus-transport.md)): `notification_outbox`
  (the transactional outbox — the frozen event name, PRD 51's entity
  context columns with hard foreign keys, the correlation id, the jsonb
  payload, and the relay bookkeeping), `notification_subscriptions`
  (the org-scoped routing table: event names x channel x recipients,
  with the channel-shape checks, the 2048-character webhook URL cap,
  and the optimistic-lock `version` column behind the surface's 409),
  and `notification_deliveries` (the delivery ledger: one row per
  event/subscription/recipient — the at-least-once idempotency unique
  key — with the retry backoff columns and the recipient key sized to
  the URL cap). Three new enum types (`notification_channel`,
  `notification_relay_state`, `notification_delivery_status`).
  Expand-only, nothing dropped.
- `V40__slack_teams_notifiers.sql` (S12-02, issue #52) appends the two
  chat channel values to the existing `notification_channel` enum
  (`SLACK`, `TEAMS` — FR-023's chat channels riding S12-01's webhook
  machinery) and re-admits the subscription recipient-shape check
  WIDER, not weaker: the URL-carrying branch becomes "every channel
  that is not IN_APP or EMAIL", admitting the new values without
  weakening any standing rule (URL + 16+-character secret + the 2048
  cap hold for the whole family). The predicate deliberately names
  only pre-existing enum values so `ALTER TYPE ... ADD VALUE` and the
  constraint re-addition share one Flyway transaction (a new enum
  value may not be USED by a later statement in its own adding
  transaction). The delivery ledger is untouched — its channel column
  is the same enum type and its 2056-character recipient key already
  covers the shorter `slack:`/`teams:` prefixes. No rows exist with
  the new channels at migration time (the values arrive with this
  story's code), so the widened constraint validates trivially.
  Expand-only: enum values appended, one check constraint re-admitted
  wider, nothing dropped or re-typed.
- `V41__inbound_webhook_deliveries.sql` (S12-03, issue #53) adds the
  generic inbound webhook framework's processed-delivery ledger: one
  row per VERIFIED, processed delivery, the idempotency grain
  duplicate deliveries answer from (`uq_inbound_webhook_deliveries_
  provider_delivery` on provider + delivery_id). Rows carry only
  their terminal outcome (`inbound_webhook_outcome`: ACCEPTED or
  IGNORED) and are written inside the processing transaction — a
  crash leaves nothing, so a redelivery processes fresh, and two
  concurrent copies of one delivery both process idempotently with
  the second finisher answering DUPLICATE on the unique key.
  `provider` is a plain varchar, deliberately not an enum: the
  framework's provider set is an open vocabulary (S12-04's
  Jenkins/GitLab/App Store/Google Play keys, then E13's connectors),
  and open vocabularies are row values, not schema migrations. An
  index on `received_at` serves the operator's consumed-events reads.
  New types and tables only; nothing existing touched.
- `V42__store_status_events.sql` (S12-04, issue #54) adds the store
  receivers' normalized internal events: one row per VERIFIED store
  notification mapped (`appstore` / `google-play`), the thin
  state-sync foundation E13's connectors consume — the provider, its
  own event id, the stable event-type vocabulary, and the store-side
  identifiers (bundle id, purchase token, subscription/sku reference;
  each NULLable because the two providers carry different facts —
  Google RTDN envelopes carry no package name). The mapping's
  idempotency grain is its OWN unique key
  (`uq_store_status_events_provider_event`), independent of the V41
  transport ledger's: a redelivery under a fresh transport id
  converges on the one row. The migration also serves the provenance
  backfill's commit-matched lookup with a partial expression index
  (`idx_artifacts_provenance_git_commit` on
  `(provenance ->> 'gitCommit')`) — the #53 review's deferred
  at-scale finding, folded here because V42 was already touching the
  receiver family's storage. New tables and indexes only; nothing
  existing touched.

(V43–V54's prose entries are documentation debt from the S13–S15
deliveries; the Layout inventory above lists every file. V55–V57 resume
the per-migration record here.)

- `V55__ota_bundles.sql` (S16-02, issue #164) adds the OTA bundle
  authoring foundation ([ADR-0010](adr/ADR-0010-ota-updates.md)):
  `applications.ota_enabled` (NOT NULL DEFAULT false — every existing
  app opts out; new apps enable it), `ota_bundles` (the authored generic
  bundle: its OWN `versions` row held exclusively —
  `uq_ota_bundles_version`, D2; bytes served as an ordinary `artifacts`
  row per D5; the manifest sha256/size lands as a coherent pair at
  finalize and the Ed25519 signature/key_id as a pair at sign, D8;
  status vocabulary UPLOADING/VALIDATING/SIGNED/RELEASED/REVOKED;
  `release_id` NULL until publish), and `ota_app_tokens` (the hashed
  `sdc1.` device-identifier registry: SHA-256 hex only, write-only the
  V49 `code_hash` doctrine; the unique hash IS the identity; revocable
  by row through `revoked_at`). `platform_tag` is a shape-checked
  varchar(32), deliberately not an enum — the generic-bundle platform
  set is an open vocabulary, the V41 `provider` doctrine. Expand-only,
  nothing dropped.
- `V56__ota_rollout.sql` (S16-02, issue #164) adds the OTA device
  rollout and alert machinery, mirroring V48's proven plan/stage/event
  shape (ADR-0010 D6/D10): `ota_rollout_plans` (ONE per release — the
  `uq_` mirror; mode AUTOMATIC/CHECKPOINT; plan status
  RUNNING/HALTED/PAUSED/COMPLETE; `plan_salt` for the deterministic
  on-the-fly device bucketing, `hash(device_id, plan_salt) mod 10000`
  below the stage's cumulative basis points; the optimistic-lock
  `version bigint`), `ota_rollout_stages` (position + basis points
  1..10000 + status PENDING/REACHED — halting is plan-level per D10,
  so stages carry no ROLLED_BACK), `ota_rollout_events` (append-only:
  COMMENCED/HALTED/RESUMED/ADVANCED/COMPLETED — COMPLETED deliberately
  mirrors V48's timeline vocabulary), and `ota_alert_rules` +
  `ota_alert_firings` (the D10 alert definitions — metric, comparator
  GT/GTE/LT/LTE, threshold, window, severity INFO/WARNING/CRITICAL —
  and the resolvable firings they raise; a partial unresolved index
  serves the console's active-alert badge). Expand-only, nothing
  dropped.
- `V57__ota_telemetry.sql` (S16-02, issue #164) adds the OTA telemetry
  surface (ADR-0010 D9): six `analytics_event_type` values appended
  (server-side `OTA_CHECK` plus the five device lifecycle events), and
  BOTH V38 CHECKs re-admitted WIDER, not weaker — `result_shape` keeps
  its four pre-existing branches verbatim and admits every type beyond
  the pre-existing four as the explicit open remainder (result NULL or
  a trimmed 1..32 value); the predicates name only pre-existing enum
  values so the `ADD VALUE`s and the constraint re-additions share one
  Flyway transaction, the V40 discipline. A NEW
  `ck_analytics_events_ota_shape` closes the coherence: the
  pre-existing family never carries bundle attribution, the OTA family
  always carries it, and `ota_payload` is a jsonb object or NULL.
  `result` widens varchar(16) to varchar(32) in place (the OTA failure
  reasons are 17 characters); `analytics_events` gains nullable
  `ota_bundle_id`/`ota_payload` with the
  `(ota_bundle_id, event_type, occurred_at)` funnel index; and
  `metric_hourly`/`metric_daily` gain nullable `ota_bundle_id` beside
  their UNTOUCHED five-column primary-key grain (OTA metrics are
  distinct metric names), with partial bundle-window indexes.
  Expand-only: enum values appended, two checks re-admitted wider, one
  new check, nullable columns, one in-place length widen; nothing
  dropped.
- `V58__ota_signing_keys.sql` (S16-03, issue #165) adds the app-scoped
  Ed25519 signing-key registry that [ADR-0010](adr/ADR-0010-ota-updates.md)
  D8 froze and V55 deferred: `ota_signing_keys` keeps the PUBLIC half and
  the secret-store port's opaque `secret_reference` only — the private
  key lives behind [ADR-0008](adr/ADR-0008-credential-secret-storage.md)'s
  store (`local-envelope://` self-hosted, GCP Secret Manager in
  production) and exists in JVM memory only for one signing flight. One
  ACTIVE key per application (the partial unique index
  `uq_ota_signing_keys_active` on `application_id WHERE revoked_at IS
  NULL`); rotation is generational — a new row under a new `key_id` with
  the old row's `revoked_at` stamped in the same transaction, so bundles
  signed under the old key keep verifying against the public key devices
  embedded. `key_id` (16 hex of the raw public key's SHA-256) is globally
  unique (`uq_ota_signing_keys_key`) — it names keys on device
  manifests — and `algorithm` is CHECK-pinned to Ed25519 (D8's frozen
  choice). `tenant_id` mirrors the owning application's dimension (NULL
  for organization-shared apps, the applications row's own shape).
  Expand-only: one new table, its indexes, and its checks; nothing
  existing touched.
- `V59__ota_release_integration.sql` (S16-05, issue #167) adds the OTA
  release linkage's schema floor ([ADR-0010](adr/ADR-0010-ota-updates.md)
  workstream 3): `release_targets.forced`/`min_app_version` — the D7
  per-target overrides of the bundle's per-bundle defaults, both NULL
  by default and NULL meaning "the bundle's own value applies" (the
  tri-state is the point: a stored FALSE is a real override, distinct
  from no override at all; `min_app_version` is varchar(100), the
  create surface's cap) — and `uq_ota_bundles_release`, the partial
  unique index on `ota_bundles.release_id WHERE release_id IS NOT
  NULL`: exactly one bundle per OTA release at the floor, the
  release-side twin of V55's `uq_ota_bundles_version`. The partial
  predicate is the lifecycle: `release_id` is NULL until the bundle
  publishes and after a rollback returns it to the SIGNED pool, so
  only live RELEASED links are unique. Expand-only: two nullable
  columns and one partial index; nothing existing touched.
- `V60__ota_rollout_paused_event.sql` (S16-06, issue #168) is the two
  gap-fixes the rollout control service surfaced over V56's frozen
  rollout triple ([ADR-0010](adr/ADR-0010-ota-updates.md) D6/D10):
  first, `PAUSED` joins `ota_rollout_events`' action vocabulary — V56
  wrote "every operator action lands here" over the timeline but
  pinned the action CHECK without the pause, so the constraint REWRITE
  (the V57 precedent of rewriting V38's closed CHECK disjunctions)
  widens `ck_ota_rollout_events_action` by exactly that one value, to
  the six control acts (`COMMENCED`, `HALTED`, `PAUSED`, `RESUMED`,
  `ADVANCED`, `COMPLETED`). Second, `ota_rollout_plans` gains the
  nullable `tenant_id` uuid the org-owned doctrine presupposes but V56
  omitted — the tenant-isolation seam composes its organization-scope
  predicate on mapped attributes, so an org-grain row without the
  column cannot ride the seam — pinned org-grain by the new
  `ck_ota_rollout_plans_org_grain` (`tenant_id IS NULL`): the plan
  serves the release's whole device population, the owning
  organization's grain and never one tenant's, exactly the shape
  V58's `ota_signing_keys` and every other org-scoped row carries.
  Expand-only: one CHECK widened by one value, one nullable column,
  one new check; nothing dropped or re-typed, and no existing action
  value leaves the vocabulary.
- `V61__ota_alert_rules_org_grain.sql` (S16-11, issue #173) is the one
  gap-fix the alert build surfaced over V56's frozen alert pair:
  `ota_alert_rules` is an org-grain row (a rule watches an
  application's whole OTA funnel — the device population's grain, the
  rollout plans' own doctrine) but V56 omitted the nullable
  `tenant_id` the org-owned doctrine presupposes, the exact gap V60
  fixed for `ota_rollout_plans`. The seam's organization-scope
  predicate composes on mapped attributes, so the rule row cannot ride
  it without the column; `ck_ota_alert_rules_org_grain`
  (`tenant_id IS NULL`) pins the grain. `ota_alert_firings` needs no
  such column by design — a firing is addressed only through its rule
  (the `ota_rollout_events` doctrine). Expand-only: one nullable
  column and one new check on a this-milestone table.

## Verifying migrations

- `mvn verify` runs `MigrationIntegrationTest` and `DevSeedIntegrationTest`
  against a clean Testcontainers PostgreSQL: baseline applies cleanly,
  a second migrate run applies nothing, tenant/org indexes exist, enum
  types match the documented values, and the seed fixture is present when
  the seed location is enabled.
- Locally, `docker compose up` runs the API with the `dev` profile: Flyway
  applies schema plus seed on startup and the health endpoint reports UP.
