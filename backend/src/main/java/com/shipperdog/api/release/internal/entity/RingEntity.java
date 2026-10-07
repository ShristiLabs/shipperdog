package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code rings} reference table (V24, issue #55): the ring data
 * model S10-04 owns behavior for, pulled forward into this story's schema
 * per its sequencing note. FR-017's five system defaults are seeded by the
 * migration with {@code organization_id} NULL (visible to every
 * organization); an organization's custom rings arrive with E10, which is
 * also when eligibility rules, approval policies, and quality gates
 * (PRD 8.10) become more than columns-to-come. A ring is reference data,
 * not tenant-scoped data: it carries an optional organization dimension
 * and no tenant dimension, so it uses a plain repository with explicit
 * organization-scoped queries — the tenth amendment's organization-grain
 * pattern — and joins no seam.
 */
@Entity
@Table(name = "rings")
public class RingEntity {

    @Id
    private UUID id;

    /** NULL for a system default ring; the owning organization for a custom ring (E10). */
    private UUID organizationId;

    /** The stable, human-facing code: {@code tenant-pilot} (lowercase kebab). */
    private String code;

    /** The display name: {@code Tenant Pilot}. */
    private String name;

    /** The progressive-distribution order (PRD 8.10: Ring 0 Engineering ... Ring 4 Production). */
    private int position;

    private Instant createdAt;

    private Instant updatedAt;

    protected RingEntity() {}

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
