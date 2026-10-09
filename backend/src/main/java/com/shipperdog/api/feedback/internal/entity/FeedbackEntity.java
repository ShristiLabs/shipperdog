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
 * <p>Rows are INSERT-only in this story: submissions are immutable,
 * born {@code OPEN} (the V1 status vocabulary; PRD 8.13's triage
 * transitions are S11-02), and no surface here updates one. The
 * {@code updated_at} column is V1's shape, maintained by the hooks
 * for the story that first needs it.
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

    /** The V1 {@code feedback_status} vocabulary, untouched by this story (triage is S11-02). */
    public enum Status {
        OPEN,
        RESOLVED,
        DISMISSED
    }

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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
