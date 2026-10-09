package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.notification.NotificationException;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The webhook target's SSRF policy (issue #51, S12-01 review): a
 * subscription's URL is an address this service will POST to from
 * inside its own network, so it must name a publicly routable http(s)
 * host -- never the loopback, link-local (the cloud metadata
 * endpoints), private, or unique-local ranges, and never a host that
 * does not resolve. The check runs at create and at every edit (the
 * standing URL re-validates through the same path), and refuses with
 * the subscription surface's field-addressed 422.
 *
 * <p>Known limitation, deliberate for V1.5: the check resolves at
 * validation time, not again at POST time, so a host whose DNS later
 * re-points into a private range (rebinding) is behind this policy's
 * horizon -- documented in docs/notifications.md with the broker-era
 * follow-up. Redirects are not followed by the delivery client
 * either, so a receiver cannot bounce a delivery inward.
 *
 * <p>{@code webhook-allow-private-addresses} (default false) skips
 * the host and resolution checks, syntax and length still enforced:
 * the test suites' local receivers and fixture hosts run under it --
 * production never should.
 */
@Component
class WebhookTargetPolicy {

    /** The accepted URL ceiling (the delivery ledger's recipient key is sized to it). */
    static final int MAX_URL_LENGTH = 2048;

    /** The production resolver. Package-private seam for the policy's own tests. */
    static final HostResolver DNS_RESOLVER = InetAddress::getAllByName;

    /** The resolution seam: the name-to-address question, answerable offline in tests. */
    @FunctionalInterface
    interface HostResolver {

        /** Every address the name resolves to, or none (unknown). */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final NotificationProperties properties;

    WebhookTargetPolicy(NotificationProperties properties) {
        this.properties = properties;
    }

    /**
     * Validates the URL as a webhook target, refusing with the
     * field-addressed 422 ({@code webhookUrl}) on any offense.
     */
    void requireRoutable(String url) {
        requireRoutable(url, DNS_RESOLVER);
    }

    /** The validation with an injected resolver (the unit tests' determinism). */
    void requireRoutable(String url, HostResolver resolver) {
        if (url.length() > MAX_URL_LENGTH) {
            reject("at most " + MAX_URL_LENGTH + " characters");
        }
        URI parsed;
        try {
            parsed = new URI(url.trim());
        } catch (URISyntaxException e) {
            reject("a well-formed http(s) URL");
            return;
        }
        String scheme = parsed.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            reject("a http(s) URL");
        }
        if (parsed.getRawUserInfo() != null) {
            reject("a URL without embedded credentials");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            reject("a URL with a host");
        }
        if (properties.webhookAllowPrivateAddresses()) {
            return;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost") || normalized.endsWith(".internal")) {
            reject("a publicly routable host");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            reject("a host that resolves");
            return;
        }
        for (InetAddress address : addresses) {
            if (!routable(address)) {
                reject(
                        "a publicly routable host (it resolves into a loopback, link-local, private, or unique-local address)");
                return;
            }
        }
    }

    /**
     * Whether the address is one this service may POST to: anything
     * but the any-local, loopback, link-local (169.254.0.0/16 and
     * fe80::/10 -- the cloud metadata endpoints live here), site-local
     * (10/8, 172.16/12, 192.168/16, fec0::/10), and IPv6 unique-local
     * (fc00::/7) ranges.
     */
    private static boolean routable(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return false;
        }
        if (address.isSiteLocalAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] octets = address.getAddress();
            if ((octets[0] & 0xfe) == 0xfc) {
                return false;
            }
        }
        return true;
    }

    private static void reject(String requirement) {
        throw new NotificationException(
                NotificationException.Code.SUBSCRIPTION_INVALID,
                "the webhook target is not acceptable",
                Map.of("webhookUrl", "must be " + requirement));
    }
}
