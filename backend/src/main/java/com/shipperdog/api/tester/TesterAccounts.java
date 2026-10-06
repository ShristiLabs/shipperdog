package com.shipperdog.api.tester;

import java.util.Optional;
import java.util.UUID;

/**
 * The tester module's account-state surface for identity (issues #26,
 * #29). A SUSPENDED or DEACTIVATED (DISABLED) tester may not log in,
 * refresh tokens, or authenticate personal access tokens — the same
 * account-level block the deactivated-organization check applies
 * (uniform 401, distinct audit reason). Implementation lives in the
 * module's {@code .internal.} packages; identity must use only this
 * interface (enforced by ArchUnit).
 */
public interface TesterAccounts {

    /**
     * Why the user's tester record blocks authentication, when it does.
     * The block kind reaches the audit trail only — responses stay the
     * uniform generic 401 so tester state is not revealed.
     */
    enum Block {

        /** The reversible park state (S6-01). */
        SUSPENDED("tester_suspended"),

        /** The deactivation state (S6-04): revocation, not parking. */
        DEACTIVATED("tester_deactivated");

        private final String auditReason;

        Block(String auditReason) {
            this.auditReason = auditReason;
        }

        /** The stable audit-trail reason string for this block. */
        public String auditReason() {
            return auditReason;
        }
    }

    /**
     * The tester-record block on the user's authentication, when one
     * applies. Empty tester records (no link yet) and non-tester users
     * never block.
     */
    Optional<Block> loginBlockFor(UUID userId);
}
