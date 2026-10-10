package com.shipperdog.api.notification.internal.bus;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The notification module's knobs (issue #51, S12-01): the outbox
 * relay's schedule and ceilings, and the delivery retry policy every
 * channel shares. Environment-configurable per the platform's
 * standing ({@code shipperdog.notifications.*} in application.yml,
 * {@code NOTIFICATIONS_*} in the deployment env), with local-default
 * values a compose stack runs on unchanged.
 *
 * @param relayEnabled      whether the scheduled drain runs (tests and
 *                          CI drive it through the bean directly; the
 *                          property keeps a node from racing a manual
 *                          drain)
 * @param relayFixedDelay   the drain cadence
 * @param relayBatchSize    rows per drain pass
 * @param relayMaxAttempts  relay attempts before a row is terminally
 *                          FAILED (transport failures only -- consumer
 *                          failures never reach the relay)
 * @param relayBackoff      the exponential base between relay attempts
 * @param deliveryMaxAttempts  delivery attempts (the first plus
 *                          retries) before a delivery is terminally
 *                          FAILED in the log
 * @param deliveryBackoff   the exponential base between delivery
 *                          retries (doubles per attempt)
 * @param webhookTimeout    the webhook POST's connect/read budget
 * @param webhookAllowPrivateAddresses whether the webhook target
 *                          policy skips its host and DNS checks
 *                          (loopback/private/link-local targets and
 *                          non-resolving fixture hosts accepted) --
 *                          the test suites' local receivers; default
 *                          false and production keeps it there
 * @param consoleBaseUrl    the console's externally reachable base
 *                          URL (S12-02): the deep links in Slack and
 *                          Teams messages are built on it -- a
 *                          production deployment sets its real origin
 *                          (CONSOLE_BASE_URL), the default serves the
 *                          local compose stack
 */
@ConfigurationProperties(prefix = "shipperdog.notifications")
public record NotificationProperties(
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("PT2S") Duration relayFixedDelay,
        @DefaultValue("100") int relayBatchSize,
        @DefaultValue("5") int relayMaxAttempts,
        @DefaultValue("PT2S") Duration relayBackoff,
        @DefaultValue("5") int deliveryMaxAttempts,
        @DefaultValue("PT2S") Duration deliveryBackoff,
        @DefaultValue("PT5S") Duration webhookTimeout,
        @DefaultValue("false") boolean webhookAllowPrivateAddresses,
        @DefaultValue("http://localhost:5173") String consoleBaseUrl) {

    /** The next relay attempt's delay: base doubled per attempt already made. */
    public Duration relayBackoff(int attempts) {
        return relayBackoff.multipliedBy(1L << Math.min(attempts, 16));
    }

    /** The next delivery retry's delay: base doubled per attempt already made. */
    public Duration deliveryBackoff(int attempts) {
        return deliveryBackoff.multipliedBy(1L << Math.min(attempts, 16));
    }
}
