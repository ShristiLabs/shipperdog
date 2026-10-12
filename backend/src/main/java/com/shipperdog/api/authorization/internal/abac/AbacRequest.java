package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import java.util.UUID;

/**
 * One ABAC evaluation request (issue #70, S14-04): the structured
 * attribute SOURCES a caller carries — the decision's subject,
 * organization, action, and the addressed resources — from which the
 * engine resolves the {@link AbacAttributeSnapshot}'s attributes
 * lazily (only the attributes the applicable policies reference
 * resolve; the S7-03 latency budget demands nothing more). The
 * layered engine builds this from its {@code AuthorizationContext}
 * and evaluation state; the promotion consult builds it from the hop
 * facts (releaseId and the environment/ring attributes only that
 * surface can carry).
 *
 * @param organizationId the organization whose policies evaluate (the resource's owning org)
 * @param userId         the subject
 * @param action         the decision being refined
 * @param applicationId  the addressed application, or null
 * @param environmentId  the addressed environment (a release's target), or null
 * @param platform       the pinned platform (IOS/ANDROID), or null
 * @param releaseId      the addressed release (the security-status source), or null
 * @param ring           the ring the decision addresses (a promotion's TARGET), or null
 */
public record AbacRequest(
        UUID organizationId,
        UUID userId,
        GoverningAction action,
        UUID applicationId,
        UUID environmentId,
        String platform,
        UUID releaseId,
        String ring) {

    public AbacRequest {
        if (organizationId == null || userId == null || action == null) {
            throw new IllegalArgumentException("organizationId, userId, and action are required");
        }
    }
}
