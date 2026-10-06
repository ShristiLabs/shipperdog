package com.shipperdog.api.authorization;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The structured decision inputs of one layered authorization decision
 * (issue #30, S7-01, PRD 11/12): who is asking ({@code userId} — the
 * authenticated subject; the engine never decides for anonymous
 * callers, authentication is a 401 of the security chain, not a
 * decision), and what they are asking for — the resource ids of the
 * PRD 11 chain plus the {@link AuthorizationAction}. Ids the caller
 * does not address are null and the engine derives them walking the
 * chain (a version implies its application and owning dimensions).
 *
 * <p>Inputs stay structured (ids and enums, never free-form rules):
 * this object is the seam the S14-04 ABAC engine extends with resource
 * attributes, exactly the way {@code UserRolesSnapshot} is the RBAC
 * seam. Layers orchestrate only — entitlements come from
 * {@link AuthorizationEntitlement} contributors (S7-02 grants) and
 * mappings from the application module (S3-03); no layer hard-codes a
 * business rule.
 *
 * <p>Platform names match the application registry's vocabulary
 * ({@code IOS} / {@code ANDROID}).
 *
 * @param userId        the authenticated subject; never null
 * @param tenantId      the tenant dimension, when the request addresses
 *                      one (the catalog listing, a tenant-scoped
 *                      download); null derives from the resource ids
 * @param applicationId the addressed application, or null
 * @param versionId     the addressed version, or null
 * @param artifactId    the addressed artifact, or null
 * @param platform      the requested platform (PRD 12's "Android"
 *                      input), or null when platform-free
 * @param distributionId the addressed distribution record (M2), or null
 * @param action        what the subject wants; never null
 */
public record AuthorizationContext(
        UUID userId,
        UUID tenantId,
        UUID applicationId,
        UUID versionId,
        UUID artifactId,
        String platform,
        UUID distributionId,
        AuthorizationAction action) {

    /** The catalog-list decision of one subject over one tenant. */
    public static AuthorizationContext listApplications(UUID userId, UUID tenantId) {
        return new AuthorizationContext(
                userId, tenantId, null, null, null, null, null, AuthorizationAction.LIST_APPLICATIONS);
    }

    /** The artifact-fetch decision of one subject over one artifact under a version. */
    public static AuthorizationContext readArtifact(UUID userId, UUID versionId, UUID artifactId) {
        return new AuthorizationContext(
                userId, null, null, versionId, artifactId, null, null, AuthorizationAction.READ_ARTIFACT);
    }

    /** The download decision shape of PRD 12's worked example. */
    public static AuthorizationContext download(
            UUID userId, UUID tenantId, UUID applicationId, UUID versionId, UUID artifactId, String platform) {
        return new AuthorizationContext(
                userId, tenantId, applicationId, versionId, artifactId, platform, null, AuthorizationAction.DOWNLOAD);
    }

    public AuthorizationContext {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(action, "action");
        if (platform != null && platform.isBlank()) {
            throw new IllegalArgumentException("platform must not be blank");
        }
    }

    /** The addressed platform, when the request pinned one. */
    public Optional<String> platformOrNull() {
        return Optional.ofNullable(platform);
    }
}
