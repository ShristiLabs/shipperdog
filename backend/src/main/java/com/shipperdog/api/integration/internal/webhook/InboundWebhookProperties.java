package com.shipperdog.api.integration.internal.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The inbound webhook receiver's knobs (issue #53, S12-03, FR-022):
 * the per-provider signing secrets (a LIST -- rotation keeps the old
 * secret accepted until every sender has moved), the replay window
 * the generic framework applies when a provider timestamps its
 * deliveries, and the body ceiling every inbound delivery passes
 * before it is parsed. Environment-configurable per the platform's
 * standing ({@code shipperdog.webhooks.*} in application.yml,
 * {@code WEBHOOKS_*} in the deployment env), with local defaults a
 * compose stack runs on unchanged -- except the secrets, which have
 * no committed default: an unset secret list rejects every delivery
 * with the uniform 401 (the receiver is off until an operator turns
 * it on), and no secret ever lands in a committed file (the public
 * repo posture).
 *
 * @param githubSecrets       the GitHub webhook's HMAC-SHA256 secrets
 *                            (comma-separated in the env); every
 *                            entry is tried constant-time, so
 *                            rotation is overlap, never cutover
 * @param maxTimestampSkew    how far a provider-sent timestamp header
 *                            may sit from now before the delivery is
 *                            a replay (S12-04's store receivers send
 *                            timestamps; GitHub does not, so its
 *                            deliveries carry none and the window
 *                            never applies to them)
 * @param maxBodyBytes        the inbound payload ceiling -- a
 *                            delivery beyond it answers 413 before
 *                            its bytes are parsed (GitHub caps its
 *                            own webhook payloads at 25 MB; the
 *                            default matches that ceiling)
 */
@ConfigurationProperties(prefix = "shipperdog.webhooks")
public record InboundWebhookProperties(
        @DefaultValue List<String> githubSecrets,
        @DefaultValue("PT5M") Duration maxTimestampSkew,
        @DefaultValue("26214400") long maxBodyBytes) {

    /** The default body ceiling: GitHub's own 25 MB webhook payload cap. */
    public static final long DEFAULT_MAX_BODY_BYTES = 26_214_400L;
}
