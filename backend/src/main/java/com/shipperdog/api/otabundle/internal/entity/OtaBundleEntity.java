package com.shipperdog.api.otabundle.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One authored OTA bundle row (V55 {@code ota_bundles}, S16-03,
 * ADR-0010): the platform-agnostic binary's registry entry — its own
 * dedicated versions row (D2), its bytes an ordinary artifact behind
 * {@code artifact_id} (D5), its manifest (sha256 + size + platform
 * tag) and its Ed25519 signature + key id landed by finalize+sign
 * (D8). The row is INSERTED at finalize, in the same transaction the
 * artifact registers and the signature signs: the schema's NOT NULL
 * artifact foreign key and the {@code ck_ota_bundles_manifest_shape}
 * / {@code ck_ota_bundles_signing_shape} coherent-pair constraints
 * make the pre-bytes draft a session-level fact (the artifact
 * module's upload session), never a bundle row.
 *
 * <p>INV-003's discipline mirrors over: no setter touches the binary
 * or manifest fields after insert — the only post-insert transition
 * this entity knows is the status column (finalize's SIGNED landing,
 * revoke's REVOKED, and the S16-05 RELEASED crossing to come).
 */
@Entity
@Table(name = "ota_bundles")
public class OtaBundleEntity implements OrganizationScope, Persistable<UUID> {

    /**
     * Lifecycle vocabulary of V55 {@code ck_ota_bundles_status}:
     * UPLOADING is the session window before any row exists (kept in
     * the vocabulary for the API surface's draft states), VALIDATING
     * is the in-transaction manifest-computation moment of finalize,
     * SIGNED the finalized+signed bundle ready to publish, RELEASED
     * the S16-05 release linkage, REVOKED the author's withdrawal.
     */
    public enum Status {
        UPLOADING,
        VALIDATING,
        SIGNED,
        RELEASED,
        REVOKED
    }

    /**
     * The id equals the pre-allocated artifact id the upload session
     * granted: the bundle's identity and its binary's identity are one
     * (D5 — the bytes ARE the bundle), and a finalize retry addresses
     * the same id whether or not the row landed. Assigned before any
     * byte moves, so {@link Persistable} keeps Hibernate inserting
     * rather than merging.
     */
    @Id
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private UUID applicationId;

    /** The bundle's OWN versions row (D2); label = bundle_version. */
    private UUID versionId;

    /** NULL until the bundle publishes on a release (the S16-05 rule). */
    private UUID releaseId;

    /** The bundle's bytes: an ordinary artifacts row (D5). */
    private UUID artifactId;

    /** The bundle's version label (its versions row's name). */
    private String bundleVersion;

    /**
     * The generic platform tag: an OPEN vocabulary ('android', 'ios',
     * 'electron', ...), deliberately not the IOS/ANDROID artifact
     * platform enum (V55's doctrine).
     */
    private String platformTag;

    /** The manifest checksum: 64 lowercase hex, landed at finalize. */
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @jakarta.persistence.Column(name = "sha256", length = 64)
    private String sha256;

    /** The manifest byte size, landed at finalize. */
    private Long sizeBytes;

    /** Base64 Ed25519 signature over the sha256 hex (D8), landed at sign. */
    private String signature;

    /** The signing key generation's public identifier (D8). */
    private String keyId;

    /** Per-bundle default, overridable per release target (D7). */
    private boolean forced;

    /** Per-bundle default, overridable per release target (D7). */
    private String minAppVersion;

    @Enumerated(EnumType.STRING)
    private Status status;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected OtaBundleEntity() {}

    /**
     * The finalize-time insert: manifest computed, signature about to
     * land. The status parameter carries the in-transaction moment
     * (VALIDATING at insert, SIGNED after sign) so the row's final
     * committed state is always coherent with the pair constraints.
     */
    public OtaBundleEntity(
            UUID id,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            String bundleVersion,
            String platformTag,
            String sha256,
            Long sizeBytes,
            boolean forced,
            String minAppVersion,
            Status status,
            UUID createdBy) {
        this.id = id;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.artifactId = artifactId;
        this.bundleVersion = bundleVersion;
        this.platformTag = platformTag;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.forced = forced;
        this.minAppVersion = minAppVersion;
        this.status = status;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        isNew = false;
    }

    @jakarta.persistence.PostLoad
    void onLoad() {
        isNew = false;
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

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public String getBundleVersion() {
        return bundleVersion;
    }

    public String getPlatformTag() {
        return platformTag;
    }

    public String getSha256() {
        return sha256;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSignature() {
        return signature;
    }

    public String getKeyId() {
        return keyId;
    }

    public boolean isForced() {
        return forced;
    }

    public String getMinAppVersion() {
        return minAppVersion;
    }

    public Status getStatus() {
        return status;
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

    /**
     * The sign landing (finalize's second half): the signature and its
     * key id land as the coherent pair V55 requires, and the row
     * crosses to SIGNED. The only writer.
     */
    public void landSignature(String signature, String keyId) {
        this.signature = signature;
        this.keyId = keyId;
        this.status = Status.SIGNED;
    }

    /** The author's withdrawal: SIGNED to REVOKED (V55's vocabulary). */
    public void revoke() {
        this.status = Status.REVOKED;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
