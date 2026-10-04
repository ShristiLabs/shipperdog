package com.shipperdog.api.identity.internal;

import java.util.UUID;

/**
 * The security principal for personal-access-token authentication. Carries
 * the authenticated user and, when applicable, the id of the token used
 * (for revocation checks and audit detail).
 *
 * @param userId the authenticated user's id
 * @param patId  the personal access token id, or null when the request is
 *               JWT-authenticated (those requests use a Jwt principal)
 */
public record AuthenticatedPrincipal(UUID userId, UUID patId) {}
