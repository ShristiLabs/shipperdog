package com.shipperdog.api.authorization;

/**
 * The layered authorization engine (issue #30, S7-01, PRD 11/12): one
 * decision service every tenant-scoped read and download consults, so
 * entitlement logic exists exactly once and cannot be bypassed. The
 * decision walks the PRD 11 chain — User, Organization, Tenant,
 * Application, Version, Artifact, Distribution — as ordered
 * {@code AuthorizationLayer} beans; the first failing layer
 * short-circuits with a {@link DenialReason}, every applicable layer
 * before it passed, and the trace of the whole chain rides the result.
 *
 * <p>The engine sits BESIDE the RBAC enforcement point
 * ({@code RbacPermissionEvaluator}), not inside it: RBAC decides who
 * may call the console's management surface; the engine decides which
 * resources a subject may reach — the catalog a tenant sees, the
 * artifacts a version yields. Console-role authority feeds the engine
 * as one entitlement contributor (the role overlay), so a single
 * evaluation authority decides resource access while RBAC keeps
 * guarding management actions.
 *
 * <p>Deny-by-default is absolute (INV-005, P3): a subject with no
 * applicable entitlement — a tester with no grant, a member with no
 * functional role — decides DENY at the version layer. Until the
 * grants of S7-02 land, the only entitlement source is the role
 * overlay; a linked tester with effective tenant access and no console
 * role therefore sees nothing downloadable, exactly the invariant the
 * PRD demands.
 *
 * <p>Every decision emits an {@code AUTHORIZATION_DECISION} audit
 * event (allow and deny alike) carrying the action, the evaluated
 * chain summary, and — attached by the audit binding — the request's
 * correlation id (PRD 27). Decisions are timed on the shared
 * {@code shipperdog.authorization.decision.duration} meter (tagged
 * {@code engine=layered}) against the under-100 ms SLO of PRD 42.
 */
public interface AuthorizationService {

    /**
     * Decides the context: walks the PRD 11 chain in order,
     * short-circuits at the first failing layer, and returns the
     * ALLOW/DENY result with the evaluated-chain trace. Never throws
     * for a denial — the denial is the return value; the caller maps
     * it to its route's concealed shape.
     */
    AuthorizationDecision decide(AuthorizationContext context);

    /**
     * Decides for the inspection surface (S7-01's admin API, advised
     * apart since S7-02): the same decision as {@link #decide}, with
     * one audit distinction — the {@code AUTHORIZATION_DECISION}
     * event carries {@code surface=inspect}, so an inspection the
     * platform administrator runs to debug a denial is
     * distinguishable in the audit trail from the serving decision
     * it mirrors (the audited user stays the inspected subject).
     */
    AuthorizationDecision inspect(AuthorizationContext context);

    /**
     * Decides and enforces: a denial throws the concealed
     * not-found shape of the caller's route (PRD 12), so the response
     * is byte-for-byte what a genuine miss for that resource type
     * produces. The conceal target is the ROUTE's addressing, never
     * the failing layer — a denial must not leak which check failed,
     * so the catalog route conceals everything as the tenant miss and
     * the artifact read conceals its two grains exactly as it always
     * has (version-grain as the version miss, artifact-grain as the
     * artifact miss).
     *
     * @param context the decision to run
     * @param concealTargetType the guarded resource type whose genuine
     *        not-found shape a denial renders ({@code 'tenant'},
     *        {@code 'version'}, ...)
     */
    void require(AuthorizationContext context, String concealTargetType);
}
