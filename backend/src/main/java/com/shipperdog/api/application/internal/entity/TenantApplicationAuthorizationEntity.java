package com.shipperdog.api.application.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code tenant_application_authorizations} (V11): the
 * shared-application tenant authorization mapping (issue #15, S3-03,
 * PRD 15). Exactly one row per application x tenant pair
 * ({@code uq_tenant_application_authorizations}); the row's status is the
 * pair's current state — {@link Status#ACTIVE} authorizes the tenant's
 * visibility of a shared application, {@link Status#REVOKED} is the
 * retained historical state after de-authorization (revocation without
 * deletion, the INV-007 philosophy). Grant/revocation HISTORY lives in
 * the immutable audit trail with actor and reason; the row carries only
 * the current grant ({@code grantedBy}/{@code grantedAt}, re-stamped on
 * re-grant) and, once revoked, the revocation
 * ({@code revokedBy}/{@code revokedAt}), pinned coherent by the V11
 * CHECK. The flips themselves are conditional state transitions on the
 * repository ({@code regrantIfRevoked} / {@code revokeIfActive}): one
 * winner per transition under concurrency, so the entity carries no
 * mutating methods.
 *
 * <p>The entity deliberately implements neither {@code TenantScope} nor
 * {@code OrganizationScope}: these rows are cross-tenant authorization
 * data by definition — an organization's distribution decisions pointing
 * into many tenants of one organization (PRD 15; the E7 authorization
 * chain's input layer per the issue's technical notes). Neither seam
 * mode can serve them (a tenant context would miss the
 * organization-level question; the organization-scope predicate composes
 * {@code tenantId IS NULL} and these rows carry the authorized tenant),
 * so access runs through the marked repository (ADR-0002, fourth
 * amendment), whose sanctioned questions are exactly this story's own
 * cross-tenant ones.
 */
@Entity
@Table(
        name = "tenant_application_authorizations",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_tenant_application_authorizations_pair",
                        columnNames = {"application_id", "tenant_id"}))
public class TenantApplicationAuthorizationEntity {

    /**
     * The mapping's status vocabulary (V11 native enum
     * {@code application_authorization_status}): ACTIVE authorizes
     * visibility, REVOKED is retained history invisible to resolution.
     */
    public enum Status {
        ACTIVE,
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID applicationId;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private UUID grantedBy;

    private Instant grantedAt;

    private UUID revokedBy;

    private Instant revokedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected TenantApplicationAuthorizationEntity() {}

    /**
     * A new grant (issue #15, AC1): born ACTIVE with its grant stamped;
     * the revocation columns stay null until a de-authorization flips
     * the row (the V11 CHECK pins the pairing).
     */
    public TenantApplicationAuthorizationEntity(UUID applicationId, UUID tenantId, UUID grantedBy, Instant grantedAt) {
        this.applicationId = applicationId;
        this.tenantId = tenantId;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
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

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    /** The pair's current state; never null once persisted. */
    public Status getStatus() {
        return status;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
