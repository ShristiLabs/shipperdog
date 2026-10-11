package com.shipperdog.api.identity.internal.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code shipperdog.sso.*} (S14-01, issue #67; SAML joined S14-02):
 * the SSO deployment posture -- where this API's own callbacks live
 * (the OIDC redirect_uri registered with every provider, the per-org
 * SAML entityID/ACS routes), where the console expects its SSO
 * landing route, and the flow-state TTLs, cache windows, and the
 * optional SAML SP signing pair.
 *
 * @param redirectBaseUri     this API's externally reachable base; the
 *                            OIDC callback redirect_uri is
 *                            {@code <base>/api/v1/auth/sso/callback}
 *                            and the SAML SP entityID/ACS live under
 *                            {@code <base>/api/v1/auth/sso/saml/...}
 * @param consoleCallbackUri  the console SPA route every SSO callback
 *                            redirects the browser to with the
 *                            one-time session code
 * @param authorizationRequestTtl how long an authorization request
 *                            (OIDC state/nonce/PKCE verifier, SAML
 *                            AuthnRequest ID/RelayState) stays usable
 * @param sessionCodeTtl      how long the one-time session code stays
 *                            exchangeable
 * @param discoveryCacheTtl   how long a fetched OIDC discovery
 *                            document stays authoritative
 * @param jwksCacheTtl        how long a fetched JWKS stays
 *                            authoritative between refreshes
 * @param clockSkew           the verification tolerance for ID-token
 *                            and SAML assertion time claims
 * @param samlSpSigningKey    the deployment's optional SAML SP signing
 *                            private key (PKCS#8 PEM, environment);
 *                            empty means unsigned AuthnRequests and an
 *                            SP metadata without a signing KeyDescriptor
 *                            -- accepted by Entra, Okta, and Workspace
 * @param samlSpSigningCert   the SP signing pair's X.509 certificate
 *                            (PEM); must arrive together with the key
 */
@ConfigurationProperties("shipperdog.sso")
public record SsoProperties(
        @DefaultValue("http://localhost:8080") URI redirectBaseUri,
        @DefaultValue("http://localhost:5173/sso/callback") URI consoleCallbackUri,
        @DefaultValue("PT10M") Duration authorizationRequestTtl,
        @DefaultValue("PT60S") Duration sessionCodeTtl,
        @DefaultValue("PT10M") Duration discoveryCacheTtl,
        @DefaultValue("PT10M") Duration jwksCacheTtl,
        @DefaultValue("PT1M") Duration clockSkew,
        @DefaultValue("") String samlSpSigningKey,
        @DefaultValue("") String samlSpSigningCert) {}
