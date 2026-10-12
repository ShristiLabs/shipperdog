package com.shipperdog.api.analytics;

import java.io.Serializable;
import java.util.UUID;

/**
 * The authenticated principal a telemetry token establishes (issue
 * #72, S15-01): the downloading tester the S8-04 issuance minted the
 * token for, the tenant and release it is scoped to, and the optional
 * device binding. There is no client-supplied identity on the events
 * contract to spoof — the token IS the reporter, and every binding it
 * carries is enforced server-side at ingest (the path tenant and each
 * event's release must be the token's own; a device-bound token
 * accepts only its device, and the device's ACTIVE status is the
 * revocation switch the registry's two-switches rule already owns).
 *
 * <p>Deliberately narrow: the authentication this principal rides
 * carries exactly one authority ({@code analytics:telemetry:ingest})
 * and nothing else in the platform honors it — a telemetry token can
 * never read, write, or decide anything but its own release's event
 * batches.
 *
 * @param tenantId      the reporting tenant (the membership the
 *                      download answered; must be a target of the
 *                      release — validated at mint)
 * @param testerUserId  the downloading tester's USER id (the audit
 *                      actor of every ingest the token authenticates)
 * @param releaseId     the release the download issued (every event
 *                      of a token-authenticated batch must name it)
 * @param deviceId      the optional device binding (a registered
 *                      ACTIVE device of the tenant owned by the
 *                      tester; {@code null} = tester-scoped token)
 * @param expiresAt     the token's expiry instant
 */
public record TelemetryTokenPrincipal(
        UUID tenantId, UUID testerUserId, UUID releaseId, UUID deviceId, java.time.Instant expiresAt)
        implements Serializable {}
