package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_manifests} table (V26, issue #57, S9-03, PRD
 * 16): the frozen reproducibility document of one release — the
 * complete record of WHAT the release ships, deep-copied at the moment
 * the state machine crosses into APPROVED and never touched again. A
 * manifest answers PRD 16's compliance question ("exactly what did
 * Acme receive on October 4?") from its own stored bytes, decades
 * after every source row it once read has become history.
 *
 * <p>Immutability is structural (INV-003/004 discipline): the row has
 * no setters at all, the table's {@code uq_release_manifests_release}
 * allows exactly one manifest per release ever, the repository
 * declares insert and read operations only, and the forward-only state
 * machine cannot re-enter APPROVED — a second freeze is doubly
 * impossible. The frozen document is never mutated to match a drifted
 * world: reads re-canonicalize the stored content and compare its
 * SHA-256 against the stored digest, failing loudly on any mismatch
 * instead of papering over it.
 *
 * <p>A pure child of the seam-served release (the V25
 * {@code release_status_history} doctrine, eighth amendment standing):
 * no owning-dimension snapshot, served by no seam repository — every
 * caller resolves the release through the scoped parent first, so
 * isolation is enforced at the parent and never re-derived here.
 * {@code content} holds the canonical JSON document; the digest is
 * over the application's canonical serialization (fixed field order,
 * ISO-8601 instants, nulls included), not over PostgreSQL's
 * key-reordered jsonb rendering.
 */
@Entity
@Table(name = "release_manifests")
public class ReleaseManifestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    /** The canonical JSON document of PRD 16's manifest, frozen at the APPROVED crossing. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String content;

    /** SHA-256 hex over the canonical serialization of the content document (the artifact row's fixed-width char(64) shape). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    private Instant frozenAt;

    /** Who moved the release into APPROVED (a bare uuid; the audit event keeps the full who). */
    private UUID frozenBy;

    protected ReleaseManifestEntity() {}

    /**
     * One frozen manifest: the canonical document, its content hash,
     * and the APPROVED crossing's actor and moment (the transition
     * event's {@code occurredAt}, so the manifest, the timeline row,
     * and the audit event tell one story).
     */
    public ReleaseManifestEntity(UUID releaseId, String content, String sha256, Instant frozenAt, UUID frozenBy) {
        this.releaseId = releaseId;
        this.content = content;
        this.sha256 = sha256;
        this.frozenAt = frozenAt;
        this.frozenBy = frozenBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public String getContent() {
        return content;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public UUID getFrozenBy() {
        return frozenBy;
    }
}
