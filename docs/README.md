# ShipperDog Developer Documentation

Developer-facing documentation lives here. As stories land, this directory
gains: API conventions (S1-08), observability setup (S1-07), and runbooks.

Current contents:

- [Database migrations](database-migrations.md) — Flyway conventions, enum
  policy, seed fixture, and the forward-only rollback strategy (S1-02).
- [Authentication](authentication.md) — identity module endpoints, token
  semantics (rotation, family revocation, PATs), lockout and reset
  behavior, AUTH_* configuration (S1-03).
- [ADR-0001: Flyway with plain SQL migrations](adr/ADR-0001-flyway-schema-migrations.md)

Start with the top-level README for local setup and CI.
