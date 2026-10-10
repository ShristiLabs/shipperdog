package com.shipperdog.api.integration.internal.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The inbound webhook receiver's knobs (issue #53, S12-03, FR-022;
 * extended by issue #54, S12-04): the per-provider credentials (LISTS
 * wherever the provider supports rotation -- rotation keeps the old
 * credential accepted until every sender has moved), the replay
 * window the generic framework applies when a provider timestamps its
 * deliveries, and the body ceiling every inbound delivery passes
 * before it is parsed. Environment-configurable per the platform's
 * standing ({@code shipperdog.webhooks.*} in application.yml,
 * {@code WEBHOOKS_*} in the deployment env), with local defaults a
 * compose stack runs on unchanged -- except the credentials, which
 * have no committed default: an unset credential keeps that receiver
 * OFF (every delivery answered with the family's uniform rejection),
 * and no secret ever lands in a committed file (the public repo
 * posture).
 *
 * @param githubSecrets       the GitHub webhook's HMAC-SHA256 secrets
 *                            (comma-separated in the env); every
 *                            entry is tried constant-time, so
 *                            rotation is overlap, never cutover
 * @param jenkinsSecrets      the Jenkins receiver's HMAC-SHA256
 *                            secrets -- Jenkins has no standard
 *                            webhook signature, so the receiver
 *                            defines the contract (S12-04): the
 *                            pipeline computes
 *                            {@code X-Shipperdog-Signature:
 *                            sha256=hex(HMAC-SHA256(secret, body))}
 *                            over the exact body bytes, the same
 *                            shape GitHub uses, with Jenkins's own
 *                            secret list (the documented Jenkinsfile
 *                            snippet carries the openssl one-liner)
 * @param gitlabSecrets       the GitLab receiver's shared tokens --
 *                            GitLab's webhook protocol sends the
 *                            configured secret verbatim in
 *                            {@code X-Gitlab-Token} (no body
 *                            signature), so this family compares the
 *                            token constant-time against the rotating
 *                            list and the transport MUST be TLS (the
 *                            deployment note)
 * @param appstoreBundleIds   the App Store receiver's bundle-id
 *                            allowlist -- Apple's JWS signature alone
 *                            proves the notification is Apple's, not
 *                            that it is about THIS deployment's apps;
 *                            the allowlist is the authorization leg.
 *                            Empty (the default) keeps the receiver
 *                            off: a notification for a bundle not on
 *                            the list is acknowledged and ignored
 * @param appstoreTrustedRoots the trusted root certificates for the
 *                            JWS x5c chain validation (Apple Root CA
 *                            G3 by default, the public certificate
 *                            Apple publishes at
 *                            apple.com/certificateauthority -- a
 *                            public root, not a secret); resource
 *                            locations, {@code classpath:} or
 *                            {@code file:}
 * @param googlePlayAudience  the Google Play receiver's required OIDC
 *                            audience -- the Pub/Sub push
 *                            subscription is configured to mint a
 *                            Google-signed JWT with this audience
 *                            (the webhook endpoint), and the receiver
 *                            verifies signature, issuer, audience,
 *                            and freshness against Google's public
 *                            JWKS. Empty (the default) keeps the
 *                            receiver off
 * @param googlePlayTrustedEmails the optional service-account email
 *                            allowlist for the same JWT (the push
 *                            subscription's identity); empty accepts
 *                            any Google-signed token with the right
 *                            audience -- the audience is the unique
 *                            endpoint URL, which already scopes the
 *                            sender to whoever configured the
 *                            subscription
 * @param googlePlayJwksUri   where Google's public signing keys are
 *                            fetched from (cached; the default is
 *                            Google's documented JWKS endpoint --
 *                            overridable so deployments can pin a
 *                            mirror and tests can serve fixtures)
 * @param maxTimestampSkew    how far a provider-stamped timestamp may
 *                            sit from now before the delivery is a
 *                            replay (S12-04's receivers apply it:
 *                            Jenkins buildTimestamp, GitLab pipeline
 *                            timestamps, Apple signedDate, Google
 *                            publishTime/JWT iat; GitHub sends none,
 *                            so its deliveries never enter the window
 *                            and its replay defense is the
 *                            delivery-id ledger alone)
 * @param maxBodyBytes        the inbound payload ceiling -- a delivery
 *                            beyond it answers 413 before its bytes
 *                            are parsed (GitHub caps its own webhook
 *                            payloads at 25 MB; the default matches
 *                            that ceiling)
 */
@ConfigurationProperties(prefix = "shipperdog.webhooks")
public record InboundWebhookProperties(
        @DefaultValue List<String> githubSecrets,
        @DefaultValue List<String> jenkinsSecrets,
        @DefaultValue List<String> gitlabSecrets,
        @DefaultValue List<String> appstoreBundleIds,

        @DefaultValue({"classpath:webhooks/apple-root-ca-g3.cer"})
        List<String> appstoreTrustedRoots,

        @DefaultValue String googlePlayAudience,
        @DefaultValue List<String> googlePlayTrustedEmails,

        @DefaultValue("https://www.googleapis.com/oauth2/v3/certs")
        String googlePlayJwksUri,

        @DefaultValue("PT5M") Duration maxTimestampSkew,
        @DefaultValue("26214400") long maxBodyBytes) {

    /** The default body ceiling: GitHub's own 25 MB webhook payload cap. */
    public static final long DEFAULT_MAX_BODY_BYTES = 26_214_400L;
}
