package com.shipperdog.api.environment.internal.entity;

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
 * Maps the {@code environment_credentials} table (V27, issue #58, AC3):
 * one credential BINDING of one environment — a label plus the opaque
 * {@code secret_reference} and nothing else (PRD 25: store references,
 * not credentials). The row can never hold secret material because no
 * writer accepts any: the binding surface takes name and reference,
 * the verify probe answers whether the reference resolves, and the
 * resolver's material exists only behind the port at resolution time.
 * Production credentials are not QA credentials because they are
 * different rows with different references under different environments
 * (AC5); the schema is the review's proof.
 *
 * <p>A plain child of the organization-grain environment (the V25
 * doctrine): no snapshot, no seam repository, replaced as a set by the
 * binding write. The name is unique per environment (the slot a
 * rotation overwrites); the reference is the value that moves when the
 * secret below it rotates.
 */
@Entity
@Table(name = "environment_credentials")
public class EnvironmentCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID environmentId;

    /** The binding's label: {@code apple-store}, {@code api-signing} (kebab). */
    private String name;

    /** The opaque secret reference; never material, never echoed beyond itself. */
    private String secretReference;

    private Instant createdAt;

    private Instant updatedAt;

    protected EnvironmentCredentialEntity() {}

    /** One binding: the environment, the label, and the reference the environment's releases resolve through. */
    public EnvironmentCredentialEntity(UUID environmentId, String name, String secretReference) {
        this.environmentId = environmentId;
        this.name = name;
        this.secretReference = secretReference;
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

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getName() {
        return name;
    }

    public String getSecretReference() {
        return secretReference;
    }

    /** A rotation re-points the binding's reference (the one mutable fact; the name is the slot). */
    public void setSecretReference(String secretReference) {
        this.secretReference = secretReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
