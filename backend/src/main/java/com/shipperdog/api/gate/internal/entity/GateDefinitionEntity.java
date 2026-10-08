package com.shipperdog.api.gate.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code gate_definitions} table (V31, issue #42, S10-03,
 * FR-026): one organization's required-gate definition — the
 * gates-as-data model, the {@code approval_workflows} standing.
 * Organization-grain reference data: no isolation seam, the evaluator
 * and the definition routes check the organization explicitly, and
 * the rows are reachable only through this module's
 * organization-scoped queries.
 *
 * <p>{@code gateType} is the V1 open vocabulary (integration-defined
 * kinds, lowercase kebab); the service validates the {@code config}
 * payload against {@link com.shipperdog.api.gate.GateCatalog}'s schema
 * for the type before any write, so a coverage definition always
 * carries its threshold and a critical-bug ceiling its max. The
 * optional {@code ringId} is the PRD 33 attachment: releases starting
 * at that ring evaluate this definition; a null ring is the
 * organization-wide default a ring-attached definition of the same
 * type overrides.
 */
@Entity
@Table(name = "gate_definitions")
public class GateDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String gateType;

    private Boolean required;

    /** The expected-result payload as data ({"threshold": 80}, {"max": 0}, or {} for the pass/fail kinds). */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config = new LinkedHashMap<>();

    /** The PRD 33 ring attachment; null = the organization-wide default. */
    private UUID ringId;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected GateDefinitionEntity() {}

    /** One definition as created (S10-03): owner, gate type, required flag, config payload, optional ring, creator. */
    public GateDefinitionEntity(
            UUID organizationId,
            String gateType,
            boolean required,
            Map<String, Object> config,
            UUID ringId,
            UUID createdBy) {
        this.organizationId = organizationId;
        this.gateType = gateType;
        this.required = required;
        this.config = config;
        this.ringId = ringId;
        this.createdBy = createdBy;
    }

    /**
     * Edits the mutable facts (the update surface): the required
     * flag, the config payload, and the ring attachment (null
     * detaches to the organization-wide standing). The gate TYPE
     * never moves — it is the identity the release rows and the
     * catalog schema key on; a changed type is a new definition.
     */
    public void edit(boolean required, Map<String, Object> config, UUID ringId) {
        this.required = required;
        this.config = config;
        this.ringId = ringId;
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

    public String getGateType() {
        return gateType;
    }

    public Boolean getRequired() {
        return required;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public UUID getRingId() {
        return ringId;
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
}
