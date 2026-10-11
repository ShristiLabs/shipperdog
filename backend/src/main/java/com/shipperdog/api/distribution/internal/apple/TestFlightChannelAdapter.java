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
 * The TestFlight channel's adapter (issue #62, S13-02, FR-020, PRD
 * 19): the first store connector on the S13-01 SPI — an iOS release
 * promoted to ACTIVE on an environment bound to {@code testflight}
 * lands its ASC build in the mapped TestFlight beta groups. The build
 * itself was uploaded by CI or Xcode Cloud (the story's note: the
 * adapter distributes EXISTING ASC builds, matching PRD 20's "build
 * upload where applicable"); this adapter binds the ShipperDog
 * artifact to its ASC peer by the binary's own facts (bundle id from
 * the app registry's slot, the artifact's extracted build number) and
 * runs the assignment.
 *
 * <p><strong>What a dispatch does:</strong> resolve the ASC app by
 * bundle id, resolve each iOS artifact's build by build number, and
 * assign the build to every TARGET GROUP whose channel link the
 * framework resolved (the mapping surface of AC2 owns links and
 * membership; dispatch assigns builds only — a group without a link
 * is the mapping surface's gap, recorded loudly as
 * {@code TESTFLIGHT_GROUPS_UNMAPPED}, never a silent skip). The
 * outcome is {@code DISTRIBUTING} with the ASC build id as the
 * external id: Apple owns the next step (processing, then beta
 * review for external groups), and the record's provider status
 * carries Apple's own state word for the release dashboard (AC4).
 *
 * <p><strong>The sync-in-transaction seam (#61's recorded
 * decision):</strong> this dispatch runs inside the ACTIVE crossing's
 * committing transaction — three ASC calls per group against the
 * sandbox's 30 s wall, tolerable for the group counts a ring names.
 * When real-Apple latencies demand it, the sandbox call moves onto
 * the bus; the listener, records, and this adapter stay.
 */
@Component
public class TestFlightChannelAdapter implements DistributionChannelAdapter {

    /** The record's failure code when the release's target groups carry no channel links (a mapping-surface gap). */
    static final String FAILURE_GROUPS_UNMAPPED = "TESTFLIGHT_GROUPS_UNMAPPED";

    /** The record's failure code when no ASC build matches the artifact's own facts (CI never uploaded it). */
    static final String FAILURE_BUILD_NOT_FOUND = "TESTFLIGHT_BUILD_NOT_FOUND";

    /** The record's failure code for a non-iOS slot (Apple's channel owns iOS installs only, the routing table's own rule). */
    static final String FAILURE_IOS_ONLY = "TESTFLIGHT_IOS_ONLY";

    /** The record's failure code when Apple's beta review rejected the build. */
    static final String FAILURE_BUILD_REJECTED = "TESTFLIGHT_BUILD_REJECTED";

    /** The record's failure code when the build expired or was removed from TestFlight. */
    static final String FAILURE_BUILD_EXPIRED = "TESTFLIGHT_BUILD_EXPIRED";

    private final AppStoreConnectClient asc;

    public TestFlightChannelAdapter(AppStoreConnectClient asc) {
        this.asc = asc;
    }

    @Override
    public String channelId() {
        return "testflight";
    }

    @Override
    public String displayName() {
        return "Apple TestFlight";
    }

    @Override
    public String description() {
        return "Distributes existing App Store Connect builds to TestFlight beta groups through the official"
                + " ASC API (ES256 API key): group mapping, tester sync, and build assignment with beta-review"
                + " status observation. Build upload stays with CI or Xcode Cloud.";
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return Set.of(AdapterCapability.BUILD_STATUS, AdapterCapability.TESTER_GROUP_SYNC);
    }

    @Override
    public Set<String> credentialProviders() {
        return Set.of("apple");
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
        List<DistributionCommand.TargetGroup> mapped = command.targetGroups().stream()
                .filter(group -> group.externalGroupId() != null)
                .toList();
        List<AdapterOutcome> outcomes = new ArrayList<>();
        for (DistributionCommand.ArtifactSlot slot : command.artifacts()) {
            outcomes.add(distributeSlot(command, credential, slot, mapped));
        }
        return outcomes;
    }

    private AdapterOutcome distributeSlot(
            DistributionCommand command,
            AppleCredentials credential,
            DistributionCommand.ArtifactSlot slot,
            List<DistributionCommand.TargetGroup> mapped) {
        if (!"IOS".equals(slot.platform())) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_IOS_ONLY,
                    "TestFlight serves iOS builds only");
        }
        if (slot.bundleId() == null || slot.buildNumber() == null) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_BUILD_NOT_FOUND,
                    "the artifact carries no extracted bundle id or build number to resolve its ASC build");
        }
        if (command.targetGroups().isEmpty()) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_GROUPS_UNMAPPED,
                    "the release targets no tester groups; TestFlight distributes through beta groups");
        }
        if (mapped.isEmpty()) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_GROUPS_UNMAPPED,
                    "none of the release's target tester groups are linked to a TestFlight beta group"
                            + " (link them on the distribution group-mapping surface)");
        }
        String appId = asc.appIdOfBundleId(credential, slot.bundleId())
                .orElseThrow(() -> new AdapterException(
                        AdapterException.Category.PERMANENT,
                        channelId(),
                        "no App Store Connect app matches bundle id of this release"));
        AppStoreConnectClient.BetaBuild build =
                asc.buildOf(credential, appId, slot.buildNumber()).orElse(null);
        if (build == null) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_BUILD_NOT_FOUND,
                    "no ASC build matches build number " + slot.buildNumber()
                            + "; CI or Xcode Cloud must upload the build before promotion");
        }
        for (DistributionCommand.TargetGroup group : mapped) {
            asc.assignBuildToGroup(credential, group.externalGroupId(), build.id());
        }
        String providerStatus = providerStatusOf(credential, build);
        return new AdapterOutcome(
                slot.artifactId(), AdapterOutcome.DISTRIBUTING, build.id(), null, null, providerStatus);
    }

    /**
     * The provider-side status of one distributed artifact (AC4's
     * refresh half): Apple's own beta state word drives the record —
     * {@code READY_FOR_BETA_TESTING}/{@code IN_BETA_TESTING} is
     * DISTRIBUTED (the provider side is done), the review/processing
     * words are DISTRIBUTING (Apple owns the next step), and the
     * rejected/expired words are FAILED with the honest code.
     */
    @Override
    public AdapterOutcome status(
            UUID artifactId, String channel, String externalId, DistributionCommand.ResolvedCredential resolved) {
        AppleCredentials credential = resolved == null ? null : AppleCredentials.of(resolved);
        if (credential == null || !credential.complete()) {
            // The refresh's honest answer when the chain resolves no
            // credential: a configuration fault, never a dial (and
            // never an NPE masquerading as a provider failure).
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the apple credential is unresolvable or incomplete (issuerId and keyId in the binding"
                            + " metadata, the .p8 as material)");
        }
        AppStoreConnectClient.BetaStates states = asc.betaStatesOf(credential, externalId);
        String external = states.externalBuildState();
        String providerStatus = external != null ? external : states.internalBuildState();
        if (external == null) {
            return new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTING, externalId, null, null, providerStatus);
        }
        return switch (external) {
            case "READY_FOR_BETA_TESTING", "IN_BETA_TESTING" ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTED, externalId, null, null, providerStatus);
            case "BETA_REJECTED" ->
                new AdapterOutcome(
                        artifactId,
                        AdapterOutcome.FAILED,
                        externalId,
                        FAILURE_BUILD_REJECTED,
                        "Apple's beta review rejected this build",
                        providerStatus);
            case "EXPIRED", "REMOVED" ->
                new AdapterOutcome(
                        artifactId,
                        AdapterOutcome.FAILED,
                        externalId,
                        FAILURE_BUILD_EXPIRED,
                        "the build expired or was removed from TestFlight",
                        providerStatus);
            default ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTING, externalId, null, null, providerStatus);
        };
    }

    private String providerStatusOf(AppleCredentials credential, AppStoreConnectClient.BetaBuild build) {
        try {
            AppStoreConnectClient.BetaStates states = asc.betaStatesOf(credential, build.id());
            return states.externalBuildState() != null
                    ? states.externalBuildState()
                    : build.processingState() != null ? build.processingState() : states.internalBuildState();
        } catch (AdapterException e) {
            // The state observation is guidance, not the assignment's
            // verdict: a fault here leaves the outcome without a
            // provider word rather than failing the dispatch.
            return build.processingState();
        }
    }
}
