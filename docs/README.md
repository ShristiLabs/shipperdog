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
  denials, role-assignment endpoints and guards (S1-05).
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
- [Admin console](frontend.md) — the React/MUI console (S2-04, S3-04):
  stack and structure, the shared API client (problem+json, correlation
  ids, cursor pagination), token storage and silent refresh, role-aware
  navigation probes, screens including the application console, CI
  gate, and local development.
- [Audit logging](audit.md) — append-only audit trail, transactional
  persistence, redaction, correlation ids, and the admin query API
  (S1-06).
- [Testers](testers.md) — tester onboarding and invitations (S6-01):
  organization-scoped tester records, the reused invitation machinery
  with the 7-day single-use token, accept flow through registration,
  the INVITED/ACTIVE/SUSPENDED lifecycle with login/refresh/PAT
  suspension, email delivery through the notification seam, and
  TESTER_* configuration.
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

Start with the top-level README for local setup and CI.
