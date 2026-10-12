package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimGroupMemberEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupMemberId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimGroupMemberRepository extends JpaRepository<ScimGroupMemberEntity, ScimGroupMemberId> {

    List<ScimGroupMemberEntity> findByScimGroupId(UUID scimGroupId);

    /** Every group the SCIM user belongs to (assignment re-derivation). */
    List<ScimGroupMemberEntity> findByScimUserId(UUID scimUserId);

    void deleteByScimGroupIdAndScimUserIdIn(UUID scimGroupId, Collection<UUID> scimUserIds);

    void deleteByScimGroupId(UUID scimGroupId);
}
