package com.shipperdog.api.notification;

/**
 * The frozen public vocabulary of the domain-event bus (issue #51,
 * S12-01, PRD 51): the names external consumers -- webhook
 * receivers, the S12-02 Slack/Teams formatters, the S12-03/04 CI
 * consumers, a future SIEM export -- subscribe against. Names are
 * public contract: values are never renamed or removed once
 * delivered; new names are appended with their owning story.
 *
 * <p>The wired table (which transition publishes which name, and the
 * payload shape of each) is documented in docs/notifications.md. The
 * PRD 51 chain names this story does <em>not</em> wire -- because the
 * transitions they describe do not exist yet -- are reserved and
 * documented there as deferred: {@code QUALITY_GATE_STARTED},
 * {@code QUALITY_GATE_PASSED} (no gate-state machine exists; gate
 * results are recorded, S10-03), {@code DISTRIBUTION_STARTED},
 * {@code DISTRIBUTION_COMPLETED}, {@code DISTRIBUTION_FAILED},
 * {@code TESTER_NOTIFIED}, {@code TESTER_INSTALLED} (distribution
 * execution and the SDK install stream are the analytics/E10 seams),
 * {@code PROMOTION_REQUESTED} (a refused promote attempt is already
 * the audited {@code RELEASE_PROMOTION_BLOCKED}; the committed hop
 * carries {@code RELEASE_PROMOTED}), and {@code
 * NEW_VERSION_AVAILABLE} (the tester-visible availability crossing
 * belongs to distribution targeting).
 */
public enum DomainEventName {
    /** One artifact registered from a completed upload session (S5-05). */
    ARTIFACT_UPLOADED,
    /** One artifact's validation pipeline ended PASSED (S5-05). */
    ARTIFACT_VALIDATED,
    /** One artifact's validation pipeline ended FAILED (FR-023). */
    ARTIFACT_FAILED,
    /** One DRAFT release created with its full target set (S9-01). */
    RELEASE_CREATED,
    /**
     * One committed release-state crossing without its own specific
     * name (S9-02): every {@code ReleaseTransitionedEvent} except the
     * APPROVED crossing (which carries {@code RELEASE_APPROVED}) and
     * the PROMOTED crossing (which carries {@code RELEASE_PROMOTED},
     * the promotion hop's own event).
     */
    RELEASE_TRANSITIONED,
    /** One approval step instantiated for signature (S10-01, FR-023 "Approval requested"). */
    APPROVAL_REQUESTED,
    /** The release's APPROVED crossing -- the completed approval chain (S10-01, FR-023 "Approval completed"). */
    RELEASE_APPROVED,
    /** One committed ring-promotion hop, any ring (S10-05). */
    RELEASE_PROMOTED,
    /** One tester invited or re-invited into an organization (S6-01, FR-023). */
    TESTER_INVITED,
    /** One artifact download URL issued to a tester (S8-04, PRD 51's TESTER_DOWNLOADED). */
    TESTER_DOWNLOADED,
    /** One structured tester feedback report landed (S11-01). */
    FEEDBACK_RECEIVED,
    /** One report classified CRITICAL by triage (S11-02, issue #47). */
    FEEDBACK_MARKED_CRITICAL,
    /**
     * A subscription's test message (S12-02, issue #52, AC3): the
     * synthetic event the test action records when an administrator
     * verifies a channel's configuration end-to-end. Not published by
     * any domain transition -- the test action is its only publisher,
     * and the row is born RELAYED (the synchronous dispatch through
     * the real delivery pipeline IS its publication; the relay never
     * re-fans a test out).
     */
    NOTIFICATION_TEST_SENT
}
