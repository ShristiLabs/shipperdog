-- V11__tenant_application_authorization.sql
-- Shared-application tenant authorization mapping (issue #15, S3-03).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds a type and a
--     table; nothing is dropped or altered destructively.
--   * Constraint names use the explicit ck_/uq_ prefixes.
--
-- PRD 15's distribution model: an organization-shared application
-- (ownership_scope = 'ORGANIZATION', S3-02) reaches exactly the tenants
-- the organization authorizes — "Banking 7.12.0 -> Acme YES, Contoso
-- YES, Delta NO". The mapping is explicit grant rows (the issue's
-- technical notes): one row per application x tenant pair, carrying a
-- status and the grant/revocation timestamps. Revocation is a status
-- flip, never a deletion — the row keeps the pair's current state
-- (INV-007 philosophy), and the immutable audit trail keeps the history
-- of every grant and revocation with its actor and reason.
--
-- One row per pair is the invariant: uq_tenant_application_authorizations
-- pins it and is the final authority on concurrent grants (the loser of
-- the race re-reads the winner's row, mirroring the tenant-membership
-- precedent). A re-grant after revocation flips the same row back to
-- ACTIVE and re-stamps granted_by/granted_at.

-- ---------------------------------------------------------------------------
-- Vocabulary
-- ---------------------------------------------------------------------------

-- The mapping's own status vocabulary (the V8 precedent: each surface
-- gets its own enum so they evolve independently). ACTIVE rows authorize
-- visibility; REVOKED rows are historical state, invisible to every
-- resolution query.
CREATE TYPE application_authorization_status AS ENUM ('ACTIVE', 'REVOKED');

-- ---------------------------------------------------------------------------
-- The mapping table
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_application_authorizations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL REFERENCES applications (id),
    tenant_id      uuid NOT NULL REFERENCES tenants (id),
    status         application_authorization_status NOT NULL DEFAULT 'ACTIVE',
    granted_by     uuid NOT NULL REFERENCES users (id),
    granted_at     timestamptz NOT NULL DEFAULT now(),
    revoked_by     uuid REFERENCES users (id),
    revoked_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_application_authorizations_pair
        UNIQUE (application_id, tenant_id),
    CONSTRAINT ck_tenant_application_authorizations_revocation
        CHECK ((status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);

-- Per-tenant lookups (the effective-visibility subquery and revocation
-- reads); application_id lookups are served by the prefix of the unique
-- constraint. The owning organization is not denormalized: it is derived
-- through the application row, which stays the single source of truth.
CREATE INDEX idx_tenant_application_authorizations_tenant
    ON tenant_application_authorizations (tenant_id);
