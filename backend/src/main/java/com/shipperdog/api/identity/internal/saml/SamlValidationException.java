package com.shipperdog.api.identity.internal.saml;

/**
 * One refused SAML document or flow leg (S14-02): the exception's
 * {@code reason} is the distinct token the audit line records -- the
 * browser only ever receives the uniform {@code error=sso} console
 * redirect, exactly like the OIDC flow's refusal carrier.
 */
public class SamlValidationException extends RuntimeException {

    private final String reason;

    SamlValidationException(String reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    /** The audit-line reason (e.g. {@code saml_assertion_signature}). */
    public String reason() {
        return reason;
    }
}
