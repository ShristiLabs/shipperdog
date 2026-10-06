package com.shipperdog.api.tester.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite identity of {@code tester_group_members} (V1 baseline, the
 * junction precedent PRD 13 set): a membership IS the
 * {@code (group_id, tester_id)} pair — the table's primary key — which
 * makes duplicate inserts a database-refused impossibility and
 * membership add/remove naturally idempotent (issue #27, acceptance
 * criterion 2).
 */
public record TesterGroupMemberId(UUID groupId, UUID testerId) implements Serializable {

    public TesterGroupMemberId {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(testerId, "testerId");
    }
}
