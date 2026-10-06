package com.shipperdog.api.authorization;

import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService;
import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService.DownloadDecisionView;
import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService.EligibilityView;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first-party decision API (issue #32, S7-03, PRD 11/12): the
 * stable internal contract the tester portal (E8) and the download
 * path consume to answer, as the CALLER (the portal's self-scoped
 * persona — a linked tester), "can I fetch this version?" and "which
 * versions can I see?" The subject is always the authenticated
 * caller; there is deliberately no way to decide for another user
 * here — that surface is the platform-administrator inspection API
 * ({@link AuthorizationDecisionController}), which stays exactly as
 * it is (live, audited {@code surface=inspect}, reason codes for the
 * admin alone).
 *
 * <p>Two routes, both self-scoped (the caller is the subject) and
 * decided by the layered engine (audited with
 * {@code surface=decision-api}) — the engine is the portal persona's
 * entire authority, because a pure tester deliberately holds no RBAC
 * role (S6-03: the membership IS the access):
 *
 * <ul>
 *   <li>{@code GET /api/v1/authorization/decisions/download} — the
 *       PRD 12 download decision for
 *       {@code tenantId}/{@code versionId}/{@code platform}; ALLOW
 *       answers the signed-URL issuance inputs (artifact id + storage
 *       key reference) S8-04 signs; denials conceal at the route's
 *       two grains (version/artifact), never leaking the failing
 *       layer.</li>
 *   <li>{@code GET /api/v1/authorization/decisions/eligibility} —
 *       the caller's authorized version set for
 *       {@code tenantId}/{@code applicationId} (FR-013's
 *       version-history page), the effective-set computation the
 *       administrator's view runs, self-scoped.</li>
 * </ul>
 *
 * <p>This is NOT the deferred public third-party "authorize"
 * endpoint (out of scope by the story) and issues no URLs: the
 * decision is the API; signing is S8-04's. Performance rides the
 * S7-03 decision-input cache (PRD 42's under-100 ms SLO).
 */
@RestController
@RequestMapping("/api/v1/authorization/decisions")
public class AuthorizationDecisionApiController {

    private final AuthorizationDecisionApiService decisions;

    AuthorizationDecisionApiController(AuthorizationDecisionApiService decisions) {
        this.decisions = decisions;
    }

    /**
     * The caller's download decision: the engine's strictest chain
     * (INV-007's revoked block, PRD 18's distributable availability,
     * the FR-011 entitlement) for the addressed tenant, version, and
     * platform. ALLOW carries the issuance inputs; DENY renders the
     * route's concealed miss shape (404, byte-for-byte the genuine
     * miss — the caller learns nothing about why).
     *
     * <p>Self-scoped ({@code isAuthenticated()}, the platform's own
     * convention for caller-addressed surfaces): the tester persona
     * holds no RBAC role by design (S6-03 — the membership IS the
     * access), so the layered engine is this route's entire authority;
     * the engine's tenant layer denies an unrelated caller with the
     * same concealed miss a genuine unknown produces.
     */
    @GetMapping("/download")
    @PreAuthorize("isAuthenticated()")
    DownloadDecisionView download(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID tenantId,
            @RequestParam UUID versionId,
            @RequestParam String platform) {
        return decisions.download(requireSubject(principal), tenantId, versionId, platform);
    }

    /**
     * The caller's authorized versions of the application: the
     * effective-access entries (newest first, with each entry's TTL),
     * self-scoped — no source-grant attribution, no administrative
     * detail. An unknown or invisible tenant/application conceals as
     * the genuine miss; a caller with no grants sees an empty set.
     * Self-scoped like the download route: the engine decides.
     */
    @GetMapping("/eligibility")
    @PreAuthorize("isAuthenticated()")
    EligibilityView eligibility(
            @AuthenticationPrincipal Object principal, @RequestParam UUID tenantId, @RequestParam UUID applicationId) {
        return decisions.eligibility(requireSubject(principal), tenantId, applicationId);
    }

    /** An unresolvable subject is a concealed denial, never a 500 (the evaluator's own rule). */
    private static UUID requireSubject(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ConcealedResourceAccessDeniedException("tenant");
    }
}
