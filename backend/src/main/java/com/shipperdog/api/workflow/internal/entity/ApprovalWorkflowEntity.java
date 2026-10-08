package com.shipperdog.api.workflow.internal.entity;

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
 * Maps the {@code approval_workflows} table (V28, issue #40, S10-01,
 * FR-016): one organization's named approval-chain definition — the
 * workflow-as-data the issue's technical note demands (S10-02 extends
 * the same model with mode flags). Organization-grain reference data
 * (the environments standing): no isolation seam, the evaluator and
 * the definition routes check the organization explicitly, and the
 * rows are reachable only through this module's organization-scoped
 * queries.
 *
 * <p>The NAME is the binding vocabulary V27 reserved: an environment's
 * {@code approval_policy} references a workflow of the owning
 * organization by this name (a reference name, not a foreign key — the
 * V27 decision). The optional {@code ringId} is the direct attachment:
 * releases starting at that ring resolve this workflow at the
 * APPROVAL_PENDING crossing.
 */
@Entity
@Table(name = "approval_workflows")
public class ApprovalWorkflowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private String name;

    /** The direct ring attachment; null = a name-only definition environments bind by name. */
    private UUID ringId;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected ApprovalWorkflowEntity() {}

    /** One definition as created (S10-01): owner, binding name, optional ring attachment, creator. */
    public ApprovalWorkflowEntity(UUID organizationId, String name, UUID ringId, UUID createdBy) {
        this.organizationId = organizationId;
        this.name = name;
        this.ringId = ringId;
        this.createdBy = createdBy;
    }

    /**
     * Re-attaches the definition's ring (S10-02's edit surface): null
     * detaches (the definition becomes name-only — environments still
     * bind it by name). The name never moves: it is the binding
     * vocabulary environments reference, and renaming would strand
     * their bindings.
     */
    public void reattach(UUID ringId) {
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

    public String getName() {
        return name;
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
