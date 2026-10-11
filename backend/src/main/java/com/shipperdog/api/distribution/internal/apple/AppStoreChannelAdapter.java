package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import com.shipperdog.api.distribution.DistributionCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The App Store production channel's adapter (issue #63, S13-03,
 * FR-020, PRD 16/17/19): the second Apple connector on the S13-01 SPI
 * — an iOS release promoted to ACTIVE on an environment bound to
 * {@code appstore} creates or updates the ASC App Store version of
 * the binary's own marketing version string, writes the frozen
 * per-locale listing metadata the release manifest carries, starts
 * the phased-release curve when the release configured one, and
 * submits the version for App Review. The submission's source of
 * truth is THE FROZEN MANIFEST BLOCK the framework resolved into the
 * command (PRD 16's immutable copy of what was sent) — never live
 * authoring rows, so the manifest and the wire can never drift.
 *
 * <p><strong>The doctrine split of S13-03 (AC4):</strong> the #61/#62
 * rule — store failures never fail the crossing — holds for every
 * channel whose platform-side availability stands alone (Direct,
 * TestFlight). The production submission channel is different: its
 * deliverable IS the store's acceptance, so a PERMANENT submission
 * refusal (metadata rejected — Apple's own 4xx words) vetoes the
 * ACTIVE crossing and lands the release on PRD 17's DEPLOYING to
 * FAILED edge with the provider's actionable reason; the framework's
 * dispatch listener performs the veto (see DistributionDispatchService).
 * Retryable exhaustion keeps the record-level doctrine.
 *
 * <p><strong>What a dispatch does:</strong> resolve the ASC app by
 * bundle id; find-or-create the App Store version of the artifact's
 * marketing version string (one editable version per platform — a
 * re-dispatch updates the same version, never a second one); upsert
 * every frozen locale's listing facts; set the release type for the
 * phased-release decision; enable the curve when configured; submit
 * for review. The outcome is {@code DISTRIBUTING} with the ASC version
 * id as the external id and Apple's own review state word as the
 * provider status — Apple owns the next step, and the review journey
 * (WAITING_FOR_REVIEW to READY_FOR_SALE or REJECTED) surfaces through
 * the refresh and the webhook-driven sync (AC2).
 */
@Component
public class AppStoreChannelAdapter implements DistributionChannelAdapter {

    /** The record's failure code for a non-iOS slot (the App Store serves iOS only). */
    static final String FAILURE_IOS_ONLY = "APPSTORE_IOS_ONLY";

    /** The record's failure code when the artifact carries no version facts to address its ASC version by. */
    static final String FAILURE_VERSION_FACTS_MISSING = "APPSTORE_VERSION_FACTS_MISSING";

    /** The record's failure code when no submission metadata was frozen for the release (author before approval). */
    static final String FAILURE_METADATA_MISSING = "APPSTORE_METADATA_MISSING";

    /** The record's failure code when no ASC app matches the artifact's bundle id. */
    static final String FAILURE_APP_NOT_FOUND = "APPSTORE_APP_NOT_FOUND";

    /** The record's failure code when Apple's App Review rejected the version. */
    static final String FAILURE_VERSION_REJECTED = "APPSTORE_VERSION_REJECTED";

    /** The record's failure code when the version was removed from the App Store. */
    static final String FAILURE_VERSION_REMOVED = "APPSTORE_VERSION_REMOVED";

    /** Apple's release type when the phased-release curve governs availability. */
    static final String RELEASE_TYPE_AFTER_APPROVAL = "AFTER_APPROVAL";

    /** Apple's release type when the operator releases the approved version by hand. */
    static final String RELEASE_TYPE_MANUAL = "MANUAL";

    private final AppStoreConnectClient asc;

    public AppStoreChannelAdapter(AppStoreConnectClient asc) {
        this.asc = asc;
    }

    @Override
    public String channelId() {
        return "appstore";
    }

    @Override
    public String displayName() {
        return "Apple App Store (production)";
    }

    @Override
    public String description() {
        return "Submits the release's App Store version with its frozen listing metadata for App Review"
                + " through the official ASC API (ES256 API key), starts Apple's 7-day phased-release curve"
                + " when configured, and surfaces the review journey. Screenshot assets upload through"
                + " ASC's own surfaces; the manifest pins their references.";
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return Set.of(AdapterCapability.SUBMISSION, AdapterCapability.ROLLOUT_CONTROL, AdapterCapability.BUILD_STATUS);
    }

    @Override
    public Set<String> credentialProviders() {
        return Set.of("apple");
    }

    /**
     * The channel's one dispatch IS the submission (the #63 doctrine
     * split, unchanged by S13-05's ring grading): every ring this
     * channel dispatches at is submission-grade, so a permanent
     * refusal of the store vetoes the crossing it rode on.
     */
    @Override
    public boolean submitsAtRing(String ringCode) {
        return true;
    }

    @Override
    public List<AdapterOutcome> distribute(DistributionCommand command) {
        AppleCredentials credential = AppleCredentials.of(command.credential());
        if (!credential.complete()) {
            // An incomplete triple (issuer id / key id / .p8) is a
            // configuration fault, not a provider conversation: never
            // retried, never dialed.
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the apple credential is incomplete (issuerId and keyId in the binding metadata, the .p8 as material)");
        }
        if (command.submission() == null || command.submission().locales().isEmpty()) {
            // The submission's honest precondition: no frozen listing
            // metadata means nothing to submit. PERMANENT — the veto
            // doctrine lands the release DEPLOYING to FAILED with this
            // actionable reason (AC4).
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "no store-submission metadata is frozen for this release (author the channel's per-locale"
                            + " listing metadata before approval — the manifest freezes it at the APPROVED crossing)");
        }
        List<AdapterOutcome> outcomes = new ArrayList<>();
        for (DistributionCommand.ArtifactSlot slot : command.artifacts()) {
            outcomes.add(distributeSlot(command, credential, slot));
        }
        return outcomes;
    }

    private AdapterOutcome distributeSlot(
            DistributionCommand command, AppleCredentials credential, DistributionCommand.ArtifactSlot slot) {
        if (!"IOS".equals(slot.platform())) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_IOS_ONLY,
                    "the App Store serves iOS builds only");
        }
        if (slot.bundleId() == null || slot.versionName() == null) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_VERSION_FACTS_MISSING,
                    "the artifact carries no extracted bundle id or marketing version string to address its"
                            + " ASC App Store version by");
        }
        String appId = asc.appIdOfBundleId(credential, slot.bundleId())
                .orElseThrow(() -> new AdapterException(
                        AdapterException.Category.PERMANENT,
                        channelId(),
                        "no App Store Connect app matches bundle id of this release (" + slot.bundleId() + ")"));
        // Find-or-create: one editable IOS version per version string —
        // a re-dispatch updates the same version ASC already holds.
        AppStoreConnectClient.AppVersion version = asc.versionOf(credential, appId, slot.versionName())
                .orElseGet(() -> asc.createVersion(credential, appId, slot.versionName()));
        for (DistributionCommand.SubmissionLocale locale : command.submission().locales()) {
            asc.putLocalization(
                    credential,
                    version.id(),
                    locale.locale(),
                    locale.description(),
                    locale.keywords(),
                    locale.releaseNotes());
        }
        boolean phased = command.submission().phasedRelease();
        asc.updateVersionReleaseType(
                credential, version.id(), phased ? RELEASE_TYPE_AFTER_APPROVAL : RELEASE_TYPE_MANUAL);
        if (phased) {
            asc.enablePhasedRelease(credential, version.id());
        }
        asc.submitVersionForReview(credential, version.id());
        // The first observation of the journey the refresh walks.
        String providerStatus = providerStatusOf(credential, version);
        return new AdapterOutcome(
                slot.artifactId(), AdapterOutcome.DISTRIBUTING, version.id(), null, null, providerStatus);
    }

    /**
     * The provider-side status of one submitted version (AC2's refresh
     * half): Apple's own review state word drives the record —
     * {@code READY_FOR_SALE} is DISTRIBUTED (the store serves the
     * binary; the phased curve governs how widely, which the curve
     * view answers), the review words are DISTRIBUTING (Apple owns the
     * next step), and REJECTED fails with Apple's own reasons carried
     * as the actionable detail (AC4).
     */
    @Override
    public AdapterOutcome status(
            UUID artifactId, String channel, String externalId, DistributionCommand.ResolvedCredential resolved) {
        AppleCredentials credential = resolved == null ? null : AppleCredentials.of(resolved);
        if (credential == null || !credential.complete()) {
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the apple credential is unresolvable or incomplete (issuerId and keyId in the binding"
                            + " metadata, the .p8 as material)");
        }
        AppStoreConnectClient.AppVersionState state = asc.versionStateOf(credential, externalId);
        String providerStatus = state.state();
        return switch (providerStatus == null ? "" : providerStatus) {
            case "READY_FOR_SALE" ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTED, externalId, null, null, providerStatus);
            case "REJECTED" ->
                new AdapterOutcome(
                        artifactId,
                        AdapterOutcome.FAILED,
                        externalId,
                        FAILURE_VERSION_REJECTED,
                        state.rejectionReasons().isEmpty()
                                ? "Apple's App Review rejected this version"
                                : "Apple's App Review rejected this version: "
                                        + String.join("; ", state.rejectionReasons()),
                        providerStatus);
            case "REMOVED", "DEVELOPER_REMOVED" ->
                new AdapterOutcome(
                        artifactId,
                        AdapterOutcome.FAILED,
                        externalId,
                        FAILURE_VERSION_REMOVED,
                        "the version was removed from the App Store",
                        providerStatus);
            default ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTING, externalId, null, null, providerStatus);
        };
    }

    private String providerStatusOf(AppleCredentials credential, AppStoreConnectClient.AppVersion version) {
        try {
            AppStoreConnectClient.AppVersionState state = asc.versionStateOf(credential, version.id());
            return state.state() != null ? state.state() : version.state();
        } catch (AdapterException e) {
            // The state observation is guidance, not the submission's
            // verdict: a fault here leaves the outcome without a
            // provider word rather than failing the dispatch.
            return version.state();
        }
    }
}
