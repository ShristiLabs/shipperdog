package com.shipperdog.api.identity.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code scim_group_members} table (V51, S14-03): the
 * (group, user) membership pairs the IdP pushed. Both legs address
 * the SCIM-side ids (the {@code scim_groups.id} and {@code
 * scim_users.id} values), never internal identifiers.
 */
@Entity
@Table(name = "scim_group_members")
@IdClass(ScimGroupMemberId.class)
public class ScimGroupMemberEntity {

    @Id
    private UUID scimGroupId;

    @Id
    private UUID scimUserId;

    private Instant addedAt;

    protected ScimGroupMemberEntity() {}

    public ScimGroupMemberEntity(UUID scimGroupId, UUID scimUserId) {
        this.scimGroupId = scimGroupId;
        this.scimUserId = scimUserId;
    }

    @PrePersist
    void onCreate() {
        addedAt = Instant.now();
    }

    public UUID getScimGroupId() {
        return scimGroupId;
    }

    public UUID getScimUserId() {
        return scimUserId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
