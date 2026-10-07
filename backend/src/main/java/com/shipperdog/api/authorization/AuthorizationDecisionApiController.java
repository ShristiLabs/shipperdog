package com.shipperdog.api.authorization;

import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService;
import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService.CatalogView;
import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService.DownloadDecisionView;
import com.shipperdog.api.authorization.internal.decision.AuthorizationDecisionApiService.EligibilityView;
import com.shipperdog.api.config.ListQuery;
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
 * <p>Three routes, all self-scoped (the caller is the subject) and
 * decided by the layered engine (audited with
 * {@code surface=decision-api}) — the engine is the portal persona's
 * entire authority, because a pure tester deliberately holds no RBAC
 * role (S6-03: the membership IS the access):
 *
 * <ul>
 *   <li>{@code GET /api/v1/authorization/decisions/download} — the
 *       PRD 12 download decision for
 *       {@code tenantId}/{@code versionId}/{@code platform}; ALLOW
 *       answers the artifact id the download would serve (since
 *       S8-04 WITHOUT the storage key reference — signed URLs are the
 *       binary's only egress, and the issuance route that mints them
 *       is the artifact module's {@code GET /api/v1/artifacts/{id}/
 *       download}); denials conceal at the route's two grains
 *       (version/artifact), never leaking the failing layer.</li>
 *   <li>{@code GET /api/v1/authorization/decisions/eligibility} —
 *       the caller's authorized version set for
 *       {@code tenantId}/{@code applicationId} (FR-013's
 *       version-history page), the effective-set computation the
 *       administrator's view runs, self-scoped.</li>
 *   <li>{@code GET /api/v1/authorization/decisions/applications} —
 *       the caller's authorized application catalog (S8-02, the
 *       portal home): the card set the effective resolution yields
 *       across the tester's sanctioned tenants, cursor-paginated.</li>
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
        return decisions.download(requireSubject(principal, "version"), tenantId, versionId, platform);
    }

    /**
     * The caller's authorized versions of the application (FR-013,
     * the S8-03 version-history page's data path): the effective-
     * access entries newest first by the version sort key, each with
     * its TTL and its per-platform installable slots (the newest
     * distributable artifact's build number, size, and upload date),
     * paged at the story's default 20 through the platform cursor
     * envelope — self-scoped, no source-grant attribution, no
     * administrative detail. An optional {@code versionId} narrows
     * the answer to that one version: the portal's deep-link probe,
     * whose miss (unknown, foreign, or unauthorized) conceals as the
     * version grain's genuine miss. An unknown or invisible
     * tenant/application conceals as the genuine miss; a caller with
     * no grants sees an empty set. Self-scoped like the download
     * route: the engine decides.
     */
    @GetMapping("/eligibility")
    @PreAuthorize("isAuthenticated()")
    EligibilityView eligibility(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID tenantId,
            @RequestParam UUID applicationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID versionId) {
        return decisions.eligibility(
                requireSubject(principal, "tenant"), tenantId, applicationId, limit, cursor, versionId);
    }

    /**
     * The caller's authorized application catalog (issue #36, S8-02,
     * FR-012/PRD 31): one card per application the tester can reach
     * and install — the server-computed set (PRD 26: never a filtered
     * bigger list), each card carrying the application, the latest
     * authorized version, and the per-platform latest installable
     * resolution (the contract S8-03/S8-04 reuse). Cursor-paginated in
     * the platform list envelope; a grantless-but-linked tester sees
     * the honest EMPTY catalog, and a caller without a tester record
     * (a console persona) answers empty the same way — this route
     * addresses no foreign resource, so there is nothing to conceal
     * and the set form's answer is emptiness. Self-scoped like the
     * other decision routes: the engine decides, never RBAC.
     */
    @GetMapping("/applications")
    @PreAuthorize("isAuthenticated()")
    CatalogView catalog(
            @AuthenticationPrincipal Object principal,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return decisions.catalog(requireSubject(principal, "catalog"), ListQuery.of(limit, cursor));
    }

    /**
     * An unresolvable subject is a concealed denial, never a 500 (the
     * evaluator's own rule) — at the CALLING route's own miss grain:
     * version for the download route, tenant for the eligibility
     * route.
     */
    private static UUID requireSubject(Object principal, String missGrain) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ConcealedResourceAccessDeniedException(missGrain);
    }
}
