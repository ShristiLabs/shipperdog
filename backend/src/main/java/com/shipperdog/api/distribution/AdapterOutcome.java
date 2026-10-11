package com.shipperdog.api.distribution;

import java.util.UUID;

/**
 * One artifact's outcome as its channel adapter reports it (issue #61,
 * S13-01, AC4): the status the distribution record takes, the external
 * id stores reconcile by, and -- on failure -- the taxonomy code that
 * explains it. The record's row is the adapter's report, verbatim;
 * the framework adds only the attempt bookkeeping around it.
 *
 * @param artifactId     the slot the outcome is about
 * @param status         the reported lifecycle status (PENDING, DISTRIBUTING, DISTRIBUTED, FAILED -- the record's own vocabulary)
 * @param externalId     the provider-side identifier for store reconciliation (build id, track release id; null when the channel has no external system -- Direct)
 * @param failureCode    the taxonomy code when status is FAILED (null otherwise)
 * @param detail         human-facing failure detail when status is FAILED (null otherwise; never secret material -- the adapter holds none it may echo)
 * @param providerStatus the provider's own lifecycle word for the artifact (S13-02, #62: Apple's beta-review states, a build's processing state) -- verbatim on the distribution record for the release dashboard; null when the channel reports none
 */
public record AdapterOutcome(
        UUID artifactId, String status, String externalId, String failureCode, String detail, String providerStatus) {

    /** The S13-01 shape (no provider lifecycle word): providerStatus answers null. */
    public AdapterOutcome(UUID artifactId, String status, String externalId, String failureCode, String detail) {
        this(artifactId, status, externalId, failureCode, detail, null);
    }

    /** The distribution-record lifecycle vocabulary (V1's own {@code distribution_status} words). */
    public static final String PENDING = "PENDING";

    public static final String DISTRIBUTING = "DISTRIBUTING";
    public static final String DISTRIBUTED = "DISTRIBUTED";
    public static final String FAILED = "FAILED";
}
