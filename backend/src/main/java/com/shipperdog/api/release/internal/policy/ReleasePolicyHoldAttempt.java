package com.shipperdog.api.release.internal.policy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hold insert's own transaction (the #26/#27 attempt doctrine).
 *
 * <p>A PENDING hold is one fact per (release, rule, ring): concurrent
 * promote attempts that the same HOLD rule refuses both try to record
 * it, and the loser's insert dies on the unique constraint. That
 * violation poisons <em>only this transaction</em> — running it
 * {@link Propagation#REQUIRES_NEW} keeps the caller's evaluation
 * transaction sound, so the loser swallows the violation and carries
 * on with its own evaluation row and per-rule outcomes. No re-read
 * recovery is needed (a same-transaction re-read after the abort is
 * the 25P02 500 the SCIM ingest review documented); the winner's
 * committed row <em>is</em> the fact.
 *
 * <p>Committing the hold independently of the caller is the intended
 * semantics, not a side effect: a hold is refusal evidence, and the
 * platform's standing discipline (#45, #65) is that evidence survives
 * the refusal's rollback.
 */
@Component
public class ReleasePolicyHoldAttempt {

    private final ReleasePolicyHoldRepository holds;

    ReleasePolicyHoldAttempt(ReleasePolicyHoldRepository holds) {
        this.holds = holds;
    }

    /** Inserts one hold fact in its own transaction; a lost race throws to the caller. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ReleasePolicyHoldEntity hold) {
        holds.saveAndFlush(hold);
    }
}
