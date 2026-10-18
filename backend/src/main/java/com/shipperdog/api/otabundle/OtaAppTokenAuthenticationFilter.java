package com.shipperdog.api.otabundle;

import com.shipperdog.api.otabundle.internal.service.OtaAppTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates bearer credentials that carry a ShipperDog OTA app
 * token ({@code sdc1....}) — the shipped app's device identifier
 * (issue #166, S16-04, ADR-0010 D3), minted per application from the
 * console/CLI. The filter only handles that prefix, exactly the {@code
 * TelemetryTokenAuthenticationFilter} doctrine: every other bearer
 * token (JWTs, PATs, {@code sdt1.}) falls through untouched to its own
 * path, and the authentication this filter establishes carries exactly
 * ONE authority — {@code ota:app-token:check} — the narrow capability
 * the name promises. No surface honors it until the device check
 * lands (S16-07, {@code GET /api/v1/ota/check}); the wiring is in
 * place now so that endpoint arrives as a route, not a security
 * change.
 *
 * <p>Unlike the stateless telemetry parse, this filter resolves
 * against the hashed registry on EVERY request (SHA-256 lookup, V55's
 * {@code ota_app_tokens}): revocation is a row state, so a revoked
 * token stops authenticating the moment {@code revoked_at} lands —
 * the issue's immediate-revocation acceptance criterion. A parse that
 * succeeds but names no row, a revoked row, or a row whose ids
 * disagree with the token's claims is the same uniform 401.
 *
 * <p>Like the codec and the principal, this class sits at the module's
 * public root rather than in {@code internal}: the identity module's
 * security chain consumes the filter bean (it must register INSIDE the
 * chain, where a set security context survives), and the ArchUnit
 * module-boundary rule forbids cross-module {@code .internal.}
 * imports — the root is the otabundle module's published surface.
 *
 * <p>An invalid, tampered, revoked, or unknown token answers the
 * uniform 401 with no reason — the caller learns neither which check
 * failed nor that anything exists.
 */
public final class OtaAppTokenAuthenticationFilter extends OncePerRequestFilter {

    /** The one authority an app token carries (the check route's guard, S16-07). */
    public static final String CHECK_AUTHORITY = "ota:app-token:check";

    private final OtaAppTokenService tokens;

    public OtaAppTokenAuthenticationFilter(OtaAppTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer " + OtaAppTokenCodec.TOKEN_PREFIX)) {
                OtaAppTokenPrincipal principal =
                        tokens.resolve(header.substring("Bearer ".length())).orElse(null);
                if (principal == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    return;
                }
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(CHECK_AUTHORITY)));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
