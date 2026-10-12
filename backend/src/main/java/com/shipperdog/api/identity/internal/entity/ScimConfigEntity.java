package com.shipperdog.api.identity.internal.entity;

import com.shipperdog.api.organization.OrgRole;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code scim_configs} table (V51, S14-03, issue #69): the
 * organization's SCIM provisioning settings. One row per organization;
 * the row's absence reads as not-configured, and {@code enabled=false}
 * keeps the connector configured but refusing every request (the
 * provisioning freeze the leaver investigation wants, without losing
 * the mapping rules).
 */
@Entity
@Table(name = "scim_configs")
public class ScimConfigEntity {

    @Id
    private UUID organizationId;

    private boolean enabled;

    /** The floor role a provisioned user receives when no mapped group applies. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OrgRole defaultRole;

    private Instant createdAt;

    private Instant updatedAt;

    protected ScimConfigEntity() {}

    public ScimConfigEntity(UUID organizationId, boolean enabled, OrgRole defaultRole) {
        this.organizationId = organizationId;
        this.enabled = enabled;
        this.defaultRole = defaultRole;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public OrgRole getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(OrgRole defaultRole) {
        this.defaultRole = defaultRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
