package com.shipperdog.api.tester.internal.entity;

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
 * Maps the {@code testers} table (V1, completed by the V19 tester
 * onboarding migration, issue #26): the ORGANIZATION-scoped tester
 * person record of PRD 8.8. {@code userId} is null until the invitee
 * accepts and creates an account (V19 widened the column, the V15
 * build_number precedent). Not a seam entity: the record is scoped to
 * its organization, not a tenant — tenant attachment is the S6-03
 * junction, so no {@code TenantScope} implementation by design.
 */
@Entity
@Table(name = "testers")
public class TesterEntity {

    /**
     * V1's DISABLED stays reserved-unused (the documented enum policy);
     * S6-04 owns revocation semantics. INVITED is the pre-account
     * state; SUSPENDED parks access reversibly (V8 vocabulary
     * precedent).
     */
    public enum Status {
        INVITED,
        ACTIVE,
        SUSPENDED,
        DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Null until invitation acceptance links the created account. */
    private UUID userId;

    private UUID organizationId;

    /** Stored normalized (lower-cased, trimmed) by the service. */
    private String email;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected TesterEntity() {}

    public TesterEntity(UUID organizationId, String email, String fullName) {
        this.organizationId = organizationId;
        this.email = email;
        this.fullName = fullName;
        this.status = Status.INVITED;
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

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
