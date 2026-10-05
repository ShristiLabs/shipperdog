-- V13__version_tags.sql
-- Version tags (issue #18, S4-02, FR-009).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table,
--     nothing dropped or altered destructively.
--   * Pure junction tables use composite primary keys over their foreign
--     keys (the V1 tenant_tester_memberships / version_access precedent).
--   * Constraint names use the explicit fk_/ck_/idx_ prefixes.
--
-- The table is exactly the story's technical note — "(version_id, tag);
-- no tag metadata in M1" — with the platform's always-present audit
-- columns:
--
--   * The composite PRIMARY KEY (version_id, tag) is the duplicate
--     authority: a tag is present at most once per version, and the
--     service's insert-if-absent composes with it race-free
--     (ON CONFLICT DO NOTHING, the idempotency-claim precedent).
--   * ck_version_tags_tag_shape pins the public vocabulary at the
--     database: case-normalized UPPER (only A-Z survive the pattern),
--     one to fifty characters, starting with a letter or digit and
--     continuing with letters, digits, underscore, or hyphen. The FR-009
--     reserved words (QA, UAT, BETA, PILOT, STABLE, PRODUCTION, LEGACY)
--     all fit; arbitrary custom tags fit just as well — the vocabulary
--     is suggested in the API's OpenAPI description, never enforced as
--     the only options (issue #18 AC3).
--   * The tag rows carry no owning-dimension snapshot: they are children
--     of a seam-scoped parent (the V1 artifacts precedent — ADR-0002
--     sixth amendment notes artifacts anchor to versions the same way).
--     Every data path reaches tags through a version row the seam
--     already served inside the request's bound scope, so isolation is
--     enforced at the version the tag hangs off, never re-derived here.
--   * Timestamps are database-defaulted on insert; rows are never
--     updated (a replace-set deletes and inserts), which is why no
--     application-side lifecycle hooks maintain them.

CREATE TABLE version_tags (
    version_id uuid NOT NULL,
    tag        varchar(50) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (version_id, tag),
    CONSTRAINT fk_version_tags_version FOREIGN KEY (version_id) REFERENCES versions (id),
    CONSTRAINT ck_version_tags_tag_shape CHECK (tag ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$')
);

-- Tag-only lookups: the listing filter's subquery (versions of one
-- application WHERE tag = :tag) and, from E7 (S7-02), the organization-
-- wide "every version tagged BETA" grant-resolution questions. The
-- composite primary key's leading column already serves every
-- per-version access; this index serves the ones that start from the
-- tag.
CREATE INDEX idx_version_tags_tag ON version_tags (tag);
