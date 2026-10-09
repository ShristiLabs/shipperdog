# ShipperDog Developer Documentation

Developer-facing documentation lives here. As stories land, this directory
gains: runbooks and deeper operational guides.

Current contents:

- [API conventions](api-conventions.md) — the single error envelope
  (RFC 7807 problem+json + stable codes), opaque concealed-404 denials,
  cursor pagination, Idempotency-Key contract, payload serialization
  rules, and the generated OpenAPI contract with its CI breaking-change
  gate (S1-08).
- [Error code registry](error-codes.md) — every stable public error code,
  its HTTP status and detail text (S1-08).
- [Database migrations](database-migrations.md) — Flyway conventions, enum
  policy, seed fixture, and the forward-only rollback strategy (S1-02).
- [Authentication](authentication.md) — identity module endpoints, token
  semantics (rotation, family revocation, PATs), lockout and reset
  behavior, AUTH_* configuration (S1-03).
- [Authorization](authorization.md) — RBAC permission catalog and role
  matrix, the single enforcement point, deny-by-default and opaque
  denials, role-assignment endpoints and guards (S1-05); the layered
  authorization engine of PRD 11/12 — seven ordered layers, the
  entitlement seam S7-02 plugs into, audited decisions, and the
  admin-only decision inspection API (S7-01).
- [Organizations](organizations.md) — organization lifecycle, settings,
  and membership surface (S1-04, extended by S1-05).
- [Tenants](tenants.md) — tenant lifecycle under an organization,
  identifier uniqueness, deactivation semantics, tenant isolation
  semantics, member management with the suspension switch, and the
  applications surface (tenant effective-visibility listing, registry,
  ownership scopes, shared-app authorization, org-wide administration
  listing, versions under an application with their lifecycle and
  permanent history, and the seam-served artifact uploads with their
  object-storage layout and background extraction) (S2-01, S2-02,
  S2-03, S3-01 through S3-04, S4-01 through S4-03, S5-01, S5-02).
- [Artifacts](artifacts.md) — the artifact surface end to end: upload
  routes and object storage (S5-01), the read route with its
  asynchronous extraction tier and stored failure codes, the parser
  port with its iOS (S5-02) and Android APK/AAB (S5-03) implementations,
  build-provenance capture and its frozen CI-integration field names
  (S5-04), the validation pipeline and artifact state machine with
  its per-stage failure codes and the revalidation surface (S5-05),
  ARTIFACT_* environment configuration, and the
  duplicate/immutability rules.
- [Releases](releases.md) — the release model, creation, and targets
  (S9-01): the DRAFT distribution event with per-platform READY
  artifacts, target tenants + tester groups, and the starting ring
  (the S10-04 ring data model pulled forward as seeded reference
  data); the 422 per-tenant target validation; the quotable R-number
  ids; the filtered listing and detail; INV-004's artifact-set freeze
  and the ownership-scope precondition the landing binds. Since S9-02:
  the PRD 17 lifecycle — guarded transitions with the compare-and-set
  race discipline, the mandatory failure/rollback reasons, the
  append-only actor-carrying history timeline, and the ROLLED_BACK
  direct-channel download cutoff.
- [Environments](environments.md) — the environment surface (S9-04):
  the organization-grain deployment contexts (seeded defaults plus
  customs) with their two-value lifecycle, the versioned
  configuration snapshots with retrieval by version, the tenant
  assignment allow-list release creation validates targets against
  (deny-by-default), the reference-only credential bindings with the
  resolution probe (secret references, never material — PRD 25), and
  the release-creation and manifest-freeze integration (the
  environment code and the RESOLVED configuration values freeze at
  APPROVED).
- [Admin console](frontend.md) — the React/MUI console (S2-04, S3-04):
  stack and structure, the shared API client (problem+json, correlation
  ids, cursor pagination), token storage and silent refresh, role-aware
  navigation probes, screens including the application console, CI
  gate, and local development.
- [Tester portal](portal.md) — the tester-facing app in `portal/`
  (S8-01): a separate React/MUI bundle on :5174 with tester login,
  the invitation-accept entry, silent refresh with the narrowed
  /auth/me retry, session isolation from the console, and the honest
  empty home.
- [Audit logging](audit.md) — append-only audit trail, transactional
  persistence, redaction, correlation ids, and the admin query API
  (S1-06).
- [Testers](testers.md) — tester onboarding and invitations (S6-01)
  and lifecycle revocation (S6-04): organization-scoped tester
  records, the reused invitation machinery with the 7-day single-use
  token, accept flow through registration, the
  INVITED/ACTIVE/SUSPENDED/DISABLED lifecycle with login/refresh/PAT
  enforcement, deactivation with mandatory reason, membership cascade,
  session kill and the revocation latency SLA, email delivery through
  the notification seam, and TESTER_* configuration.
- [Tester groups](tester-groups.md) — tenant-scoped tester collections
  (S6-02): group CRUD on the isolation seam, idempotent membership
  add/remove over the composite-PK junction, default-group seeding on
  tenant creation, and the testers-per-group / groups-per-tester
  listings.
- [Tenant-tester memberships](tenant-tester-memberships.md) — the
  portal-access join between testers and tenants (S6-03): link /
  suspend / history-preserving removal over the seam-joined composite
  key, the two-switches effective-access rule, both listings, and the
  group-membership precondition.
- [Devices](devices.md) — the tester-device registry of one tenant
  (S11-03): manual registration with the tenant-scoped identifier
  uniqueness, the reversible park (rows are never deleted), the
  admin-only provisioning CSV export, the feedback device link, and
  the console surface.
- [Release analytics](analytics.md) — the FR-029 metrics dictionary,
  the facts-to-rollups-to-reads pipeline, the frozen S15-01 SDK events
  contract, the rollup job and its backfill, and the gate/policy
  metric seams the pipeline registers on (S11-04)
- [Dashboards](dashboards.md) — the console's landing surfaces (S11-05):
  the organization dashboard (counts, per-application release health
  with the BLOCKED/TESTING/HEALTHY derivation, role-filtered pending
  actions) and the tenant dashboard, the two read APIs behind them,
  and the series-folding rule (counts sum, rates never do) every
  chart consumer of the analytics reads follows
- [Notifications](notifications.md) — the domain-event bus and the
  notification service (S12-01): the transactional outbox and the
  at-least-once relay behind the DomainEventBus port (ADR-0007), the
  frozen event-name vocabulary and its mapping table, the org-scoped
  subscription CRUD (events x channels x recipients), the in-app
  inbox, signed webhooks with the receiver's HMAC verification recipe,
  and the delivery ledger with its retry policy
- [Observability](observability.md) — tracing/metrics/logs baseline,
  correlation model, `shipperdog_*` metric namespace, Prometheus +
  Grafana local stack, and the management-port security posture
  (S1-07).
- [CLI](cli.md) — the Go developer CLI (S5-06): command surface,
  credentials store (PAT in `~/.shipperdog/credentials`, 0600), the
  endpoint contract test pinning every URL to the OpenAPI baseline,
  exit-code contract for CI, and the cli-ci gate with its cross-compile
  matrix.
- [ADR-0001: Flyway with plain SQL migrations](adr/ADR-0001-flyway-schema-migrations.md)
- [ADR-0002: Predicate-injection repository seam for tenant isolation](adr/ADR-0002-tenant-isolation-data-seam.md)
- [ADR-0003: Artifact object storage behind the ArtifactStore port](adr/ADR-0003-artifact-object-storage.md)
- [ADR-0004: CLI implemented in Go with a contract-tested thin client](adr/ADR-0004-cli-toolchain.md)
- [ADR-0005: M1 deploys to Cloud Run over a private VPC data tier](adr/ADR-0005-cloud-run-deployment.md)
- [ADR-0006: Portal and console sessions stay in localStorage for M1, hardened by CSP](adr/ADR-0006-portal-session-token-storage.md)
- [ADR-0007: The domain-event bus ships in-process over a transactional outbox; the broker is a swap behind one port](adr/ADR-0007-event-bus-transport.md)
- [Deployment runbook (Cloud Run, M1)](deployment.md) — terraform stack,
  deploy workflow, smoke test, rollback, observability posture (S8-05).

Start with the top-level README for local setup and CI.
