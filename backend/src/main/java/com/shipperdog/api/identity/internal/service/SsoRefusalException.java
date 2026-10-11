package com.shipperdog.api.identity.internal.service;

import java.util.UUID;

/**
 * One refused SSO login leg (S14-01/S14-02): thrown by the shared
 * {@code SsoAccountResolver} after the {@code SSO_LOGIN_FAILURE} audit
 * line with the distinct reason is published. Each provider flow
 * catches it and renders its own uniform browser outcome -- the OIDC
 * callback's bare {@code error=sso} redirect, the SAML ACS's identical
 * one -- so the reason exists ONLY on the audit line, never to the
 * browser.
 */
public class SsoRefusalException extends RuntimeException {

    private final String reason;

    SsoRefusalException(UUID userId, UUID organizationId, String reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    /** The audit-line refusal reason (e.g. {@code not_invited}). */
    public String reason() {
        return reason;
    }
}
