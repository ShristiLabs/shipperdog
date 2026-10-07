package com.shipperdog.api.environment.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code environments} table (V1 baseline + V27's code,
 * position, and the two name-only bindings): a deployment context with
 * its own configuration — PRD 8.11's Development/QA/UAT/Pilot/
 * Production and whatever custom environments an organization adds
 * (FR-025). An environment describes WHAT the application points to; a
 * ring describes WHO receives — orthogonal axes, separate tables (the
 * issue's technical note).
 *
 * <p>Organization-grain (the issue's scope decision, mirroring V1's
 * {@code organization_id NOT NULL}): environments belong to the
 * organization, not to a tenant — the same dimension the testers module
 * holds and the rings reference table deliberately lacks. The entity
 * implements no scope interface and joins no isolation seam: like rings
 * and testers, it is organization-grain data served by a plain
 * repository with explicit organization-scoped queries (ADR-0002's
 * tenth amendment), because there is no tenant dimension for the seam's
 * predicate to filter by and no environment route is tenant-addressed;
 * the evaluator performs the environment-to-organization hop through
 * the module's public port instead.
 *
 * <p>The lifecycle is V1's two-value {@code environment_status}:
 * ACTIVE is the working state, DISABLED is the park (a disabled
 * environment accepts no mutations and may not be named by a new
 * release). There is no deletion — the platform never deletes
 * distribution history (INV-006/007 posture); releases reference
 * environments and the foreign key would refuse anyway.
 */
@Entity
@Table(name = "environments")
public class EnvironmentEntity {

    /** V1's two-value lifecycle: ACTIVE works, DISABLED parks (reversible). */
    public enum Status {
        ACTIVE,
        DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** The stable, human-facing code: {@code production} (lowercase kebab, the rings shape). */
    private String code;

    /** The display name: {@code Production}. */
    private String name;

    /** The Dev -> Production progression order (PRD 8.11); custom environments take any position. */
    private int position;

    /** Name-only binding: the distribution channel (E13 owns channels; the name is the bind). */
    private String distributionChannel;

    /** Name-only binding: the approval policy (S10-01 owns policies; the name is the bind). */
    private String approvalPolicy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected EnvironmentEntity() {}

    /**
     * A new environment (S9-04): born ACTIVE under its organization,
     * with its stable code, display name, progression position, and the
     * optional name-only channel and policy bindings.
     */
    public EnvironmentEntity(
            UUID organizationId,
            String code,
            String name,
            int position,
            String distributionChannel,
            String approvalPolicy) {
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.position = position;
        this.distributionChannel = distributionChannel;
        this.approvalPolicy = approvalPolicy;
        this.status = Status.ACTIVE;
    }

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

    public String getDistributionChannel() {
        return distributionChannel;
    }

    public String getApprovalPolicy() {
        return approvalPolicy;
    }

    public Status getStatus() {
        return status;
    }

    /** The lifecycle crossing (reversible both ways): the one mutation allowed on a DISABLED row. */
    public void setStatus(Status status) {
        this.status = status;
    }

    /** The PATCH-able fields (the code is the frozen vocabulary and never moves). */
    public void update(String name, int position, String distributionChannel, String approvalPolicy) {
        this.name = name;
        this.position = position;
        this.distributionChannel = distributionChannel;
        this.approvalPolicy = approvalPolicy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
