package com.shipperdog.api.identity.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The composite primary key of {@code scim_group_members}: group x user (V51, S14-03). */
public record ScimGroupMemberId(UUID scimGroupId, UUID scimUserId) implements Serializable {

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScimGroupMemberId id)) {
            return false;
        }
        return Objects.equals(scimGroupId, id.scimGroupId) && Objects.equals(scimUserId, id.scimUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scimGroupId, scimUserId);
    }
}
