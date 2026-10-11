package com.shipperdog.api.identity.internal.entity;

import com.shipperdog.api.tenant.TenantRole;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code scim_group_tenant_mappings} table (V51, S14-03):
 * one SCIM group display name -> one tenant membership role. A group
 * may map to several tenants (one row each); members of the group
 * receive each mapped tenant membership. The tenant must belong to
 * the mapping's organization — validated at write time.
 */
@Entity
@Table(name = "scim_group_tenant_mappings")
public class ScimGroupTenantMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String groupName;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private TenantRole tenantRole;

    private Instant createdAt;

    protected ScimGroupTenantMappingEntity() {}

    public ScimGroupTenantMappingEntity(UUID organizationId, String groupName, UUID tenantId, TenantRole tenantRole) {
        this.organizationId = organizationId;
        this.groupName = groupName;
        this.tenantId = tenantId;
        this.tenantRole = tenantRole;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getGroupName() {
        return groupName;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public TenantRole getTenantRole() {
        return tenantRole;
    }

    public void setTenantRole(TenantRole tenantRole) {
        this.tenantRole = tenantRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
