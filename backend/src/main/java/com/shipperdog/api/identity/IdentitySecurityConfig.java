package com.shipperdog.api.identity;

import com.shipperdog.api.analytics.TelemetryTokenAuthenticationFilter;
import com.shipperdog.api.analytics.TelemetryTokenCodec;
import com.shipperdog.api.config.ProblemJsonSecurityHandlers;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.service.TokenService;
import com.shipperdog.api.identity.internal.web.PatAuthenticationFilter;
import com.shipperdog.api.otabundle.OtaAppTokenAuthenticationFilter;
import com.shipperdog.api.otabundle.OtaAppTokenCodec;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security chain for the API surface ({@code /api/v1/**}): stateless,
 * CSRF-free (bearer tokens only), JWT-authenticated via Spring's resource
 * server, with personal access tokens handled by a dedicated filter.
 * Refresh-type JWTs are rejected as bearer credentials; only
 * {@code type=access} tokens authenticate. The chain is shaped so an OIDC
 * provider can be added as another credential source without touching
 * callers (S14-01): the SSO flow's browser legs are permitted here and
 * authenticated by their own one-time state and token verification.
 */
@Configuration
public class IdentitySecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            TokenService tokens,
            ProblemJsonSecurityHandlers problemHandlers,
            ObjectProvider<TelemetryTokenAuthenticationFilter> telemetryTokens,
            ObjectProvider<OtaAppTokenAuthenticationFilter> otaAppTokens)
            throws Exception {
        PatAuthenticationFilter patFilter = new PatAuthenticationFilter(tokens);
        http.securityMatcher("/api/v1/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/password-reset/request",
                                "/api/v1/auth/password-reset/confirm",
                                "/api/v1/auth/sso/exchange",
                                "/api/v1/auth/sso/logout")
                        .permitAll()
                        // The OIDC SSO flow's browser legs (S14-01):
                        // the probe, the authorization redirect, and
                        // the provider callback. Anonymous by design --
                        // the flow's one-time state and the ID token's
                        // cryptographic verification are the authority,
                        // and every failure leg is uniform. The SAML
                        // flow's legs (S14-02) share the doctrine: the
                        // per-organization SP metadata document is
                        // public registry material, and the ACS is the
                        // signed-response receiver whose validation
                        // against pinned IdP metadata IS the authority.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/auth/sso/options",
                                "/api/v1/auth/sso/authorize",
                                "/api/v1/auth/sso/callback",
                                "/api/v1/auth/sso/saml/*/metadata")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/sso/saml/*/acs")
                        .permitAll()
                        // The emulator-mode download proxy (issue #38, S8-04): the
                        // HMAC token in the path IS the credential — an engine ALLOW
                        // minted it at issuance — so the fetch is anonymous by design
                        // and every invalid, tampered, or expired token answers the
                        // controller's uniform opaque 404 (never an authentication
                        // hint). Production deployments mint GCS V4 URLs instead and
                        // never hand a client this route; the signature guard is the
                        // entire authority either way.
                        .requestMatchers(HttpMethod.GET, "/api/v1/downloads/*")
                        .permitAll()
                        // The OTA device check (S16-07, issue #169, ADR-0010
                        // D3/D4): anonymous at the chain exactly like the
                        // download proxy — the Bearer sdc1. prefix is the
                        // credential, and the otabundle module's filter
                        // (registered above) resolves it against the hashed
                        // registry with immediate revocation. The route's
                        // method security carries the one-authority guard, so
                        // a missing, invalid, or revoked token answers the
                        // uniform 401 and an authenticated-but-wrong
                        // credential the 403 — never a hint of what exists.
                        .requestMatchers(HttpMethod.GET, "/api/v1/ota/check")
                        .permitAll()
                        // Spring routes unhandled exceptions to /error via an ERROR
                        // dispatch that passes through this chain; without the permit a
                        // server fault would surface as a bare 401 instead of a 5xx.
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(patFilter, BearerTokenAuthenticationFilter.class);
        // The tester telemetry SDK's ingest credential (issue
        // #72, S15-01): the sdt1. prefix is authenticated by
        // the analytics module's filter, minted by the S8-04
        // download issuance — the PAT filter's exact doctrine,
        // one authority (analytics:telemetry:ingest), nothing
        // else honors it. ObjectProvider keeps identity
        // decoupled from the analytics module's presence for
        // sliced test contexts — and where the bean is absent
        // there is no filter to add (a null registration would
        // NPE when the chain builds).
        TelemetryTokenAuthenticationFilter telemetryFilter = telemetryTokens.getIfAvailable();
        if (telemetryFilter != null) {
            http.addFilterBefore(telemetryFilter, BearerTokenAuthenticationFilter.class);
        }
        // The shipped app's OTA device identifier (issue #166,
        // S16-04, ADR-0010 D3): the sdc1. prefix is authenticated by
        // the otabundle module's filter against the hashed registry
        // (revocation is immediate), one authority
        // (ota:app-token:check) that nothing honors until the device
        // check lands (S16-07). The same ObjectProvider decoupling as
        // the telemetry filter above.
        OtaAppTokenAuthenticationFilter otaAppTokenFilter = otaAppTokens.getIfAvailable();
        if (otaAppTokenFilter != null) {
            http.addFilterBefore(otaAppTokenFilter, BearerTokenAuthenticationFilter.class);
        }
        // The API-surface security headers (issue #39, S8-05):
        // HSTS, no-referrer, empty Permissions-Policy, and a
        // default-src 'none' CSP — the same posture the baseline
        // chain applies below /api/v1 and the origins carry.
        http.headers(com.shipperdog.api.config.SecurityConfig.ApiSecurityHeaders::apply)
                .oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(patAwareBearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(accessTokenOnly())))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /**
     * Keeps the resource server away from personal access tokens,
     * telemetry tokens, and OTA app tokens: the {@code sdp_},
     * {@code sdt1.}, and {@code sdc1.} prefixes belong to their own
     * filters, and the default resolver must not attempt (and fail)
     * JWT decoding for them.
     */
    private static BearerTokenResolver patAwareBearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null
                    && (header.startsWith("Bearer " + SecureTokens.PAT_PREFIX)
                            || header.startsWith("Bearer " + TelemetryTokenCodec.TOKEN_PREFIX)
                            || header.startsWith("Bearer " + OtaAppTokenCodec.TOKEN_PREFIX))) {
                return null;
            }
            return delegate.resolve(request);
        };
    }

    /**
     * Rejects refresh-token JWTs presented as access credentials: only
     * {@code type=access} tokens may authenticate API requests.
     */
    private static Converter<Jwt, ? extends AbstractAuthenticationToken> accessTokenOnly() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return jwt -> {
            if (!TokenService.TYPE_ACCESS.equals(jwt.getClaimAsString(TokenService.CLAIM_TYPE))) {
                throw new InvalidBearerTokenException("token is not an access token");
            }
            return delegate.convert(jwt);
        };
    }
}
