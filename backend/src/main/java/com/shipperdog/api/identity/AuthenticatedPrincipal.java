package com.shipperdog.api.identity;

import java.util.UUID;

/**
 * The security principal for personal-access-token authentication. Carries
 * the authenticated user and, when applicable, the id of the token used
 * (for revocation checks and audit detail). Public because every module's
 * web layer resolves the current actor from either this type or a JWT.
 *
 * @param userId the authenticated user's id
 * @param patId  the personal access token id, or null when the request is
 *               JWT-authenticated (those requests use a Jwt principal)
 */
public record AuthenticatedPrincipal(UUID userId, UUID patId) {}
