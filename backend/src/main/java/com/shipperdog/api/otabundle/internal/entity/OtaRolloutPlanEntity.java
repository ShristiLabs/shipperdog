package com.shipperdog.api.otabundle.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * One OTA device-rollout plan row (V56 {@code ota_rollout_plans},
 * S16-06, ADR-0010 D6/D10): the release's OWN widening ladder -- ONE
 * plan per release ({@code uq_ota_rollout_plans_release}), born
 * RUNNING at its attachment to a live OTA release (the commencement:
 * stage 1 REACHED, a COMMENCED timeline row), narrowed by the manual,
 * audit-trailed operator actions (HALTED, PAUSED) and widened again
 * (RESUMED), and terminal when the final stage's basis points are
 * reached (COMPLETE). No code path auto-halts (D10): every crossing
 * here is an operator's act, and the act's evidence lands in
 * {@code ota_rollout_events}, the audit trail, and the bus.
 *
 * <p><strong>Org-grain by design</strong> (the V48 mirror): the row
 * carries the organization, never a tenant -- a rollout serves a
 * release's whole device population, which is the application's
 * owning organization's grain -- so {@link #tenantId()} answers null
 * (V60's column, kept NULL by its org-grain guard) and access rides
 * the seam bound in ORGANIZATION scope, exactly the org-owned
 * doctrine {@code OrganizationScope} names.
 *
 * <p><strong>The optimistic-lock stamp</strong> (V56's {@code version
 * bigint}, the V48 {@code staged_rollout_plans} precedent): two
 * concurrent control actions on one plan refuse -- the loser answers
 * the module's 409 -- instead of last-write-winning a checkpoint or
 * double-applying a stage. The stamp is JPA-managed
 * ({@link Version @Version}); the service pushes every control
 * action's writes to the database INSIDE its guard (flush before the
 * catch boundary, the #181 lesson) so the refusal translates there,
 * never as a deferred-commit 500.
 */
@Entity
@Table(name = "ota_rollout_plans")
public class OtaRolloutPlanEntity implements OrganizationScope {

    /** The advancement mode of V56 {@code ck_ota_rollout_plans_mode}: who decides between stages. */
    public enum Mode {
        AUTOMATIC,
        CHECKPOINT
    }

    /**
     * The plan's own standing of V56 {@code ck_ota_rollout_plans_status}:
     * RUNNING from the commencement, HALTED by the manual operator stop,
     * PAUSED by the temporary operator stop, COMPLETE when the final
     * stage's basis points are reached.
     */
    public enum Status {
        RUNNING,
        HALTED,
        PAUSED,
        COMPLETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /**
     * Always NULL (V60's {@code ck_ota_rollout_plans_org_grain}): the
     * plan is the organization's own row -- a rollout serves the
     * release's whole device population, never one tenant's -- but the
     * MAPPED column itself is what lets the row ride the
     * tenant-isolation seam, whose organization-scope predicate
     * composes {@code tenantId IS NULL AND organizationId = :context}
     * on exactly this attribute (the {@code OrganizationScope}
     * doctrine every org-owned row follows).
     */
    private UUID tenantId;

    private UUID releaseId;

    /** The release's application, denormalized at attachment (the audit and view grain). */
    private UUID applicationId;

    /** The release's version, denormalized at attachment (the V48 precedent). */
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    private Mode mode;

    @Enumerated(EnumType.STRING)
    private Status status;

    /** The 1-based position of the serving stage (the highest REACHED); null before the commencement. */
    private Integer currentStage;

    /**
     * The salt of the deterministic device bucketing (D6): mixed into
     * {@code hash(device_id, plan_salt)} so two plans' bucket orders are
     * independent. Public-by-design (V56's own note: device ids are not
     * enumerable, so the salt alone reveals nothing).
     */
    private String planSalt;

    /** The optimistic-lock stamp (V56's V48 mirror); JPA-managed, never written by hand. */
    @Version
    private long version;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected OtaRolloutPlanEntity() {}

    /**
     * One plan born RUNNING at its commencement: the release's own
     * dimensions beside its mode and fresh salt, stage 1 already the
     * serving stage.
     */
    public OtaRolloutPlanEntity(
            UUID releaseId,
            UUID organizationId,
            UUID applicationId,
            UUID versionId,
            Mode mode,
            String planSalt,
            UUID createdBy) {
        this.releaseId = releaseId;
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.mode = mode;
        this.status = Status.RUNNING;
        this.currentStage = 1;
        this.planSalt = planSalt;
        this.createdBy = createdBy;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    @Override
    public UUID organizationId() {
        return organizationId;
    }

    /** Always null: the row is org-owned (V60's org-grain guard over the mapped column). */
    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public Mode getMode() {
        return mode;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getCurrentStage() {
        return currentStage;
    }

    public String getPlanSalt() {
        return planSalt;
    }

    /** The optimistic-lock stamp's current value (view grain; never written through this reader). */
    public long getVersion() {
        return version;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The serving stage moves forward -- the advancement's own write. */
    public void serve(int stagePosition) {
        this.currentStage = stagePosition;
    }

    /** One operator crossing: HALTED, PAUSED, RESUMED (back to RUNNING), or COMPLETE. */
    public void move(Status to) {
        this.status = to;
    }
}
