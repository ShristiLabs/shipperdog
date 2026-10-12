package com.shipperdog.api.authorization;

import java.util.Optional;
import java.util.UUID;

/**
 * The ABAC promotion consult (issue #70, S14-04): the public port the
 * release module's promotion precheck consults — the post-RBAC
 * refinement hook for the PRD 25 worked example ("Can promote IF
 * user.role = ReleaseManager AND application = Banking AND
 * environment = Production AND security.status = APPROVED"). The
 * promote route's own {@code @PreAuthorize} RBAC check has already
 * decided who may ENTER the surface; this consult refines WITHIN it:
 * empty permits, a present value is the refusal the promotion 409
 * renders (keyed by the contributor's name, the seam's rendering).
 *
 * <p>The consult audites its own denials (ABAC_POLICY_DENIED, with
 * the winning rule and the evaluated attributes — AC3) because the
 * promotion surface has no standing AUTHORIZATION_DECISION event of
 * its own; the layered engine's ABAC denials ride that event instead,
 * so no decision is audited twice.
 *
 * <p>Fail-closed (AC6): a fault or an unresolvable attribute answers
 * a denial, never a silent permit.
 */
public interface AbacConsult {

    /**
     * Whether the promotion may proceed: empty permits; a present
     * value is the human-readable refusal naming the rule, its
     * version, and the evaluated attributes.
     *
     * @param request the hop's attribute sources: the owning
     *        organization, the acting subject, the application, the
     *        release's targeted environment, the release (the
     *        security-state source), and the ring the hop ENTERS
     */
    Optional<String> promotionDenial(AbacPromotionRequest request);

    /**
     * The promotion consult's attribute sources — the release-grain
     * facts only that surface carries (the layered engine's contexts
     * address resources, not releases).
     *
     * @param organizationId the release's owning organization
     * @param userId         the acting subject
     * @param applicationId  the release's application
     * @param environmentId  the release's targeted environment, or null
     * @param releaseId      the release being promoted (the security-state source)
     * @param ring           the ring the hop ENTERS
     */
    record AbacPromotionRequest(
            UUID organizationId, UUID userId, UUID applicationId, UUID environmentId, UUID releaseId, String ring) {}
}
