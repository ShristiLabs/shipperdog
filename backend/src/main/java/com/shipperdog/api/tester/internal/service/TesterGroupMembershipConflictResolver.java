package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.internal.entity.TesterGroupMemberEntity;
import com.shipperdog.api.tester.internal.repository.TesterGroupMemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fresh-transaction re-derivation of a group membership after a lost
 * insert race (issue #27) — the TesterConflictResolver pattern from
 * issue #26: the racing transaction is aborted and cannot answer
 * probes (PostgreSQL), so the winner is read on a NEW transaction
 * (REQUIRES_NEW) once the attempt's rollback has completed.
 */
@Component
public class TesterGroupMembershipConflictResolver {

    private final TesterGroupMemberRepository members;

    TesterGroupMembershipConflictResolver(TesterGroupMemberRepository members) {
        this.members = members;
    }

    /**
     * The concurrent winner of the {@code (group, tester)} pair, or
     * empty when the race loser cannot be explained by a membership
     * (the group vanished mid-flight; the caller renders a conflict).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TesterGroupMemberEntity> winnerOf(UUID groupId, UUID testerId) {
        return members.findByGroupIdAndTesterId(groupId, testerId);
    }

    /** The inexplicable-race answer: an idempotent surface never 500s on uniqueness. */
    public TesterException unexplained(UUID groupId, UUID testerId) {
        return new TesterException(
                TesterException.Code.NOT_FOUND,
                "group membership state changed concurrently (" + groupId + "/" + testerId + ")");
    }
}
