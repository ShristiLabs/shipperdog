-- V26__release_manifest.sql
-- Release manifest schema for ShipperDog (issue #57, S9-03, FR-015/FR-024,
-- PRD 16).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table,
--     nothing dropped, nothing altered.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--
-- release_manifests (new): the PRD 16 reproducibility artifact — the
-- complete, immutable record of WHAT a release ships, frozen at the
-- moment the state machine crosses into APPROVED (S9-02's event hook;
-- the freeze joins the transition's transaction, so no release is ever
-- APPROVED without its manifest). The row is a frozen document, not an
-- assembled-at-read view: the release's artifacts (sha256, build, size,
-- platform, filename), targets, ring, environment, rollout, required
-- approvals, and configuration snapshot are deep-copied VALUES at freeze
-- time — never lazy references to rows that might later move.
--
--   * Immutability authority: uq_release_manifests_release allows
--     exactly one manifest per release, ever. There is no update path
--     in the code (the repository declares insert + read only), and the
--     state machine's forward-only graph cannot re-enter APPROVED, so
--     a second freeze is doubly impossible. INV-003/004 discipline: a
--     manifest is never mutated to match a drifted world — reads
--     verify the stored content hash and fail loudly instead.
--   * Plain child of the seam-served release (the eighth amendment,
--     the V25 release_status_history doctrine): no owning-dimension
--     snapshot, served by no seam repository — every caller resolves
--     the release through the scoped parent first, so isolation is
--     enforced at the parent and never re-derived here.
--   * content is jsonb for queryability, but the stored sha256 is
--     computed over the application's canonical serialization of the
--     document (fixed field order, ISO-8601 instants, nulls included)
--     — PostgreSQL's jsonb re-orders keys on storage, so byte-stability
--     lives in the code, and read-time verification re-canonicalizes
--     the parsed document before comparing hashes.
--   * frozen_by is a bare uuid (the V25 actor_id precedent): the audit
--     event RELEASE_MANIFEST_FROZEN keeps the full who.
--
-- No backfill, by design: a manifest is a transition-time act. Any
-- release already past APPROVED when this story lands predates the
-- manifest surface and stays manifest-less (honest absence, never a
-- retro-frozen document with a falsified freeze time). No such rows
-- exist on any current database — the release module gained its first
-- writer in S9-01 and none of its transitions predate this migration.

CREATE TABLE release_manifests (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id uuid NOT NULL,
    content    jsonb NOT NULL,
    sha256     char(64) NOT NULL,
    frozen_at  timestamptz NOT NULL,
    frozen_by  uuid NOT NULL,
    CONSTRAINT uq_release_manifests_release UNIQUE (release_id),
    CONSTRAINT fk_release_manifests_release FOREIGN KEY (release_id) REFERENCES releases (id),
    CONSTRAINT ck_release_manifests_sha256_shape CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
