package com.shipperdog.api.authorization;

import java.util.UUID;

/**
 * The in-request application-grant probe (issue #40, S10-01): the
 * programmatic twin of a {@code @PreAuthorize("hasPermission(…,
 * 'application', …)")} check, for surfaces whose required permission
 * is only known after the addressed resource resolves — the approval
 * surface's per-step role check. The route-level annotation stays the
 * coarse gate (the approval action's five-way approve-permission
 * disjunction); this port answers the precise question the step's
 * required role raises: does THIS user hold THIS permission against
 * THIS application, resolved through the application's owning tenant /
 * organization exactly as the evaluator resolves the annotation form.
 *
 * <p>One resolution authority: the {@code RbacPermissionEvaluator}
 * implements this interface, so the programmatic answer and the
 * annotation answer share the same snapshot, the same
 * application-to-owner hop, and the same union semantics (an
 * organization role grants tenant-scope permissions inside the
 * organization's tenants; the platform-administrator wildcard passes
 * everything, PRD 7.1). Implementation lives in the module's
 * {@code .internal.} packages; cross-module callers must use only
 * this interface (enforced by ArchUnit).
 */
public interface ApplicationPermissionCheck {

    /**
     * Whether {@code userId} holds {@code permission} against the
     * application: resolved through the application's owning tenant
     * (with the owning organization's roles overlaid) or, for an
     * organization-shared application, its owning organization. An
     * unknown application denies.
     */
    boolean permits(UUID userId, UUID applicationId, Permission permission);
}
