package com.shipperdog.api.distribution;

import java.util.UUID;

/**
 * One artifact's outcome as its channel adapter reports it (issue #61,
 * S13-01, AC4): the status the distribution record takes, the external
 * id stores reconcile by, and -- on failure -- the taxonomy code that
 * explains it. The record's row is the adapter's report, verbatim;
 * the framework adds only the attempt bookkeeping around it.
 *
 * @param artifactId  the slot the outcome is about
 * @param status      the reported lifecycle status (PENDING, DISTRIBUTING, DISTRIBUTED, FAILED -- the record's own vocabulary)
 * @param externalId  the provider-side identifier for store reconciliation (build id, track release id; null when the channel has no external system -- Direct)
 * @param failureCode the taxonomy code when status is FAILED (null otherwise)
 * @param detail      human-facing failure detail when status is FAILED (null otherwise; never secret material -- the adapter holds none it may echo)
 */
public record AdapterOutcome(UUID artifactId, String status, String externalId, String failureCode, String detail) {

    /** The distribution-record lifecycle vocabulary (V1's own {@code distribution_status} words). */
    public static final String PENDING = "PENDING";

    public static final String DISTRIBUTING = "DISTRIBUTING";
    public static final String DISTRIBUTED = "DISTRIBUTED";
    public static final String FAILED = "FAILED";
}
