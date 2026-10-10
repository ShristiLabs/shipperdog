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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code store_accounts} table (V43, issue #61, AC3, PRD
 * 25): one credential binding of the delegation chain -- a row at the
 * organization level (both parents null), the application level
 * (application set), or the environment level (both set). The row is
 * a REFERENCE HOLDER ONLY: id, provider, name, metadata, and the
 * opaque {@code secret_reference}; no column can hold secret material
 * because no writer accepts any (the V27 environment_credentials
 * doctrine, now the chain's organization-wide shape). The schema test
 * pins the column set exactly.
 *
 * <p>Resolution walks the chain most-specific-first (environment,
 * application, organization) and the first defined row wins: the
 * {@code uq_store_accounts_level_provider} unique constraint makes
 * "defined at a level" unambiguous -- one account per provider per
 * level, so an override is a replacement, never a merge.
 */
@Entity
@Table(name = "store_accounts")
public class StoreAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** Null at the organization level; set at the application and environment levels. */
    private UUID applicationId;

    /** Null unless the environment level (an environment row always names its application). */
    private UUID environmentId;

    /** The account's provider (open vocabulary: apple, google-play, ...). */
    private String provider;

    /** The account's human label (the slot a rotation re-points). */
    private String name;

    /** Non-secret provider facts as a jsonb document (account id, team id -- provider-specific, never material). */
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    /** The opaque secret reference; never material, never echoed beyond itself. */
    private String secretReference;

    private Instant createdAt;

    private Instant updatedAt;

    protected StoreAccountEntity() {}

    /**
     * One binding of the chain: the level's parents (organization
     * always; application/environment as the level names them), the
     * provider, the label, and the reference the level's dispatches
     * resolve through.
     */
    public StoreAccountEntity(
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            String provider,
            String name,
            String metadata,
            String secretReference) {
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.environmentId = environmentId;
        this.provider = provider;
        this.name = name;
        this.metadata = metadata;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getProvider() {
        return provider;
    }

    public String getName() {
        return name;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getSecretReference() {
        return secretReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** A rotation re-points the binding's reference (the one mutable fact; the level and provider are the slot's key). */
    public void rotate(String name, String metadata, String secretReference) {
        this.name = name;
        this.metadata = metadata;
        this.secretReference = secretReference;
    }

    /** The binding's level of the delegation chain (PRD 25's three). */
    public Level level() {
        if (environmentId != null) {
            return Level.ENVIRONMENT;
        }
        return applicationId != null ? Level.APPLICATION : Level.ORGANIZATION;
    }

    /** The delegation chain's levels (PRD 25: Organization, Application, Environment). */
    public enum Level {
        ORGANIZATION,
        APPLICATION,
        ENVIRONMENT
    }
}
