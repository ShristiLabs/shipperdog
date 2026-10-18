package com.shipperdog.api.otabundle.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The OTA app token's configuration (issue #166, S16-04, ADR-0010
 * D3): environment-only per the platform profile — {@code
 * application.yml} reads the {@code OTA_APP_TOKEN_KEY} variable and
 * supplies the compose default.
 *
 * <p>No TTL property by design: the {@code sdc1.} token is an
 * identifier, not a secret (D3) — its lifetime is the registry row's
 * ({@code revoked_at}), never a clock's, because the token is
 * embedded in shipped binaries that cannot be re-issued.
 *
 * @param appTokenKey  the HMAC-SHA256 key behind every minted
 *                     {@code sdc1.} app token. Local default is a
 *                     dev-only value exactly like the JWT secret's
 *                     local default; production supplies a real
 *                     secret through the environment and nothing
 *                     secret is committed. Rotating the key is the
 *                     break-glass revocation of EVERY minted app
 *                     token (the codec's documented lever) — the
 *                     same posture as the telemetry and download-proxy
 *                     keys, on its own key so rotating one credential
 *                     family never retires another's.
 */
@ConfigurationProperties(prefix = "shipperdog.ota")
public record OtaAppTokenProperties(String appTokenKey) {}
