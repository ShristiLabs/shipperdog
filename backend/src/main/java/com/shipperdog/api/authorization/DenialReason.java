package com.shipperdog.api.authorization;

/**
 * The layered engine's reason codes (issue #30, S7-01): which layer
 * denied and why. Reason codes are INTERNAL — they reach the audit
 * trail (with the correlation id, PRD 27) and the admin-only decision
 * inspection API, never an HTTP error response: a denial renders as
 * the route's concealed not-found shape (PRD 12), byte-for-byte a
 * genuine miss, so the response cannot reveal which layer failed.
 *
 * <p>Values are never removed once audited; new reasons are appended
 * with the story that introduces them (S7-02 will add the
 * grant-specific outcomes over {@code version_access}).
 */
public enum DenialReason {
    // User layer
    SUBJECT_MISSING,

    // Organization layer
    ORGANIZATION_MISSING,
    ORGANIZATION_INACTIVE,

    // Tenant layer
    TENANT_MISSING,
    TENANT_ACCESS_DENIED,

    // Application layer
    APPLICATION_MISSING,
    APPLICATION_NOT_VISIBLE,

    // Version layer
    VERSION_MISSING,
    VERSION_REVOKED,
    ENTITLEMENT_MISSING,

    // Artifact layer
    ARTIFACT_MISSING,
    ARTIFACT_PLATFORM_MISMATCH,
    ARTIFACT_NOT_AVAILABLE,

    // Distribution layer
    DISTRIBUTION_BLOCKED,

    // ABAC layer (S14-04, issue #70): an attribute policy denied —
    // the deny-override refinement over the base layers.
    ABAC_POLICY_DENIED,

    // The engine itself (a layer or resolution fault; fail-closed)
    ENGINE_FAULT
}
