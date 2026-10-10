package com.shipperdog.api.notification.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.shipperdog.api.notification.NotificationException;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import com.shipperdog.api.notification.internal.service.WebhookTargetPolicy.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The webhook target policy's own unit (issue #51, S12-01 review):
 * every range and syntax rule against an injected resolver -- no DNS,
 * no container, fully deterministic. The through-the-API rejections
 * live in {@code NotificationWebhookTargetPolicyIntegrationTest};
 * this class proves the address math itself.
 */
class WebhookTargetPolicyTest {

    /** A stub resolver: names it has answers for, everything else unknown. */
    private static final HostResolver STUB_RESOLVER = host -> {
        try {
            return switch (host) {
                case "public.example.test" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                case "multi.example.test" ->
                    new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.1.2.3")};
                case "ula.example.test" -> new InetAddress[] {InetAddress.getByName("fd12:3456:789a::1")};
                case "v6pub.example.test" -> new InetAddress[] {InetAddress.getByName("2606:2800:220:1::1")};
                default -> throw new UnknownHostException(host);
            };
        } catch (UnknownHostException unresolved) {
            throw unresolved;
        }
    };

    private static NotificationProperties properties(boolean allowPrivate) {
        return new NotificationProperties(
                true,
                Duration.ofSeconds(2),
                100,
                5,
                Duration.ofSeconds(2),
                5,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                allowPrivate);
    }

    private static NotificationException refused(String url, boolean allowPrivate) {
        try {
            new WebhookTargetPolicy(properties(allowPrivate)).requireRoutable(url, STUB_RESOLVER);
        } catch (NotificationException e) {
            return e;
        }
        return null;
    }

    @Test
    void aPublicHttpHostIsAccepted() {
        assertThatCode(() -> new WebhookTargetPolicy(properties(false))
                        .requireRoutable("http://public.example.test/hook", STUB_RESOLVER))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WebhookTargetPolicy(properties(false))
                        .requireRoutable("https://public.example.test/hook", STUB_RESOLVER))
                .doesNotThrowAnyException();
    }

    @Test
    void aPublicIpv6HostIsAccepted() {
        assertThatCode(() -> new WebhookTargetPolicy(properties(false))
                        .requireRoutable("https://v6pub.example.test/h", STUB_RESOLVER))
                .doesNotThrowAnyException();
    }

    @Test
    void thePolicyRefusesLiteralInternalTargets() {
        assertThat(refused("http://127.0.0.1:8080/hook", false)).isNotNull();
        assertThat(refused("http://10.0.0.5/hook", false)).isNotNull();
        assertThat(refused("http://172.16.1.1/hook", false)).isNotNull();
        assertThat(refused("http://192.168.1.10/hook", false)).isNotNull();
        assertThat(refused("http://169.254.169.254/computeMetadata/v1", false)).isNotNull();
        assertThat(refused("http://[::1]:8080/hook", false)).isNotNull();
        assertThat(refused("http://0.0.0.0/hook", false)).isNotNull();
    }

    @Test
    void thePolicyRefusesReservedNamesWithoutDns() {
        // The name rules fire before resolution: deterministic offline.
        assertThat(refused("http://localhost:8080/hook", false)).isNotNull();
        assertThat(refused("http://metadata.google.internal/computeMetadata/v1", false))
                .isNotNull();
        assertThat(refused("http://printer.internal/hook", false)).isNotNull();
    }

    @Test
    void thePolicyRefusesUnresolvableAndMixedHosts() {
        // A host with no DNS answer at all.
        assertThat(refused("https://nothing-here.example.invalid/hook", false)).isNotNull();
        // A host whose record set contains a private address alongside
        // a public one: any internal answer refuses the target.
        assertThat(refused("https://multi.example.test/hook", false)).isNotNull();
        // A unique-local IPv6 answer (fc00::/7; fd12::/... is inside).
        assertThat(refused("https://ula.example.test/hook", false)).isNotNull();
    }

    @Test
    void theSyntaxRulesHoldEvenWithPrivateTargetsAllowed() {
        // The escape hatch skips host/DNS checks only: scheme,
        // credentials, host presence, and length still refuse.
        assertThat(refused("ftp://public.example.test/hook", true)).isNotNull();
        assertThat(refused("https://user:pass@public.example.test/hook", true)).isNotNull();
        assertThat(refused("https:///hook", true)).isNotNull();
        assertThat(refused("https://not a url", true)).isNotNull();
        assertThat(refused("http://public.example.test/hook", false)).isNull();
    }

    @Test
    void anOverLongUrlIsRefusedRegardlessOfTheFlag() {
        String pad = "a".repeat(WebhookTargetPolicy.MAX_URL_LENGTH);
        assertThat(refused("https://public.example.test/" + pad, true)).isNotNull();
        assertThat(refused("https://public.example.test/" + pad, false)).isNotNull();
    }

    @Test
    void theEscapeHatchAcceptsLocalTargets() {
        assertThatCode(() -> new WebhookTargetPolicy(properties(true))
                        .requireRoutable("http://127.0.0.1:9/hook", STUB_RESOLVER))
                .doesNotThrowAnyException();
        assertThatCode(() -> new WebhookTargetPolicy(properties(true))
                        .requireRoutable("http://localhost:9/hook", STUB_RESOLVER))
                .doesNotThrowAnyException();
    }

    @Test
    void refusalsCarryTheFieldAddressed422Shape() {
        NotificationException refusal = refused("http://10.0.0.5/hook", false);
        assertThat(refusal).isNotNull();
        assertThat(refusal.code()).isEqualTo(NotificationException.Code.SUBSCRIPTION_INVALID);
        assertThat(refusal.getMessage()).contains("webhook target");
    }
}
