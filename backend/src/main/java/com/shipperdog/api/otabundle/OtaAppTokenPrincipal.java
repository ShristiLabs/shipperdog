package com.shipperdog.api.otabundle;

import java.io.Serializable;
import java.util.UUID;

/**
 * The authenticated principal an OTA app token establishes (issue
 * #166, S16-04, ADR-0010 D3): the application the token was minted for
 * and the owning organization and tenant the registry + application
 * resolution yield — everything the device check (S16-07) needs to
 * resolve unambiguously, because bundleId is NOT tenant-unique. There
 * is no client-supplied identity on the check contract to spoof — the
 * token IS the reporter, and its resolution is refused server-side the
 * moment the registry row is revoked or incoherent with the payload's
 * claims.
 *
 * <p>Deliberately narrow: the authentication this principal rides
 * carries exactly one authority ({@code ota:app-token:check}) and
 * nothing else in the platform honors it — an app token can never
 * read, write, or decide anything but the device check it was minted
 * for (the {@code TelemetryTokenPrincipal} doctrine).
 *
 * @param organizationId  the owning organization (the token's claim,
 *                        verified against the registry row)
 * @param tenantId        the application's owning tenant, null for an
 *                        organization-shared application (the
 *                        application row's own dimension)
 * @param applicationId   the application the token was minted for
 * @param tokenId         the registry row's id (the revocation target
 *                        the console revokes by)
 */
public record OtaAppTokenPrincipal(UUID organizationId, UUID tenantId, UUID applicationId, UUID tokenId)
        implements Serializable {}
