package com.shipperdog.api.version.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code version_tags} (V13): the per-version tag join rows of
 * S4-02 (issue #18, FR-009) — a version's tags are the targeting
 * primitive later consumed by tag-based authorization grants (S7-02)
 * and policy rules. A pure junction with no tag metadata in M1: the
 * composite key {@code (versionId, tag)} is the whole row's identity,
 * the V1 junction-table precedent (issue #18's technical note).
 *
 * <p>The entity deliberately implements neither {@code TenantScope} nor
 * {@code OrganizationScope}: tag rows are children of a seam-scoped
 * parent, the same standing the V1 {@code artifacts} table has —
 * ADR-0002's sixth amendment notes children anchor to versions, which
 * carry the owning-dimension snapshot since V12. Every data path
 * reaches tags through a {@link VersionEntity} the seam already served
 * inside the request's bound scope (the service resolves the version
 * scoped first, then reads or writes only that version's tag rows), so
 * isolation is enforced at the version the tag hangs off and is never
 * re-derived here. The repository therefore needs no seam or
 * cross-tenant marker: it is not a tenant-scoped surface, it is the
 * private child store of one.
 *
 * <p>Rows are written only by the repository's declared operations —
 * the insert-if-absent and the targeted delete — so the entity has no
 * lifecycle hooks to maintain: timestamps are database-defaulted on
 * insert (V13) and rows are never updated (a replace-set deletes and
 * re-inserts).
 */
@Entity
@Table(name = "version_tags")
@IdClass(VersionTagId.class)
public class VersionTagEntity {

    @Id
    private UUID versionId;

    /** The case-normalized UPPER tag value (V13 {@code ck_version_tags_tag_shape}). */
    @Id
    private String tag;

    private Instant createdAt;

    private Instant updatedAt;

    protected VersionTagEntity() {}

    public UUID getVersionId() {
        return versionId;
    }

    public String getTag() {
        return tag;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
