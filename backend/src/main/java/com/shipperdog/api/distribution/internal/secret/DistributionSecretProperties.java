package com.shipperdog.api.distribution.internal.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The secret-store selection and its two implementations' knobs
 * (issue #61, S13-01, PRD 25, ADR-0008): which store backs the
 * platform's secret references, and the facts each posture needs.
 * Environment-configurable per the platform's standing ({@code
 * shipperdog.secrets.*} in application.yml, {@code SECRETS_*} in the
 * deployment env), with the OSS envelope store as the default a
 * compose stack runs on unchanged.
 *
 * @param provider the store selection: {@code envelope} (default; the
 *                 self-hostable AES-256-GCM envelope store, KEK from
 *                 the deployment environment) or {@code
 *                 gcp-secret-manager} (the production posture of PRD
 *                 25: GCP Secret Manager plus Cloud KMS)
 * @param envelope the envelope store's single knob: the KEK (Base64
 *                 of 32 raw key bytes). No committed default: a blank
 *                 KEK mints an EPHEMERAL process-local key with a
 *                 loud warning -- acceptable for a local stack whose
 *                 secrets are re-bindable, never for production (a
 *                 restart would stop every reference resolving; the
 *                 deployment documentation says so)
 * @param gcp      the GCP store's facts: the project that owns the
 *                 secrets and an optional endpoint override (tests
 *                 serve fixtures; production talks to
 *                 secretmanager.googleapis.com with application
 *                 default credentials)
 */
@ConfigurationProperties(prefix = "shipperdog.secrets")
public record DistributionSecretProperties(
        @DefaultValue("envelope") Provider provider,
        @DefaultValue Envelope envelope,
        @DefaultValue Gcp gcp) {

    /** The two postures of ADR-0008. */
    public enum Provider {
        ENVELOPE,
        GCP_SECRET_MANAGER
    }

    /** The envelope store's knobs. */
    public record Envelope(@DefaultValue String kek) {}

    /** The GCP Secret Manager store's knobs. */
    public record Gcp(
            @DefaultValue String projectId, @DefaultValue String endpoint) {}
}
