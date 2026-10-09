package com.shipperdog.api.device.internal.entity;

import com.shipperdog.api.tenant.TenantScope;
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
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One registered tester device (issue #48, S11-03, FR-028, PRD 8.12):
 * the V1 {@code devices} row with the V37 status switch. The
 * registration vocabulary is V1's own — platform (the store's grain),
 * model, OS version, and the identifier (a UDID or serial, the
 * semi-sensitive fingerprint the issue's privacy note guards) — and
 * the tenant-scoped uniqueness of that identifier (one device id per
 * tenant, {@code uq_devices_tenant_identifier}) is AC1's duplicate
 * guard.
 *
 * <p>Tenant-scoped user data (ADR-0002): the entity implements
 * {@link TenantScope}, its repository extends the seam, and every read
 * and write runs under the request's bound tenant context — a foreign
 * tenant's device is indistinguishable from a missing one. The device
 * belongs to a tester AND a tenant (both V1 NOT NULL): the tester is
 * the owner whose feedback carries the device as context, the tenant
 * is the scope the console manages and the provisioning export draws
 * from.
 *
 * <p>Rows are never deleted (the INV-007 history posture, AC5's
 * ethos): deactivating a tester keeps the tester's device records,
 * and deactivating a device parks it reversibly ({@code DEACTIVATED})
 * while its feedback links and the tenant's history stay queryable.
 * The identifier and platform are immutable after registration — they
 * ARE the device's identity; only the descriptive fields (model, OS
 * version) and the status move.
 */
@Entity
@Table(name = "devices")
public class DeviceEntity implements TenantScope {

    /** The V1 {@code platform} vocabulary: the two stores' grains (an artifact's platform is the same enum). */
    public enum Platform {
        IOS,
        ANDROID
    }

    /**
     * The V37 {@code device_status} vocabulary (the V8
     * tenant_member_status precedent): ACTIVE is the registered,
     * linkable state; DEACTIVATED the reversible park — the device
     * stops counting as an install and new feedback cannot attach it,
     * but the row, its history, and its feedback links stay.
     */
    public enum Status {
        ACTIVE,
        DEACTIVATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID testerId;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Platform platform;

    private String model;

    private String osVersion;

    /** The UDID or serial — semi-sensitive (device fingerprinting); admin-only visibility. */
    private String identifier;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private Instant createdAt;

    private Instant updatedAt;

    protected DeviceEntity() {}

    /**
     * A new registration (AC1): born ACTIVE under the tenant the route
     * addresses, owned by the tester the request names (the service
     * has already proven a retained membership row in this tenant).
     */
    public DeviceEntity(
            UUID testerId, UUID tenantId, Platform platform, String model, String osVersion, String identifier) {
        this.testerId = testerId;
        this.tenantId = tenantId;
        this.platform = platform;
        this.model = model;
        this.osVersion = osVersion;
        this.identifier = identifier;
        this.status = Status.ACTIVE;
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

    public UUID getTesterId() {
        return testerId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getModel() {
        return model;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getIdentifier() {
        return identifier;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Records the descriptive update (AC2): the model and OS version
     * alone — the identifier and platform are the device's identity
     * and never move. Nulls clear (an unknown model may be filled in
     * later or withdrawn); the service has already bounded the values.
     *
     * @return whether either field changed (an unchanged update is not
     *     an event worth auditing)
     */
    public boolean applyDescription(String model, String osVersion) {
        boolean changed = !Objects.equals(this.model, model) || !Objects.equals(this.osVersion, osVersion);
        this.model = model;
        this.osVersion = osVersion;
        return changed;
    }

    /**
     * Records one status crossing (AC2/AC5): the reversible park and
     * its release. The service has already refused the no-op crossing.
     *
     * @return the status replaced
     */
    public Status applyStatus(Status to) {
        Status previous = this.status;
        this.status = to;
        return previous;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
