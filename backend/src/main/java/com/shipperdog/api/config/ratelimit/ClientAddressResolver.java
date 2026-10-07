package com.shipperdog.api.config.ratelimit;

import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Resolves the client IP a rate-limit bucket is keyed on (issue #39,
 * S8-05). Behind a proxy the socket address is the proxy's, so the
 * {@code X-Forwarded-For} chain is the only usable identity — but a
 * caller can PREPEND forged entries to it. Each trusted proxy appends
 * the address it actually saw, so the rightmost
 * {@code trustedProxyCount} entries are the chain our own proxies
 * observed, and the client is the entry that opens that window:
 * position {@code size - trustedProxyCount}. Everything further left
 * is caller-supplied and never read. Compose's nginx appends
 * ($proxy_add_x_forwarded_for) and Cloud Run's fronting proxy
 * supplies the client address the same way, so the default depth of
 * one is correct for both shipped topologies.
 *
 * <p>The chain is read from the ORIGINAL request, unwrapped from any
 * {@link ServletRequestWrapper} layers first: a forwarded-header
 * strategy (Spring's ForwardedHeaderFilter) wraps the request,
 * resolves the LEFTMOST — caller-claimable — entry into
 * {@code getRemoteAddr()}, and removes the header from view, which
 * would quietly turn the limiter's trusted-side arithmetic back into
 * first-entry keying. Unwrapping makes the resolver's answer
 * independent of where in the filter chain it runs.
 *
 * <p>This is the same trust reasoning the audit trail applies to
 * attribution ({@code AuditRequestContext}, which keeps the raw first
 * entry because audit wants the claimed origin too); the rate limiter
 * instead needs an identity a single client cannot multiply, so it
 * resolves from the trusted side only.
 */
final class ClientAddressResolver {

    private final int trustedProxyCount;

    ClientAddressResolver(int trustedProxyCount) {
        this.trustedProxyCount = trustedProxyCount;
    }

    /** The rate-limit client identity for this request. */
    String clientOf(HttpServletRequest request) {
        HttpServletRequest original = unwrap(request);
        String forwarded = original.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return original.getRemoteAddr();
        }
        List<String> entries = List.of(forwarded.trim().split("\\s*,\\s*"));
        int clientIndex = entries.size() - trustedProxyCount;
        if (clientIndex < 0) {
            // The chain is shorter than the configured trust depth:
            // every entry was appended by a trusted proxy, so the
            // leftmost is the directly connected client.
            clientIndex = 0;
        }
        return entries.get(clientIndex);
    }

    /** Peels wrapper layers (forwarded-header, security) back to the container's request. */
    private static HttpServletRequest unwrap(HttpServletRequest request) {
        while (request instanceof ServletRequestWrapper wrapper) {
            if (!(wrapper.getRequest() instanceof HttpServletRequest parent)) {
                break;
            }
            request = parent;
        }
        return request;
    }
}
