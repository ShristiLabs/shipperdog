package com.shipperdog.api.feedback.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One structured tester feedback report (issue #46, S11-01, FR-027,
 * PRD 8.13): the V1 {@code feedback} row expanded by V35 with the
 * submission-time context the API attaches SERVER-side — the owning
 * organization, the denormalized application/version beside V1's
 * release/artifact references, and the SUBMITTING tenant (the release
 * target the report came from; the release row's owning-application
 * snapshot is a different tenant's, so both dimensions live here).
 *
 * <p>A plain child of the release (the V25 doctrine, the release-
 * policy ledger's precedent): every console read resolves the parent
 * release through the release module's scoped port first, so
 * isolation is enforced at the parent and never re-derived here. The
 * submission path writes through the tester's own sanctioned tenant
 * scope with the release link ({@code ReleaseAccess.submissionFactsOf})
 * as its entire standing.
 *
 * <p>Submissions are immutable; triage (S11-02) mutates the WORKING
 * columns only — status, severity, assignee, resolution note — never
 * the description, type, or attachments. Rows are born {@code OPEN}
 * (the NEW state of the triage vocabulary) and move
 * OPEN to TRIAGED to RESOLVED or REJECTED, with every resolved state
 * re-openable to TRIAGED; the hooks maintain {@code updated_at} as
 * the triage trail's clock.
 */
@Entity
@Table(name = "feedback")
public class FeedbackEntity {

    /** The V1 {@code feedback_type} vocabulary: PRD 8.13's six report types, FR-027's list verbatim. */
    public enum Type {
        BUG,
        FEEDBACK,
        CRASH,
        FEATURE_REQUEST,
        UX_ISSUE,
        SECURITY_ISSUE
    }

    /**
     * The {@code feedback_status} vocabulary: V1's three values plus
     * the two the triage workflow appended (V36, expand-only — OPEN is
     * the born NEW state, TRIAGED the under-review state, RESOLVED and
     * REJECTED the two answers, DISMISSED the historical answer whose
     * rows re-open through the same TRIAGED door).
     */
    public enum Status {
        OPEN,
        TRIAGED,
        RESOLVED,
        REJECTED,
        DISMISSED
    }

    /**
     * The {@code feedback_severity} vocabulary (V36): the QA
     * engineer's classification, assigned at triage — null until then,
     * because the submitting tester does not classify (one vocabulary,
     * one authority).
     */
    public enum Severity {
        CRITICAL,
        MAJOR,
        MINOR
    }

    /**
     * The statuses an unresolved report holds (the open-critical-bugs
     * metric's predicate half beside severity CRITICAL — the count the
     * gate and policy seams read, and the rollup's live answer).
     */
    public static final List<Status> UNRESOLVED = List.of(Status.OPEN, Status.TRIAGED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private UUID artifactId;

    private UUID testerId;

    private UUID deviceId;

    private UUID organizationId;

    /** The release target the report came from — the tester's validated tenant linkage at submission time. */
    private UUID submitterTenantId;

    private UUID applicationId;

    private UUID versionId;

    /** The REPORTED device model, free text (registered device rows link in with S11-03). */
    private String deviceModel;

    /** The REPORTED OS, free text (the portal derives it from the user agent; the SDK reports it directly). */
    private String os;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Type type;

    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Severity severity;

    /** The organization member working the report (null until assigned). */
    private UUID assigneeUserId;

    /** The mandatory explanation a RESOLVED or REJECTED crossing records (null otherwise). */
    private String resolutionNote;

    private Instant createdAt;

    private Instant updatedAt;

    protected FeedbackEntity() {}

    /**
     * A new report (S11-01): born OPEN under the server-attached
     * context — the release it evaluates, the artifact when the tester
     * named their build, the owning organization, the denormalized
     * application/version, the submitting tenant, and the reported
     * device/OS free text. No constructor argument carries an
     * identity the caller could have supplied: the tester and tenant
     * are resolved from the session, the context from the release.
     */
    public FeedbackEntity(
            UUID releaseId,
            UUID artifactId,
            UUID testerId,
            UUID organizationId,
            UUID submitterTenantId,
            UUID applicationId,
            UUID versionId,
            String deviceModel,
            String os,
            Type type,
            String description) {
        this.releaseId = releaseId;
        this.artifactId = artifactId;
        this.testerId = testerId;
        this.organizationId = organizationId;
        this.submitterTenantId = submitterTenantId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.deviceModel = deviceModel;
        this.os = os;
        this.type = type;
        this.description = description;
        this.status = Status.OPEN;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public UUID getTesterId() {
        return testerId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getSubmitterTenantId() {
        return submitterTenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public String getOs() {
        return os;
    }

    public Type getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    /**
     * Records one triage classification (S11-02): the severity is QA
     * authority over a report the triage workflow can still move — the
     * service refuses the call on a resolved, rejected, or dismissed
     * row (re-open first; a closed report's classification is history).
     *
     * @return the severity replaced, null when this is the first classification
     */
    public Severity applySeverity(Severity severity) {
        Severity previous = this.severity;
        this.severity = severity;
        return previous;
    }

    /**
     * Records one assignment (S11-02). A null assignee clears it.
     *
     * @return the assignee replaced, null when the report was unassigned
     */
    public UUID applyAssignee(UUID assigneeUserId) {
        UUID previous = this.assigneeUserId;
        this.assigneeUserId = assigneeUserId;
        return previous;
    }

    /**
     * Records one triage crossing (S11-02): the status alone — the
     * submission's content never moves (the S11-01 immutability
     * posture, kept). The service has already validated the edge and
     * the mandatory note of the closing crossings; the resolution note
     * rides the same write.
     *
     * @return the status replaced
     */
    public Status applyTransition(Status to, String resolutionNote) {
        Status previous = this.status;
        this.status = to;
        if (resolutionNote != null) {
            this.resolutionNote = resolutionNote;
        }
        return previous;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
