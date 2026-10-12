package com.shipperdog.api.identity.internal.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional boundary of SCIM's fresh-resource provisioning
 * legs (S14-03, issue #69). The Users and Groups creates run their
 * inserts through this bean ON PURPOSE — the lost-race doctrine of
 * issues #26/#27, held for the tenant surfaces by
 * {@code TenantMemberWriteAttempt} since #28 and
 * {@code TenantMemberProvisioningAttempt} in this very story: a
 * find-then-insert under the caller's own transaction is a dead
 * catch for a TRUE race, because PostgreSQL has already aborted that
 * transaction by the time the unique violation surfaces — the loser
 * answered 500 where the protocol promises the idempotent 200.
 *
 * <p>With the whole leg (the account insert, the membership floor,
 * the resource row, the assignment re-derivation, the audit line)
 * isolated here in REQUIRES_NEW and flushed inside, a lost
 * uniqueness race rolls back only the attempt; the caller's
 * transaction stays sound and re-derives the concurrent winner.
 */
@Component
public class ScimProvisioningAttempt {

    /**
     * Runs a fresh-provisioning leg in this bean's own transaction.
     * The leg MUST flush its inserts before returning, so a lost
     * uniqueness race throws HERE — inside the boundary — and not at
     * the caller's commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T attempt(Supplier<T> leg) {
        return leg.get();
    }
}
