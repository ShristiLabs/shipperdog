package com.shipperdog.api.identity.internal.entity;

import com.shipperdog.api.organization.OrgRole;
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

/** Maps the {@code user_invitations} table (V2 migration). */
@Entity
@Table(name = "user_invitations")
public class UserInvitationEntity {

    public enum Status {
        PENDING,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String email;

    private UUID invitedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private String tokenHash;

    /** Optional organization context (issue #4): accepting grants membership. */
    private UUID organizationId;

    /** The membership role granted on acceptance; null for plain invitations. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OrgRole role;

    /**
     * Tester-onboarding flavor (issue #26): the tester record the
     * invitation activates on acceptance. Mutually exclusive with
     * {@code role} per {@code ck_user_invitations_scope}; null for
     * platform and org-member invitations.
     */
    private UUID testerId;

    private Instant expiresAt;

    private Instant acceptedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected UserInvitationEntity() {}

    public UserInvitationEntity(String email, UUID invitedBy, String tokenHash, Instant expiresAt) {
        this(email, invitedBy, tokenHash, expiresAt, null, null);
    }

    public UserInvitationEntity(
            String email, UUID invitedBy, String tokenHash, Instant expiresAt, UUID organizationId, OrgRole role) {
        this(email, invitedBy, tokenHash, expiresAt, organizationId, role, null);
    }

    public UserInvitationEntity(
            String email,
            UUID invitedBy,
            String tokenHash,
            Instant expiresAt,
            UUID organizationId,
            OrgRole role,
            UUID testerId) {
        this.email = email;
        this.invitedBy = invitedBy;
        this.status = Status.PENDING;
        this.tokenHash = tokenHash;
        this.organizationId = organizationId;
        this.role = role;
        this.testerId = testerId;
        this.expiresAt = expiresAt;
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

    public String getEmail() {
        return email;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public OrgRole getRole() {
        return role;
    }

    public UUID getTesterId() {
        return testerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
