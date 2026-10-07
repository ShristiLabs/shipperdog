package com.shipperdog.api.artifact.internal.download;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The download tier's configuration (issue #38, S8-04, FR-014):
 * environment-only per the platform profile — {@code application.yml}
 * reads the {@code ARTIFACT_DOWNLOAD_*} variables and supplies compose
 * defaults.
 *
 * @param urlTtl the signed download URL's lifetime (FR-014: short-lived;
 *     default 15 minutes). Bounded twice, fail-fast at startup by the
 *     download service: at or under the authorization decision-input
 *     cache TTL ({@code shipperdog.authorization-cache.ttl}, the S7-03/S7-04
 *     rule — write-through invalidation makes revocation immediate in
 *     the healthy path, and the cache TTL is the degraded-mode backstop
 *     a URL must never outlive) and at or under the hard 15-minute
 *     ceiling the story's acceptance criteria name.
 * @param proxySigningKey the HMAC-SHA256 key behind the emulator-mode
 *     proxy URL (local/CI only — the ADR-0003 download-tier amendment's
 *     documented fallback; blank in production mode, where GCS V4
 *     signing is the mechanism and this key is never read). Local
 *     default is a dev-only value exactly like the JWT secret's local
 *     default; production supplies a real secret through the
 *     environment and nothing secret is committed.
 */
@ConfigurationProperties(prefix = "shipperdog.artifact.download")
public record ArtifactDownloadProperties(Duration urlTtl, String proxySigningKey) {

    /** The hard ceiling of FR-014's "short-lived" (the story's own bound). */
    public static final Duration MAX_URL_TTL = Duration.ofMinutes(15);

    public ArtifactDownloadProperties {
        if (urlTtl == null || urlTtl.isZero() || urlTtl.isNegative()) {
            throw new IllegalArgumentException("shipperdog.artifact.download.url-ttl must be a positive duration");
        }
    }
}
