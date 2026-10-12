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
 * Maps the {@code scim_groups} table (V51, S14-03): a group the IdP
 * pushed. The entity's {@code id} is the SCIM id (separate from every
 * internal identifier, like {@link ScimUserEntity}); {@code
 * displayName} is the mapping-rules key and UNIQUE per organization;
 * {@code externalId} is optional (Okta and Workspace send one, Entra
 * sends its id as the resource id) and UNIQUE per organization when
 * present.
 */
@Entity
@Table(name = "scim_groups")
public class ScimGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String externalId;

    private String displayName;

    private Instant createdAt;

    private Instant updatedAt;

    protected ScimGroupEntity() {}

    public ScimGroupEntity(UUID organizationId, String externalId, String displayName) {
        this.organizationId = organizationId;
        this.externalId = externalId;
        this.displayName = displayName;
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

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
