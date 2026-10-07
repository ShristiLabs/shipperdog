package com.shipperdog.api.identity;

import com.shipperdog.api.config.ProblemJsonSecurityHandlers;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.service.TokenService;
import com.shipperdog.api.identity.internal.web.PatAuthenticationFilter;
import java.util.List;
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
 * provider can later be added as another credential source without
 * touching callers (S14-01).
 */
@Configuration
public class IdentitySecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JwtDecoder jwtDecoder, TokenService tokens, ProblemJsonSecurityHandlers problemHandlers)
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
                                "/api/v1/auth/password-reset/confirm")
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
                        // Spring routes unhandled exceptions to /error via an ERROR
                        // dispatch that passes through this chain; without the permit a
                        // server fault would surface as a bare 401 instead of a 5xx.
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(patFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.bearerTokenResolver(patAwareBearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(accessTokenOnly())))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(problemHandlers).accessDeniedHandler(problemHandlers));
        return http.build();
    }

    /**
     * Keeps the resource server away from personal access tokens: the
     * {@code sdp_} prefix belongs to the PAT filter, and the default
     * resolver must not attempt (and fail) JWT decoding for it.
     */
    private static BearerTokenResolver patAwareBearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer " + SecureTokens.PAT_PREFIX)) {
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
