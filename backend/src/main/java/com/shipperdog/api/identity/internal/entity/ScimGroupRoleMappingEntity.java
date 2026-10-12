package com.shipperdog.api.identity.internal.entity;

import com.shipperdog.api.organization.OrgRole;
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
 * Maps the {@code scim_group_role_mappings} table (V51, S14-03): one
 * SCIM group display name -> one organization role. When several
 * mapped groups match a user, the most privileged mapped value wins
 * (the SSO group-claim mapping discipline); absence never demotes.
 */
@Entity
@Table(name = "scim_group_role_mappings")
public class ScimGroupRoleMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String groupName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private OrgRole orgRole;

    private Instant createdAt;

    protected ScimGroupRoleMappingEntity() {}

    public ScimGroupRoleMappingEntity(UUID organizationId, String groupName, OrgRole orgRole) {
        this.organizationId = organizationId;
        this.groupName = groupName;
        this.orgRole = orgRole;
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

    public OrgRole getOrgRole() {
        return orgRole;
    }

    public void setOrgRole(OrgRole orgRole) {
        this.orgRole = orgRole;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
