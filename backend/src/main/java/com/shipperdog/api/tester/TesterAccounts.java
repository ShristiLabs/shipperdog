package com.shipperdog.api.tester;

import java.util.UUID;

/**
 * The tester module's account-state surface for identity (issue #26).
 * A SUSPENDED tester may not log in or refresh tokens — the same
 * account-level block the deactivated-organization check applies
 * (uniform 401, distinct audit reason). Implementation lives in the
 * module's {@code .internal.} packages; identity must use only this
 * interface (enforced by ArchUnit).
 */
public interface TesterAccounts {

    /**
     * Whether the user's tester record is SUSPENDED. Empty tester
     * records (no link yet) and non-tester users never block.
     */
    boolean loginBlockedFor(UUID userId);
}
