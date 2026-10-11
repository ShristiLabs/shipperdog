package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code channel_rollouts} table (V47, issue #65, S13-05,
 * AC1/AC2/AC5): ONE staged-rollout state row per release x channel —
 * the INTENT model the story's technical note names. ShipperDog owns
 * the intended curve ({@code intendedPercent}: what the operator
 * asked the provider to serve), the provider owns the mechanics, and
 * the reconciliation's OBSERVED percentage beside the intent IS the
 * drift report (never silently re-applied — the explicit REAPPLY
 * action is the operator's own act).
 *
 * <p>The control state is the platform's own vocabulary — ROLLING,
 * HALTED, COMPLETE — never the provider's words (those ride the
 * distribution record verbatim): HALT and RESUME are the release
 * actions AC2 names, and COMPLETE is the curve's own end (100% or the
 * provider's completed word).
 */
@Entity
@Table(name = "channel_rollouts")
public class ChannelRolloutEntity {

    /** The rollout's control state (the platform's vocabulary). */
    public enum Status {
        ROLLING,
        HALTED,
        COMPLETE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID releaseId;

    /** The channel reference name the rollout rides (the registry's vocabulary). */
    private String channel;

    /** The provider-side track release reference the curve controls (the record's external id). */
    private String externalId;

    /** The curve's intended percentage (1..100) — ShipperDog's own intent. */
    private int intendedPercent;

    /** The percentage last observed on the provider; null until a first observation. */
    private Integer observedPercent;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /** The effective-order ring the rollout serves (the policy consult's ring scope). */
    private String ringCode;

    /**
     * The optimistic-lock stamp (the #51 subscription precedent): a
     * stale concurrent control action refuses with the public 409
     * instead of last-write-winning — two concurrent ADVANCEs can
     * never lower the curve or double-apply a step.
     */
    @jakarta.persistence.Version
    private long version;

    private Instant createdAt;

    private Instant updatedAt;

    protected ChannelRolloutEntity() {}

    /** One rollout born at the production dispatch's landing: the configured initial percentage. */
    public ChannelRolloutEntity(
            UUID releaseId,
            UUID organizationId,
            String channel,
            String externalId,
            int intendedPercent,
            Status status,
            String ringCode) {
        this.releaseId = releaseId;
        this.organizationId = organizationId;
        this.channel = channel;
        this.externalId = externalId;
        this.intendedPercent = intendedPercent;
        this.status = status;
        this.ringCode = ringCode;
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

    public UUID getReleaseId() {
        return releaseId;
    }

    public String getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public int getIntendedPercent() {
        return intendedPercent;
    }

    public Integer getObservedPercent() {
        return observedPercent;
    }

    public Status getStatus() {
        return status;
    }

    public String getRingCode() {
        return ringCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The curve's intent moves (an advance or the initial landing's own intent). */
    public void intend(int percent) {
        this.intendedPercent = percent;
    }

    /** A provider observation lands (the refresh's, the view's, or an action's own answer). */
    public void observe(Integer percent) {
        this.observedPercent = percent;
    }

    /** The control state moves (HALT, RESUME, or the curve's own completion). */
    public void move(Status to) {
        this.status = to;
    }
}
