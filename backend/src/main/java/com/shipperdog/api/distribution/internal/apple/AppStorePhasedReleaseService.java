package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.AdapterCallSandbox;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.release.ReleaseAccess;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The App Store phased-release surface (issue #63, S13-03, AC3,
 * FR-020, PRD 17): the release actions that govern Apple's fixed
 * 7-day availability curve (1, 2, 5, 10, 20, 50, 100 percent) of an
 * approved production version — the curve's progress for the release
 * dashboard, and pause/resume/halt as release actions, run under the
 * SAME sandbox every adapter conversation runs under.
 *
 * <p><strong>The rollback posture (the story's documented
 * response):</strong> Apple offers no binary rollback on the App
 * Store — a phased release that must stop answers HALT, Apple's
 * "release to all users", which completes the curve immediately; the
 * platform's own response to a bad binary is a new version (and, for
 * the platform's Direct tier, the release's own ROLLED_BACK edge,
 * S13-06's orchestration). This service documents the posture in
 * code: HALT is a forward-completing control, never a reversal.
 *
 * <p>The service resolves the release's appstore submission record
 * (the ASC version id is the curve's handle), resolves the credential
 * chain AT the release's scope exactly as the dispatch does, and
 * talks to ASC through the shared client. The channel's adapter keeps
 * the dispatch and status conversations; rollout control is this
 * channel service's, the TestFlight-group-service precedent.
 */
@Service
public class AppStorePhasedReleaseService {

    private static final String CHANNEL = "appstore";

    /** The pause action: the curve holds at its current day. */
    public static final String ACTION_PAUSE = "PAUSE";

    /** The resume action: the curve continues from its current day. */
    public static final String ACTION_RESUME = "RESUME";

    /** The halt action: the curve completes immediately (release to all users — the documented rollback posture). */
    public static final String ACTION_HALT = "HALT";

    /** Apple's phased-release day to availability percentage (the fixed 7-day curve). */
    private static final int[] CURVE_PERCENTAGE = {1, 2, 5, 10, 20, 50, 100};

    private final ChannelDistributionRepository records;
    private final DistributionCredentialService credentials;
    private final ChannelSecretStore secrets;
    private final ReleaseAccess releases;
    private final AppStoreConnectClient asc;
    private final AdapterCallSandbox sandbox;
    private final AuditLogger audit;
    private final Clock clock;

    public AppStorePhasedReleaseService(
            ChannelDistributionRepository records,
            DistributionCredentialService credentials,
            ChannelSecretStore secrets,
            ReleaseAccess releases,
            AppStoreConnectClient asc,
            AdapterCallSandbox sandbox,
            AuditLogger audit,
            Clock clock) {
        this.records = records;
        this.credentials = credentials;
        this.secrets = secrets;
        this.releases = releases;
        this.asc = asc;
        this.sandbox = sandbox;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The curve's progress for the release dashboard (AC3): the
     * provider's phased-release state, the curve's current day and
     * total, the day's availability percentage, and the version's own
     * review state beside it. A release whose version carries no
     * phased release answers the honest
     * {@code DISTRIBUTION_PHASED_RELEASE_NOT_FOUND} — an invented
     * curve would be a lie a dashboard renders.
     */
    @Transactional(readOnly = true)
    public PhasedReleaseView view(UUID organizationId, UUID releaseId) {
        ChannelDistributionEntity record = submissionRecordOf(organizationId, releaseId);
        AppleCredentials credential = credentialOf(record);
        AdapterCallSandbox.Result<PhasedReleaseView> observed =
                sandbox.run(CHANNEL, "phased-release-view", attempt -> readCurve(credential, record.getExternalId()));
        if (observed.failure() != null) {
            throw problemOf(observed.failure());
        }
        return observed.answer();
    }

    /**
     * One release action on the curve (AC3): pause, resume, or halt,
     * applied to the provider and answered with the curve's refreshed
     * state. Audited with the action and the resulting state.
     */
    @Transactional
    public PhasedReleaseView control(UUID organizationId, UUID releaseId, String action, UUID actorId) {
        String providerState = switch (action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT)) {
            case ACTION_PAUSE -> "PAUSED";
            case ACTION_RESUME -> "ACTIVE";
            case ACTION_HALT -> "COMPLETE";
            default ->
                throw new DistributionException(
                        DistributionException.Code.DISTRIBUTION_PHASED_RELEASE_ACTION_INVALID,
                        "the phased release action must be one of PAUSE, RESUME, or HALT");
        };
        ChannelDistributionEntity record = submissionRecordOf(organizationId, releaseId);
        AppleCredentials credential = credentialOf(record);
        String versionId = record.getExternalId();
        AdapterCallSandbox.Result<String> controlled = sandbox.run(CHANNEL, "phased-release-control", attempt -> {
            AppStoreConnectClient.PhasedRelease phased = asc.phasedReleaseOf(credential, versionId)
                    .orElseThrow(() -> new DistributionException(
                            DistributionException.Code.DISTRIBUTION_PHASED_RELEASE_NOT_FOUND,
                            "the version carries no phased release on the store side"));
            asc.updatePhasedReleaseState(credential, phased.id(), providerState);
            return providerState;
        });
        if (controlled.failure() != null) {
            throw problemOf(controlled.failure());
        }
        PhasedReleaseView refreshed = view(organizationId, releaseId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("releaseId", releaseId.toString());
        detail.put("action", action.trim().toUpperCase(java.util.Locale.ROOT));
        detail.put("providerState", refreshed.phasedReleaseState() == null ? "" : refreshed.phasedReleaseState());
        AuditEvent.Builder line = AuditEvent.builder(AuditEventType.DISTRIBUTION_PHASED_RELEASE_CONTROLLED)
                .orgId(organizationId)
                .applicationId(applicationIdOf(record))
                .detail(detail)
                .success(true);
        if (actorId != null) {
            line.userId(actorId);
        }
        audit.record(line.build());
        return refreshed;
    }

    /**
     * The orchestrated rollback's App Store arm (S13-06, issue #66,
     * AC4): HALT the phased curve where one runs — the documented
     * posture (no binary rollback exists on the App Store; HALT is
     * Apple's own stop). Tolerant by design: a release with no
     * submission record, no curve, or a halt the store refuses each
     * answer their honest verdict, never a throw — the rollback's
     * other arms are not this curve's to veto, and the outcome
     * vocabulary records what happened for the timeline.
     *
     * @return the arm's outcome: halted, no-submission-record,
     *         no-phased-curve, or halt-failed:{@code CODE}
     */
    @Transactional
    public String haltForStagedRollback(UUID organizationId, UUID releaseId, UUID actorId) {
        try {
            control(organizationId, releaseId, ACTION_HALT, actorId);
            return "halted";
        } catch (DistributionException e) {
            if (e.code() == DistributionException.Code.DISTRIBUTION_RECORD_NOT_FOUND) {
                return "no-submission-record";
            }
            if (e.code() == DistributionException.Code.DISTRIBUTION_PHASED_RELEASE_NOT_FOUND) {
                return "no-phased-curve";
            }
            return "halt-failed:" + e.code().codeValue();
        }
    }

    private PhasedReleaseView readCurve(AppleCredentials credential, String versionId) {
        AppStoreConnectClient.AppVersionState version = asc.versionStateOf(credential, versionId);
        AppStoreConnectClient.PhasedRelease phased =
                asc.phasedReleaseOf(credential, versionId).orElse(null);
        if (phased == null) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_PHASED_RELEASE_NOT_FOUND,
                    "the version carries no phased release on the store side (the release did not configure one)");
        }
        int day = Math.min(phased.currentDay(), CURVE_PERCENTAGE.length);
        int percentage = day <= 0 ? 0 : CURVE_PERCENTAGE[day - 1];
        return new PhasedReleaseView(
                versionId,
                version.state(),
                phased.state(),
                phased.currentDay(),
                phased.totalDays(),
                percentage,
                InstantOf(clock));
    }

    private static java.time.Instant InstantOf(Clock clock) {
        return java.time.Instant.now(clock);
    }

    /** The release's one appstore submission record (concealed across organizations like every record surface). */
    private ChannelDistributionEntity submissionRecordOf(UUID organizationId, UUID releaseId) {
        List<ChannelDistributionEntity> rows = records.findByReleaseIdAndOrganizationIdAndChannelOrderByCreatedAtAsc(
                releaseId, organizationId, CHANNEL);
        ChannelDistributionEntity withExternal = rows.stream()
                .filter(row -> row.getExternalId() != null)
                .findFirst()
                .orElse(null);
        if (withExternal == null) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_RECORD_NOT_FOUND,
                    "the release carries no App Store submission record with a store-side version id");
        }
        return withExternal;
    }

    private AppleCredentials credentialOf(ChannelDistributionEntity record) {
        ReleaseAccess.ReleaseFacts facts =
                releases.distributionFactsOf(record.getReleaseId()).orElse(null);
        UUID applicationId = facts == null ? null : facts.applicationId();
        UUID environmentId = facts == null ? null : facts.environmentId();
        DistributionCommand.ResolvedCredential resolved = credentials
                .resolve(record.getOrganizationId(), applicationId, environmentId, "apple")
                .map(account -> {
                    SecretMaterial material =
                            secrets.resolve(account.getSecretReference()).orElse(new SecretMaterial(""));
                    return new DistributionCommand.ResolvedCredential(
                            account.getProvider(),
                            account.getName(),
                            account.getSecretReference(),
                            material,
                            account.getMetadata());
                })
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                        "no credential is bound on the delegation chain for this release's channel and scope"));
        AppleCredentials credential = AppleCredentials.of(resolved);
        if (!credential.complete()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                    "the resolved apple credential is incomplete (issuerId and keyId in the binding metadata,"
                            + " the .p8 as material)");
        }
        return credential;
    }

    private UUID applicationIdOf(ChannelDistributionEntity record) {
        return releases.distributionFactsOf(record.getReleaseId())
                .map(ReleaseAccess.ReleaseFacts::applicationId)
                .orElse(null);
    }

    /** The taxonomy's problem code for a sandbox failure (the same closed mapping everywhere). */
    private static DistributionException problemOf(AdapterException failure) {
        return new DistributionException(AdapterCallSandbox.taxonomyCodeOf(failure), failure.getMessage());
    }

    /** The curve's answer for the release dashboard. */
    public record PhasedReleaseView(
            String versionId,
            String versionState,
            String phasedReleaseState,
            int currentDay,
            int totalDays,
            int percentage,
            java.time.Instant observedAt) {}
}
