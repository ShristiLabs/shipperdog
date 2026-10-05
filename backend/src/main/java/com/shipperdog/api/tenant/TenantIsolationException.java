package com.shipperdog.api.tenant;

/**
 * Refusal raised by the tenant-isolation seam (INV-001, issue #10) when
 * data access would run without a tenant context, or a write would land
 * outside the context's tenant without the platform-administrator
 * override. This is a server-side programming invariant, not a caller
 * error: it renders through the platform's generic 500 envelope (the
 * leak-proof path) and should never reach a correctly wired caller.
 */
public class TenantIsolationException extends RuntimeException {

    public TenantIsolationException(String message) {
        super(message);
    }
}
