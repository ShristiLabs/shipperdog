package com.shipperdog.api.otabundle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The evaluation pass's own wiring (S16-11, issue #173): the
 * scheduler's body is the rollup scheduler's -- the switch, the
 * clock, the pass, one audit line -- and these pins hold exactly
 * that shape. The pass itself (crossings, firings, recoveries) is
 * the S16-11 integration suite's; the comparator arithmetic beside
 * them is the unit below.
 */
class OtaAlertSchedulerTest {

    private final OtaAlertService alerts = mock(OtaAlertService.class);

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private final Clock clock = Clock.systemUTC();

    @Test
    void thePassPublishesOneAuditLineCarryingTheCounts() {
        when(alerts.evaluateAll(any())).thenReturn(new OtaAlertService.EvaluationReport(7, 2, 1));
        OtaAlertScheduler scheduler = new OtaAlertScheduler(alerts, new OtaAlertProperties(true, null), audit, clock);

        scheduler.runPass();

        ArgumentCaptor<AuditEvent> captured = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).publish(captured.capture());
        AuditEvent line = captured.getValue();
        assertThat(line.type()).isEqualTo(AuditEventType.OTA_ALERTS_EVALUATED);
        assertThat(line.detail())
                .containsEntry("rules", "7")
                .containsEntry("fired", "2")
                .containsEntry("resolved", "1");
        assertThat(line.occurredAt()).isCloseTo(Instant.now(clock), within(java.time.Duration.ofSeconds(5)));
    }

    @Test
    void theSwitchOffMeansNoPassAndNoLine() {
        OtaAlertScheduler scheduler = new OtaAlertScheduler(alerts, new OtaAlertProperties(false, null), audit, clock);

        scheduler.runPass();

        verify(alerts, never()).evaluateAll(any());
        verify(audit, never()).publish(any());
    }

    /**
     * The comparator arithmetic (V56's closed vocabulary): GT/GTE/LT/LTE
     * against the threshold, equality the discriminating boundary.
     */
    @Test
    void comparatorsDecideOnTheBoundaryOfEquality() {
        assertThat(OtaAlertService.crosses("GT", 1.0, 1.0)).isFalse();
        assertThat(OtaAlertService.crosses("GTE", 1.0, 1.0)).isTrue();
        assertThat(OtaAlertService.crosses("LT", 1.0, 1.0)).isFalse();
        assertThat(OtaAlertService.crosses("LTE", 1.0, 1.0)).isTrue();
        assertThat(OtaAlertService.crosses("GT", 1.5, 1.0)).isTrue();
        assertThat(OtaAlertService.crosses("LT", 0.5, 1.0)).isTrue();
        // An unknown word compares nothing -- the CRUD never stores
        // one (the V56 CHECK and the validated writes see to it), and
        // the evaluator still refuses to fire on garbage.
        assertThat(OtaAlertService.crosses("BETWEEN", 99.0, 1.0)).isFalse();
    }
}
