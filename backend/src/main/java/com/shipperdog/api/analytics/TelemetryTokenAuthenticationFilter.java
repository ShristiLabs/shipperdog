package com.shipperdog.api.analytics;

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
 * Authenticates bearer credentials that carry a ShipperDog telemetry
 * token ({@code sdt1....}) — the tester SDK's ingest credential
 * (issue #72, S15-01), minted by the S8-04 download issuance. The
 * filter only handles that prefix, exactly the {@code
 * PatAuthenticationFilter} doctrine: every other bearer token (JWTs,
 * PATs) falls through untouched to its own path, and the
 * authentication this filter establishes carries exactly ONE
 * authority — {@code analytics:telemetry:ingest} — the narrow
 * capability the name promises (no other surface honors it).
 *
 * <p>Like the codec and the principal, this class sits at the module's
 * public root rather than in {@code internal}: the identity module's
 * security chain consumes the filter bean (it must register INSIDE
 * the chain, where a set security context survives), and the
 * ArchUnit module-boundary rule forbids cross-module {@code .internal.}
 * imports — the root is the analytics module's published surface.
 *
 * <p>An invalid, tampered, or expired token answers the uniform 401
 * with no reason — the caller learns neither which check failed nor
 * that anything exists.
 */
public final class TelemetryTokenAuthenticationFilter extends OncePerRequestFilter {

    /** The one authority a telemetry token carries (the ingest route's SpEL alternative). */
    public static final String INGEST_AUTHORITY = "analytics:telemetry:ingest";

    private final TelemetryTokenCodec tokens;

    public TelemetryTokenAuthenticationFilter(TelemetryTokenCodec tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer " + TelemetryTokenCodec.TOKEN_PREFIX)) {
                TelemetryTokenPrincipal principal =
                        tokens.parse(header.substring("Bearer ".length())).orElse(null);
                if (principal == null) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    return;
                }
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority(INGEST_AUTHORITY)));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
