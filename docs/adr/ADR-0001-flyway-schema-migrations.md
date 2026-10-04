# ADR-0001: Flyway with plain SQL migrations for schema management

- **Status:** Accepted
- **Date:** 2026-10-04
- **Issue:** #2 (S1-02 — Database foundation & migrations)

## Context

Issue #2 requires a strict migration discipline for the PostgreSQL schema so
all M1 epics build on versioned tables instead of ad-hoc DDL. The issue
shortlisted Flyway and Liquibase and required the choice to be recorded.

## Decision

Use **Flyway** with **plain SQL migrations** (`db/migration/V<n>__*.sql`).

Reasons:

1. SQL migrations are exactly what a DBA would run; no XML/YAML/DSL layer to
   review, diff, or learn.
2. Spring Boot auto-configures Flyway on the classpath, including startup
   execution against the configured datasource — no bespoke runner code.
3. Versioned, ordered, checksum-verified history in `flyway_schema_history`
   gives out-of-the-box drift detection (`validate`) and clean-database
   reproducibility, which CI already exercises through Testcontainers.
4. Liquibase's strengths (database-agnostic abstractions, generated changelogs)
   buy nothing here: the platform is PostgreSQL-only by decision, and the
   schema is hand-designed.

## Consequences

- Migrations are PostgreSQL-dialect SQL; portability is explicitly not a goal.
- The policy is **forward-only**: migrations are never edited after landing,
   never reordered, and never rolled back. See the rollback strategy in
   `docs/database-migrations.md`.
- A dev-only seed fixture lives in a separate Flyway location
   (`db/seed`) activated by the `dev` profile, so production and clean
   CI databases never see seed data.
- `spring.jpa.hibernate.ddl-auto` stays `validate`; Flyway is the sole owner
  of schema.
