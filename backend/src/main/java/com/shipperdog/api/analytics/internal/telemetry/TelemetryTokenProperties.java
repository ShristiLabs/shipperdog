package com.shipperdog.api.analytics.internal.telemetry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The telemetry token's configuration (issue #72, S15-01):
 * environment-only per the platform profile — {@code
 * application.yml} reads the {@code TELEMETRY_TOKEN_*} variables and
 * supplies compose defaults.
 *
 * @param tokenTtl  the telemetry token's lifetime (default 90 days —
 *                  a beta test cycle's honest horizon; the download
 *                  URL's 15-minute TTL is the BINARY's lifetime, this
 *                  is the TELEMETRY credential's). Bounded above by
 *                  the hard ceiling the constructor enforces: a token
 *                  must not outlive a release's plausible test window
 *                  by a season.
 * @param tokenKey  the HMAC-SHA256 key behind every minted telemetry
 *                  token. Local default is a dev-only value exactly
 *                  like the JWT secret's local default; production
 *                  supplies a real secret through the environment and
 *                  nothing secret is committed. Rotating the key is
 *                  the break-glass revocation of EVERY minted token
 *                  (the codec's documented lever).
 */
@ConfigurationProperties(prefix = "shipperdog.telemetry")
public record TelemetryTokenProperties(Duration tokenTtl, String tokenKey) {

    /** The hard ceiling: a telemetry token never lives longer than a year. */
    public static final Duration MAX_TOKEN_TTL = Duration.ofDays(365);

    public TelemetryTokenProperties {
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("shipperdog.telemetry.token-ttl must be a positive duration");
        }
        if (tokenTtl.compareTo(MAX_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(
                    "shipperdog.telemetry.token-ttl (" + tokenTtl + ") exceeds the ceiling of " + MAX_TOKEN_TTL);
        }
    }
}
