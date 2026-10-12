package com.shipperdog.api.artifact.internal.download;

import com.shipperdog.api.analytics.TelemetryTokenCodec;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.device.DeviceAccess;
import com.shipperdog.api.device.DeviceAccess.DeviceFacts;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The telemetry token the download issuance mints beside the signed
 * URL (issue #72, S15-01, PRD 37): the credential the tester
 * telemetry SDK presents to the frozen events contract, bound to
 * everything the download already proved. The mint resolves through
 * the FEEDBACK precedent's own chain — the downloading tester's
 * record, their EFFECTIVE membership in the reporting tenant, and
 * the release of the downloaded (application, version) TARGETING
 * that tenant — so a token never exists for a pairing the platform
 * would not itself accept an event batch from.
 *
 * <p>Telemetry is ADDITIVE evidence, never a download gate: when the
 * bindings do not resolve (a console persona with no tester record, a
 * tenant the release does not target, a caller without an effective
 * membership) the issuance still succeeds and simply carries no
 * telemetry block — the download's authority is the engine's ALLOW,
 * unchanged. The one explicit refusal is a caller-SUPPLIED
 * {@code device_id} that does not resolve as the tester's own ACTIVE
 * device in the tenant (a typo'd pairing would otherwise mint a
 * token whose events can never land — the field-addressed 400 is the
 * honest answer).
 */
@Service
public class DownloadTelemetryService {

    private final TelemetryTokenCodec tokens;

    private final ReleaseAccess releases;

    private final TesterAccess testers;

    private final TenantTesterMembershipAccess memberships;

    private final DeviceAccess devices;

    DownloadTelemetryService(
            TelemetryTokenCodec tokens,
            ReleaseAccess releases,
            TesterAccess testers,
            TenantTesterMembershipAccess memberships,
            DeviceAccess devices) {
        this.tokens = tokens;
        this.releases = releases;
        this.testers = testers;
        this.memberships = memberships;
        this.devices = devices;
    }

    /**
     * Resolves the telemetry block of one ALLOWED download: empty
     * when the pairing does not resolve (the additive doctrine
     * above); the device leg's explicit refusal when a supplied
     * device id is not the tester's own ACTIVE device in the tenant.
     *
     * @param actorId     the downloading user (the engine's ALLOW was
     *                    for them)
     * @param ownership   the downloaded version's ownership tuple
     * @param tenantParam the reporting tenant the caller addressed
     *                    (the portal's membership tenant; the
     *                    version's owning tenant when absent)
     * @param deviceParam the optional device pairing (the registry's
     *                    own id)
     */
    Optional<ArtifactDownloadService.TelemetryIssuance> telemetryOf(
            UUID actorId, VersionOwnership ownership, UUID tenantParam, UUID deviceParam) {
        UUID tenantId = tenantParam != null ? tenantParam : ownership.tenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        ReleaseFacts facts = releases.telemetryFactsOfVersion(
                        ownership.applicationId(), ownership.versionId(), tenantId)
                .orElse(null);
        if (facts == null) {
            return Optional.empty();
        }
        UUID testerId = testers.testerIdOfUser(actorId).orElse(null);
        if (testerId == null || !memberships.isEffectivelyActive(tenantId, testerId)) {
            return Optional.empty();
        }
        UUID deviceId = resolveDevice(tenantId, testerId, deviceParam);
        TelemetryTokenCodec.Grant grant = tokens.grantForNow(tenantId, actorId, facts.releaseId(), deviceId);
        return Optional.of(new ArtifactDownloadService.TelemetryIssuance(
                tokens.mint(grant),
                grant.expiresAt(),
                tenantId,
                ownership.applicationId(),
                facts.releaseId(),
                ownership.versionId(),
                deviceId));
    }

    /** The feedback link's own discipline: the caller's own ACTIVE device in this tenant, or the field-addressed refusal. */
    private UUID resolveDevice(UUID tenantId, UUID testerId, UUID deviceParam) {
        if (deviceParam == null) {
            return null;
        }
        DeviceFacts device = devices.factsOf(deviceParam).orElse(null);
        if (device == null
                || !tenantId.equals(device.tenantId())
                || !"ACTIVE".equals(device.status())
                || !testerId.equals(device.testerId())) {
            throw new ArtifactException(
                    ArtifactException.Code.INVALID_REQUEST,
                    "device_id did not resolve as the downloading tester's own ACTIVE device in this tenant",
                    Map.of("device_id", "not the downloading tester's own ACTIVE device in this tenant"));
        }
        return deviceParam;
    }
}
