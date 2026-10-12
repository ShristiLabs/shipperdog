package com.shipperdog.api.identity.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code scim_users} table (V51, S14-03, issue #69): the
 * joiner/leaver registry. The entity's {@code id} IS the SCIM "id" the
 * IdP addresses the resource by — deliberately a SEPARATE UUID from
 * {@code users.id} (the story's technical note), so the internal
 * account identifier never rides a SCIM URL. {@code externalId} (the
 * IdP's own stable person id) is the join key: UNIQUE per
 * organization, it makes create idempotent (re-provisioning after
 * deactivation finds the same row, the same SCIM id, and the same
 * account). {@code active} mirrors the SCIM active attribute — the
 * deprovisioning switch the hard lock follows.
 */
@Entity
@Table(name = "scim_users")
public class ScimUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID userId;

    private String externalId;

    private String userName;

    private boolean active;

    private Instant createdAt;

    private Instant updatedAt;

    protected ScimUserEntity() {}

    public ScimUserEntity(UUID organizationId, UUID userId, String externalId, String userName, boolean active) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.externalId = externalId;
        this.userName = userName;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
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

    public UUID getUserId() {
        return userId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
