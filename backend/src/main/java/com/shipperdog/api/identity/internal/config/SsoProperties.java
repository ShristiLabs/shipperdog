package com.shipperdog.api.identity.internal.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code shipperdog.sso.*} (S14-01, issue #67): the OIDC relying
 * party's deployment posture -- where this API's own callback lives
 * (the redirect_uri registered with every identity provider), where
 * the console expects its SSO landing route, and the flow-state TTLs
 * and cache windows.
 *
 * @param redirectBaseUri     this API's externally reachable base; the
 *                            callback redirect_uri is
 *                            {@code <base>/api/v1/auth/sso/callback}
 * @param consoleCallbackUri  the console SPA route the callback
 *                            redirects the browser to with the
 *                            one-time session code
 * @param authorizationRequestTtl how long an authorization request
 *                            (state/nonce/PKCE verifier) stays usable
 * @param sessionCodeTtl      how long the one-time session code stays
 *                            exchangeable
 * @param discoveryCacheTtl   how long a fetched OIDC discovery
 *                            document stays authoritative
 * @param jwksCacheTtl        how long a fetched JWKS stays
 *                            authoritative between refreshes
 * @param clockSkew           the verification tolerance for ID-token
 *                            time claims
 */
@ConfigurationProperties("shipperdog.sso")
public record SsoProperties(
        @DefaultValue("http://localhost:8080") URI redirectBaseUri,
        @DefaultValue("http://localhost:5173/sso/callback") URI consoleCallbackUri,
        @DefaultValue("PT10M") Duration authorizationRequestTtl,
        @DefaultValue("PT60S") Duration sessionCodeTtl,
        @DefaultValue("PT10M") Duration discoveryCacheTtl,
        @DefaultValue("PT10M") Duration jwksCacheTtl,
        @DefaultValue("PT1M") Duration clockSkew) {}
