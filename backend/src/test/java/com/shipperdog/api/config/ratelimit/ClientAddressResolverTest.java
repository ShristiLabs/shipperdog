package com.shipperdog.api.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * The X-Forwarded-For trust arithmetic of the rate limiter's client
 * resolver (issue #39, S8-05): entries appended by trusted proxies
 * are the identity; caller-supplied prefixes are never read.
 */
class ClientAddressResolverTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void noForwardedHeaderFallsBackToSocketAddress() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        assertThat(new ClientAddressResolver(1).clientOf(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void oneTrustedProxyTakesTheEntryItAppended() {
        // nginx appended the real client: the last entry.
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");
        assertThat(new ClientAddressResolver(1).clientOf(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void forgedPrefixesCannotMintNewIdentities() {
        // The attacker controls everything left of the trusted
        // append; every variation below is the SAME client.
        when(request.getHeader("X-Forwarded-For"))
                .thenReturn("spoof-a, 198.51.100.7")
                .thenReturn("spoof-b, spoof-c, 198.51.100.7")
                .thenReturn("spoof-d, spoof-e, spoof-f, 198.51.100.7");
        ClientAddressResolver resolver = new ClientAddressResolver(1);
        assertThat(resolver.clientOf(request)).isEqualTo("198.51.100.7");
        assertThat(resolver.clientOf(request)).isEqualTo("198.51.100.7");
        assertThat(resolver.clientOf(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void deeperTrustDepthResolvesThroughTheChainItObserved() {
        // client -> p1 (appends client) -> p2 (appends p1) -> api,
        // trusted depth 2: the trusted observations are the last two
        // entries; the client opens that window.
        when(request.getHeader("X-Forwarded-For")).thenReturn("claim, 203.0.113.9, 10.0.0.2");
        assertThat(new ClientAddressResolver(2).clientOf(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void chainShorterThanTrustDepthTakesTheLeftmost() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7");
        assertThat(new ClientAddressResolver(2).clientOf(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void blankHeaderFallsBackToSocketAddress() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("  ");
        when(request.getRemoteAddr()).thenReturn("192.0.2.11");
        assertThat(new ClientAddressResolver(1).clientOf(request)).isEqualTo("192.0.2.11");
    }
}
