package com.shipperdog.api.otabundle;

import java.time.Instant;
import java.util.UUID;

/**
 * One committed OTA alert firing, published in-process (S16-11, issue
 * #173, ADR-0010 D10): the evaluation scheduler observed a rollup
 * metric crossing a persisted rule's threshold and landed the {@code
 * ota_alert_firings} row -- one event per firing, in the SAME
 * transaction that committed the row (the {@code
 * OtaRolloutTransitionedEvent} doctrine). The S12-01 outbox recorder
 * maps it to {@code OTA_ALERT_FIRED} on the bus (ADR-0007) so webhook
 * and chat consumers learn a fleet is degrading the moment the
 * scheduler sees it.
 *
 * <p><strong>Nothing halts on this event</strong> (D10): the firing
 * surfaces the degradation -- the console badge, the feed, the bus --
 * and halting stays the manual, audit-trailed act an operator drives
 * through the S16-06 endpoints. There is no resolution event: the
 * metric's recovery stamps the standing row's {@code resolved_at},
 * and the feed, never the bus, carries it.
 *
 * <p>The {@code ArtifactValidatedEvent} discipline: self-contained
 * facts, published inside the terminal write's transaction through
 * Spring's {@code ApplicationEventPublisher}.
 *
 * @param ruleId        the rule that fired
 * @param firingId      the firing row the event rides
 * @param applicationId the rule's application (the firing's scope)
 * @param organizationId the owning organization (the rule's own grain)
 * @param otaBundleId   the bundle the rule watches, when it watches one
 * @param metric        the dictionary metric that crossed
 * @param comparator    the rule's comparator (GT, GTE, LT, LTE)
 * @param threshold     the rule's threshold
 * @param observedValue the value the window observed
 * @param severity      the rule's severity (INFO, WARNING, CRITICAL)
 * @param detail        the machine-readable context (the window's
 *                      arithmetic), bounded, never secret material
 * @param occurredAt    the firing's moment
 */
public record OtaAlertFiredEvent(
        UUID ruleId,
        UUID firingId,
        UUID applicationId,
        UUID organizationId,
        UUID otaBundleId,
        String metric,
        String comparator,
        double threshold,
        double observedValue,
        String severity,
        String detail,
        Instant occurredAt) {}
