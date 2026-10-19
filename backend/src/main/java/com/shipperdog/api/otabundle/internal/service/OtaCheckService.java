package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.analytics.OtaCheckEventRecorder;
import com.shipperdog.api.analytics.TelemetryTokenCodec;
import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.artifact.OtaBundleArtifacts;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.otabundle.OtaAppTokenPrincipal;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.OtaDeviceBucketing;
import com.shipperdog.api.otabundle.OtaRolloutAccess;
import com.shipperdog.api.otabundle.OtaRolloutAccess.RolloutStanding;
import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleRegistryRepository;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.OtaChannelRelease;
import com.shipperdog.api.release.RingEligibility;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The device-check resolution (S16-07, issue #169, ADR-0010 workstream
 * 4): the keystone device-facing endpoint's whole chain — app standing,
 * the channel's live OTA release, ring eligibility, the rollout's
 * standing and deterministic bucket, and the version/content-hash
 * comparison — answered as ONE manifest or ONE 204, never a reason.
 * Every leg is a composed probe over the building blocks S16-03
 * through S16-06 landed; this service owns only the POLICY ORDERING
 * (the {@code OtaRolloutAccess} note's doctrine: the ports answer
 * facts, the caller owns the sequence).
 *
 * <p><strong>The application's own standing is the FIRST gate</strong>
 * (the #182 review carry-forward): the {@code sdc1.} filter's resolve
 * authenticates tokens of ARCHIVED and OTA-disabled applications by
 * design — identity, never policy — so the policy lives HERE. An
 * archived or opted-out application answers the same no-update 204 as
 * an empty channel: a device learns nothing about why.
 *
 * <p><strong>No-update is one answer.</strong> Up-to-date (version
 * label or content hash), not eligible (the ring's device axes), a
 * HALTED or PAUSED rollout, a live release without its rollout plan,
 * a stage that has not reached the device's bucket, and no live
 * release on the channel at all are indistinguishable on the wire —
 * the METERS carry the funnel split (D9: the checks are counted
 * server-side by outcome; only the update-available outcome inserts
 * an {@code OTA_CHECK} row, the one OTA event whose release is known).
 *
 * <p><strong>The served manifest mints two credentials</strong>
 * (issue #169's contract): the download URL (D5 — the artifact
 * module's issuance machinery, the same audit, counters, and TTL a
 * tester download rides; the check's own resolution is the
 * authorization it records) and the {@code sdt1.} ingest grant (D4 —
 * the existing telemetry codec, so lifecycle events flow through the
 * existing ingest with zero new auth surface). The grant binds the
 * release and the application's FIRST target tenant (deterministic,
 * the funnel grain the {@code OTA_CHECK} row shares); it is
 * deliberately device-UNBOUND — a fleet device is not the device
 * registry's tester hardware, and the S16-08 {@code ota_payload} is
 * the device identity's carrier.
 */
@Service
public class OtaCheckService {

    /** The device identifier's bounds: a non-blank string of at most 128 characters. */
    private static final int DEVICE_ID_MAX = 128;

    /** The open platform tag's bounds (V55's vocabulary discipline). */
    private static final int PLATFORM_MAX = 64;

    /** The channel (ring code) bounds, the ring-code vocabulary's own. */
    private static final int CHANNEL_MAX = 64;

    /** The app version report's bounds. */
    private static final int APP_VERSION_MAX = 64;

    /** The bundle version label's bounds (the bundle authoring surface's own). */
    private static final int BUNDLE_VERSION_MAX = 128;

    private final ApplicationAccess applications;
    private final ReleaseAccess releases;
    private final RingEligibility eligibility;
    private final OtaRolloutAccess rollouts;
    private final OtaBundleRegistryRepository bundles;
    private final OtaBundleArtifacts bundleArtifacts;
    private final TelemetryTokenCodec telemetryTokens;
    private final OtaCheckEventRecorder checkEvents;
    private final OtaCheckProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    OtaCheckService(
            ApplicationAccess applications,
            ReleaseAccess releases,
            RingEligibility eligibility,
            OtaRolloutAccess rollouts,
            OtaBundleRegistryRepository bundles,
            OtaBundleArtifacts bundleArtifacts,
            TelemetryTokenCodec telemetryTokens,
            OtaCheckEventRecorder checkEvents,
            OtaCheckProperties properties,
            MeterRegistry meters,
            Clock clock) {
        this.applications = applications;
        this.releases = releases;
        this.eligibility = eligibility;
        this.rollouts = rollouts;
        this.bundles = bundles;
        this.bundleArtifacts = bundleArtifacts;
        this.telemetryTokens = telemetryTokens;
        this.checkEvents = checkEvents;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * One check's answer: the manifest of the update the device should
     * take, or empty for the no-update 204. Pure reads until the
     * serving decision; the mints and the check row land only after
     * the whole chain said yes.
     */
    public Optional<Manifest> check(OtaAppTokenPrincipal principal, CheckQuery query) {
        ApplicationProfile application = applications
                .profileOf(principal.applicationId())
                .filter(profile -> profile.organizationId().equals(principal.organizationId()))
                .orElse(null);
        // The application's own policy gate (the carry-forward): an
        // archived or OTA-disabled application serves nothing — the
        // same uniform 204 as every other refusal leg.
        if (application == null || "ARCHIVED".equals(application.status()) || !application.otaEnabled()) {
            return noUpdate(Outcome.NOT_ELIGIBLE);
        }
        OtaChannelRelease channelRelease = releases.otaChannelReleaseOf(application.applicationId(), query.channel())
                .orElse(null);
        if (channelRelease == null || !channelRelease.facts().organizationId().equals(principal.organizationId())) {
            return noUpdate(Outcome.NO_ACTIVE_RELEASE);
        }
        UUID releaseId = channelRelease.facts().releaseId();
        OtaBundleEntity bundle = bundles.findByReleaseIdAndStatus(releaseId, OtaBundleEntity.Status.RELEASED)
                .orElse(null);
        if (bundle == null || bundle.getSignature() == null || bundle.getKeyId() == null) {
            return noUpdate(Outcome.NO_ACTIVE_RELEASE);
        }
        // The ring's device axes (S16-05): the application's own
        // registered identifier is the bundle-id axis's report (the
        // sdc1. resolution already fixed WHICH application — the axis
        // narrows within it), the device's platform tag the second.
        Boolean admitted = eligibility
                .admitsDevice(releaseId, bundleIdentifierOf(application, query.platform()), query.platform())
                .orElse(false);
        if (!admitted) {
            return noUpdate(Outcome.NOT_ELIGIBLE);
        }
        // Up-to-date by label or by content hash: the device skips
        // what it already runs, whatever the label re-publish called it.
        if (isUpToDate(application.applicationId(), bundle, query.bundleVersion())) {
            return noUpdate(Outcome.UP_TO_DATE);
        }
        RolloutStanding standing = rollouts.rolloutStandingOf(application.applicationId(), releaseId)
                .orElse(null);
        if (standing == null) {
            // A live release without its rollout plan serves nothing:
            // the plan (even a single full-space stage) is the
            // operator's deliberate act that opens the funnel.
            return noUpdate(Outcome.NO_ROLLOUT);
        }
        if ("HALTED".equals(standing.status()) || "PAUSED".equals(standing.status())) {
            return noUpdate(Outcome.HALTED);
        }
        if (!OtaDeviceBucketing.servesDevice(query.deviceId(), standing.planSalt(), standing.currentBasisPoints())) {
            return noUpdate(Outcome.BELOW_STAGE);
        }
        // The funnel grain the ingest grant and the check row share:
        // the release's first target tenant (deterministic, and the
        // tenant the ingest's release-target resolution accepts).
        UUID reportingTenant = releases.distributionTargetTenantIdsOf(releaseId).stream()
                .min(UUID::compareTo)
                .orElse(null);
        if (reportingTenant == null) {
            return noUpdate(Outcome.NO_ACTIVE_RELEASE);
        }
        Manifest manifest = serve(principal, application, channelRelease, bundle, reportingTenant);
        return Optional.of(manifest);
    }

    /**
     * The serving half: mints the two credentials, lands the one
     * {@code OTA_CHECK} row (D9 — this is the only OTA outcome whose
     * release is known), and counts the outcome. The manifest is the
     * answer only if every write succeeded; the recorder never gates
     * the answer on the meters.
     */
    private Manifest serve(
            OtaAppTokenPrincipal principal,
            ApplicationProfile application,
            OtaChannelRelease channelRelease,
            OtaBundleEntity bundle,
            UUID reportingTenant) {
        UUID releaseId = channelRelease.facts().releaseId();
        OtaBundleArtifacts.BundleDownloadUrl download = bundleArtifacts.issueBundleDownloadUrl(
                principal.tokenId(), bundle.getArtifactId(), bundle.getVersionId());
        String ingestToken = telemetryTokens.mint(
                telemetryTokens.grantForNow(reportingTenant, principal.tokenId(), releaseId, null));
        boolean forced = channelRelease.forcedOverride() != null ? channelRelease.forcedOverride() : bundle.isForced();
        String minAppVersion = channelRelease.minAppVersionOverride() != null
                ? channelRelease.minAppVersionOverride()
                : bundle.getMinAppVersion();
        checkEvents.recordCheck(new OtaCheckEventRecorder.OtaCheckEvent(
                principal.organizationId(),
                reportingTenant,
                application.applicationId(),
                bundle.getVersionId(),
                releaseId,
                bundle.getId(),
                Outcome.UPDATE_AVAILABLE.wireName(),
                Instant.now(clock)));
        count(Outcome.UPDATE_AVAILABLE);
        return new Manifest(
                bundle.getBundleVersion(),
                download.url(),
                bundle.getSha256(),
                bundle.getSignature(),
                bundle.getKeyId(),
                bundle.getSizeBytes(),
                forced,
                minAppVersion,
                ingestToken,
                properties.pollIntervalSeconds());
    }

    /**
     * The up-to-date comparison (D2/D5): the reported label equals the
     * candidate's, or any bundle of the application the label ever
     * named carries the candidate's content hash — a re-published,
     * re-labeled copy of bytes the device already runs is not an
     * update.
     */
    private boolean isUpToDate(UUID applicationId, OtaBundleEntity candidate, String reportedVersion) {
        if (reportedVersion == null || reportedVersion.isBlank()) {
            return false;
        }
        if (candidate.getBundleVersion().equals(reportedVersion.trim())) {
            return true;
        }
        return bundles.findByApplicationIdAndBundleVersionOrderByIdAsc(applicationId, reportedVersion.trim()).stream()
                .anyMatch(named ->
                        candidate.getSha256() != null && candidate.getSha256().equals(named.getSha256()));
    }

    /**
     * The bundle-identifier axis's report: the application's own
     * registered identifier — the package name for an Android-tagged
     * device, the bundle id otherwise (the S3-01 registry rule; the
     * open tag vocabulary's non-mobile members ride whichever
     * identifier the application registered).
     */
    private static String bundleIdentifierOf(ApplicationProfile application, String platformTag) {
        if ("android".equals(platformTag) && application.packageName() != null) {
            return application.packageName();
        }
        return application.bundleId() != null ? application.bundleId() : application.packageName();
    }

    /** Counts one terminal outcome of the check funnel (D9). */
    private void count(Outcome outcome) {
        meters.counter(MetricNames.OTA_CHECK_COUNT, "outcome", outcome.wireName())
                .increment();
    }

    private Optional<Manifest> noUpdate(Outcome outcome) {
        count(outcome);
        return Optional.empty();
    }

    /**
     * The check query as the controller parsed and validated it: the
     * raw device identifier (the bucketing's contract — never trimmed
     * or normalized after validation), the normalized platform tag
     * and channel, the app version report, and the optional bundle
     * version label the device runs.
     */
    public record CheckQuery(
            String deviceId, String platform, String appVersion, String bundleVersion, String channel) {}

    /**
     * The OTA manifest (issue #169's response contract): the bundle's
     * version label and binary facts (checksum, signature + key of
     * D8, size), the minted download URL (D5) and ingest grant (D4),
     * the effective {@code forced}/{@code minAppVersion} (D7 overrides
     * applied), and the server-owned poll cadence (D9).
     */
    public record Manifest(
            String bundleVersion,
            String url,
            String sha256,
            String signature,
            String keyId,
            Long sizeBytes,
            boolean forced,
            String minAppVersion,
            String ingestToken,
            long pollIntervalSeconds) {}

    /** The check funnel's outcomes (the meter tag values; D9). */
    enum Outcome {
        UPDATE_AVAILABLE("update_available"),
        UP_TO_DATE("up_to_date"),
        NOT_ELIGIBLE("not_eligible"),
        HALTED("halted"),
        NO_ACTIVE_RELEASE("no_active_release"),
        NO_ROLLOUT("no_rollout"),
        BELOW_STAGE("below_stage");

        private final String wireName;

        Outcome(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }

    /**
     * The query surface's validation: every bound is the neighboring
     * surface's own (the bucketing's device id, V55's platform tag,
     * the ring-code vocabulary), and the refusals are the module's
     * field-addressed 400 — never a 204, which would silently
     * mis-bucket a malformed report.
     */
    public static CheckQuery validate(
            String deviceId, String platform, String appVersion, String bundleVersion, String channel) {
        Map<String, String> errors = new LinkedHashMap<>();
        String rawDevice = require(deviceId, "deviceId", DEVICE_ID_MAX, errors);
        String tag = normalized(platform, "platform", PLATFORM_MAX, errors);
        String version = require(appVersion, "appVersion", APP_VERSION_MAX, errors);
        String bundle = null;
        if (bundleVersion != null && !bundleVersion.isBlank()) {
            bundle = require(bundleVersion, "bundleVersion", BUNDLE_VERSION_MAX, errors);
        }
        String normalizedChannel = normalized(channel, "channel", CHANNEL_MAX, errors);
        if (!errors.isEmpty()) {
            throw new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, "the check query is invalid", errors);
        }
        return new CheckQuery(rawDevice, tag, version, bundle, normalizedChannel);
    }

    /** A required bounded string: trimmed, non-blank, at most {@code max} characters. */
    private static String require(String value, String field, int max, Map<String, String> errors) {
        if (value == null) {
            errors.put(field, field + " is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            errors.put(field, field + " is required");
            return null;
        }
        if (trimmed.length() > max) {
            errors.put(field, field + " is at most " + max + " characters");
            return null;
        }
        return trimmed;
    }

    /** A required bounded lowercase token (the tag and channel vocabularies' normalization). */
    private static String normalized(String value, String field, int max, Map<String, String> errors) {
        String required = require(value, field, max, errors);
        return required == null ? null : required.toLowerCase(Locale.ROOT);
    }
}
