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
  (S1-04). Later integration tests rely on these identifiers.

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
  descent reads the row and therefore its status; revisit with
  S7-01/S7-03 when the cache strategy lands.

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

## Verifying migrations

- `mvn verify` runs `MigrationIntegrationTest` and `DevSeedIntegrationTest`
  against a clean Testcontainers PostgreSQL: baseline applies cleanly,
  a second migrate run applies nothing, tenant/org indexes exist, enum
  types match the documented values, and the seed fixture is present when
  the seed location is enabled.
- Locally, `docker compose up` runs the API with the `dev` profile: Flyway
  applies schema plus seed on startup and the health endpoint reports UP.
