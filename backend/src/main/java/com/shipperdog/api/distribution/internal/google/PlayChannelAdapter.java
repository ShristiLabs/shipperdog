package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import com.shipperdog.api.distribution.DistributionCommand;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Google Play testing-track channel's adapter (issue #64, S13-04,
 * FR-021, PRD 19/25): the first Android connector on the S13-01 SPI —
 * an Android release promoted to ACTIVE on an environment bound to
 * {@code play} uploads its AAB through Play's two-phase edit model
 * (upload the bundle into an edit, write the mapped track's release,
 * commit), the track chosen by the ring-to-track mapping (the
 * environment's {@code play-track} configuration key overrides the
 * ring-code default), the release notes THE FROZEN MANIFEST BLOCK
 * carries, and the record lands DISTRIBUTING with the track release
 * as its external id — Google owns the next step, and the track's
 * journey surfaces through the refresh legs.
 *
 * <p><strong>The boundary this story holds (S13-05 owns the rest):</strong>
 * the testing tracks only — internal and closed. A mapping that
 * resolves to the production track is refused loudly
 * ({@code PLAY_PRODUCTION_UNSUPPORTED}): production promotion and its
 * staged-rollout percentages are the NEXT story's surface, and an
 * accidental production release through a default mapping would be
 * the one mistake this channel cannot make quietly. The channel
 * therefore declares no SUBMISSION capability — the #61/#62
 * never-fail-the-crossing doctrine holds (store failures write
 * FAILED records; the release's platform-side availability stands
 * alone).
 *
 * <p><strong>The format rule at the adapter boundary (AC3, FR-006):</strong>
 * the Play channel serves AAB artifacts only. An APK slot fails its
 * record with {@code PLAY_AAB_REQUIRED} — the mirror of the Direct
 * channel's APK-only rule for Android, enforced exactly where the
 * provider conversation would begin.
 */
@Component
public class PlayChannelAdapter implements DistributionChannelAdapter {

    /** The record's failure code for a non-Android slot (the Play channel owns Android installs only). */
    static final String FAILURE_ANDROID_ONLY = "PLAY_ANDROID_ONLY";

    /** The record's failure code when the artifact is not an AAB (FR-006's channel rule, enforced here). */
    static final String FAILURE_AAB_REQUIRED = "PLAY_AAB_REQUIRED";

    /** The record's failure code when the artifact carries no package facts to address the app by. */
    static final String FAILURE_PACKAGE_FACTS_MISSING = "PLAY_PACKAGE_FACTS_MISSING";

    /** The record's failure code when the ring-to-track mapping resolved no testing track. */
    static final String FAILURE_TRACK_UNMAPPED = "PLAY_TRACK_UNMAPPED";

    /** The record's failure code when the mapping resolved the production track (S13-05's surface, never this story's). */
    static final String FAILURE_PRODUCTION_UNSUPPORTED = "PLAY_PRODUCTION_UNSUPPORTED";

    /** The record's failure code when the release targets groups but none are linked to the release's track. */
    static final String FAILURE_GROUPS_UNMAPPED = "PLAY_GROUPS_UNMAPPED";

    /** The record's failure code when the track release failed on Google's side. */
    static final String FAILURE_RELEASE_FAILED = "PLAY_RELEASE_FAILED";

    /** The environment channel-config key that names the track explicitly (any testing track, custom closed names included). */
    static final String CONFIG_TRACK = "play-track";

    /** Play's internal track id (no review — the fast feedback loop of the Engineering/QA rings). */
    static final String TRACK_INTERNAL = "internal";

    /** Play's canonical closed track id (the default closed mapping of the pilot/UAT rings). */
    static final String TRACK_CLOSED_ALPHA = "alpha";

    /** Play's production track id — S13-05's surface; this adapter refuses it. */
    static final String TRACK_PRODUCTION = "production";

    /** The release status the adapter writes: released to the full tester list; Google's review words surface through the refresh. */
    static final String RELEASE_STATUS_COMPLETED = "completed";

    private final GooglePlayDeveloperApiClient play;

    public PlayChannelAdapter(GooglePlayDeveloperApiClient play) {
        this.play = play;
    }

    @Override
    public String channelId() {
        return "play";
    }

    @Override
    public String displayName() {
        return "Google Play (testing tracks)";
    }

    @Override
    public String description() {
        return "Uploads the release's AAB to the Google Play track its ring maps to (internal and closed testing)"
                + " through the official Play Developer API (service-account RS256 OAuth2), carries the frozen"
                + " release notes, and syncs the track's tester email lists. Production promotion and staged"
                + " rollout arrive with the next story; the internal track skips review for the fastest"
                + " Engineering/QA loop.";
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return Set.of(
                AdapterCapability.BUILD_UPLOAD, AdapterCapability.BUILD_STATUS, AdapterCapability.TESTER_GROUP_SYNC);
    }

    @Override
    public Set<String> credentialProviders() {
        return Set.of("google-play");
    }

    @Override
    public List<AdapterOutcome> distribute(DistributionCommand command) {
        GooglePlayCredentials credential = GooglePlayCredentials.of(command.credential());
        if (!credential.complete()) {
            // A material that is not the complete service-account JSON is
            // a configuration fault, not a provider conversation: never
            // retried, never dialed.
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the google-play credential is incomplete (the service-account JSON document as material:"
                            + " client_email, private_key, token_uri)");
        }
        // The ring-to-track mapping and the group-link verdict are
        // release-level facts: resolve both before any slot dials.
        String track = trackOf(command);
        boolean groupsMapped = command.targetGroups() == null
                || command.targetGroups().isEmpty()
                || command.targetGroups().stream().anyMatch(group -> track.equals(group.externalGroupId()));
        List<AdapterOutcome> outcomes = new ArrayList<>();
        for (DistributionCommand.ArtifactSlot slot : command.artifacts()) {
            outcomes.add(distributeSlot(command, credential, track, groupsMapped, slot));
        }
        return outcomes;
    }

    private AdapterOutcome distributeSlot(
            DistributionCommand command,
            GooglePlayCredentials credential,
            String track,
            boolean groupsMapped,
            DistributionCommand.ArtifactSlot slot) {
        if (!"ANDROID".equals(slot.platform())) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_ANDROID_ONLY,
                    "Google Play serves Android builds only");
        }
        if (!"AAB".equals(slot.format())) {
            // FR-006's channel rule at the adapter boundary (AC3): the
            // Play channel is where AABs go; an APK belongs to the Direct
            // channel (or bundletool's universal APK pipeline).
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_AAB_REQUIRED,
                    "the Google Play channel serves AAB artifacts only (format "
                            + (slot.format() == null ? "unset" : slot.format())
                            + "); upload the APK through the direct channel or produce the AAB from the same build");
        }
        if (!groupsMapped) {
            // The TestFlight discipline, held here: a release that targets
            // tester groups but whose release track no group is linked to
            // ships to a track with no testers the platform knows — the
            // mapping surface's gap, recorded loudly, never a silent skip.
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_GROUPS_UNMAPPED,
                    "none of the release's target tester groups are linked to the Play track '" + track
                            + "' (link them on the distribution group-mapping surface)");
        }
        if (slot.bundleId() == null || slot.versionName() == null) {
            return new AdapterOutcome(
                    slot.artifactId(),
                    AdapterOutcome.FAILED,
                    null,
                    FAILURE_PACKAGE_FACTS_MISSING,
                    "the artifact carries no extracted package name or version name to address its Play app and"
                            + " track release by");
        }
        if (slot.contentOf() == null) {
            // The framework's honest precondition: an upload channel's
            // slot must carry its bytes. PERMANENT — a retry cannot
            // conjure a stream the dispatch never resolved.
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the artifact's stored bytes did not resolve for the upload");
        }
        String packageName = slot.bundleId();
        GooglePlayDeveloperApiClient.AppEdit edit = play.insertEdit(credential, packageName);
        GooglePlayDeveloperApiClient.UploadedBundle bundle;
        try (InputStream content = slot.contentOf().get()) {
            if (content == null) {
                throw new AdapterException(
                        AdapterException.Category.PERMANENT,
                        channelId(),
                        "the artifact's stored bytes did not resolve for the upload");
            }
            bundle = play.uploadBundle(credential, packageName, edit.id(), content, slot.sizeBytes());
        } catch (java.io.IOException e) {
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    channelId(),
                    "the artifact's byte stream failed mid-upload: "
                            + e.getClass().getSimpleName());
        } catch (com.shipperdog.api.artifact.ArtifactException e) {
            // The object-storage tier's own fault while OPENING the
            // stored bytes: TRANSIENT — a store blip is exactly what
            // the sandbox's bounded retries exist for, never a final
            // record failure.
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    channelId(),
                    "the artifact's stored bytes could not be opened for the upload: " + e.getMessage());
        }
        // The track's release: the uploaded bundle's version code, the
        // FROZEN release notes (the manifest block the dispatch
        // resolved — never live authoring rows), released to the full
        // tester list (rollout fractions are S13-05's surface).
        GooglePlayDeveloperApiClient.TrackRelease release = new GooglePlayDeveloperApiClient.TrackRelease(
                slot.versionName(), List.of(bundle.versionCode()), releaseNotesOf(command), RELEASE_STATUS_COMPLETED);
        GooglePlayDeveloperApiClient.Track updated = play.putTrack(
                credential, packageName, edit.id(), new GooglePlayDeveloperApiClient.Track(track, List.of(release)));
        play.commitEdit(credential, packageName, edit.id());
        // The first observation of the journey the refresh walks: the
        // LIVE track after the commit (the write's own echo would say
        // what we asked; the committed side says what Google made of
        // it — the internal track serving immediately, the closed
        // tracks holding the release in review).
        String providerStatus;
        try {
            GooglePlayDeveloperApiClient.Track live = play.liveTrackOf(credential, packageName, track);
            providerStatus = providerStatusOf(live, bundle.versionCode());
        } catch (AdapterException e) {
            // The state observation is guidance, not the upload's
            // verdict: a fault here leaves the outcome without a
            // provider word rather than failing the dispatch.
            providerStatus = providerStatusOf(updated, bundle.versionCode());
        }
        return new AdapterOutcome(
                slot.artifactId(),
                AdapterOutcome.DISTRIBUTING,
                externalIdOf(track, bundle.versionCode(), packageName),
                null,
                null,
                providerStatus);
    }

    /**
     * The provider-side status of one distributed artifact (AC5's
     * refresh half): Google's own track-release status word drives the
     * record — {@code completed} is DISTRIBUTED (the track serves the
     * release), the in-flight words ({@code inProgress}, {@code
     * draft}, {@code halted}) are DISTRIBUTING (Google owns the next
     * step), and a failed release fails with the honest code. The
     * external id carries every addressing fact ({@code
     * track#versionCode#packageName}), so the refresh needs no
     * platform-table read.
     */
    @Override
    public AdapterOutcome status(
            UUID artifactId, String channel, String externalId, DistributionCommand.ResolvedCredential resolved) {
        GooglePlayCredentials credential = resolved == null ? null : GooglePlayCredentials.of(resolved);
        if (credential == null || !credential.complete()) {
            // The refresh's honest answer when the chain resolves no
            // credential: a configuration fault, never a dial (and
            // never an NPE masquerading as a provider failure).
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    channelId(),
                    "the google-play credential is unresolvable or incomplete (the service-account JSON document"
                            + " as material)");
        }
        String[] reference = referenceOf(externalId);
        String track = reference[0];
        long versionCode = Long.parseLong(reference[1]);
        String packageName = reference[2];
        GooglePlayDeveloperApiClient.Track live = play.liveTrackOf(credential, packageName, track);
        String providerStatus = providerStatusOf(live, versionCode);
        if (providerStatus == null) {
            return new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTING, externalId, null, null, null);
        }
        return switch (providerStatus) {
            case "completed" ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTED, externalId, null, null, providerStatus);
            case "failed" ->
                new AdapterOutcome(
                        artifactId,
                        AdapterOutcome.FAILED,
                        externalId,
                        FAILURE_RELEASE_FAILED,
                        "the track release failed on Google's side",
                        providerStatus);
            default ->
                new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTING, externalId, null, null, providerStatus);
        };
    }

    /**
     * The ring-to-track mapping (AC2): the environment's {@code
     * play-track} configuration key names the track explicitly (any
     * testing track, custom closed names included); without it the
     * ring code maps through the documented default (Engineering/QA
     * to the review-free internal track, Tenant Pilot/UAT to the
     * closed alpha track). The production track is refused — S13-05's
     * surface — and an unmapped ring with no override is the mapping
     * gap, answered PERMANENT with the actionable reason.
     */
    private static String trackOf(DistributionCommand command) {
        String configured =
                command.channelConfig() == null ? null : command.channelConfig().get(CONFIG_TRACK);
        if (configured != null && !configured.isBlank()) {
            return validateTrack(configured.trim());
        }
        String ring = command.ringCode() == null ? "" : command.ringCode();
        return switch (ring) {
            case "engineering", "qa" -> TRACK_INTERNAL;
            case "tenant-pilot", "uat" -> TRACK_CLOSED_ALPHA;
            case "production" -> throw productionRefused("the production ring maps to the production track");
            default ->
                throw new AdapterException(
                        AdapterException.Category.PERMANENT,
                        "play",
                        "the release's ring '" + command.ringCode()
                                + "' maps to no Play track (configure the play-track channel key on the environment,"
                                + " or dispatch through a ring the default mapping serves)");
        };
    }

    private static String validateTrack(String track) {
        if (TRACK_PRODUCTION.equals(track)) {
            throw productionRefused("the play-track configuration key names the production track");
        }
        if (track.isBlank() || track.length() > 64) {
            throw new AdapterException(
                    AdapterException.Category.PERMANENT,
                    "play",
                    "the play-track configuration key must name a testing track (internal, a closed track name)");
        }
        return track;
    }

    private static AdapterException productionRefused(String reason) {
        return new AdapterException(
                AdapterException.Category.PERMANENT,
                "play",
                reason + ", which this channel refuses (staged rollout and production promotion arrive with"
                        + " S13-05); bind the play-track configuration key to a testing track or dispatch through"
                        + " a testing ring");
    }

    /** The frozen manifest block's release notes as the track release carries them (PRD 16: what was sent). */
    private static List<GooglePlayDeveloperApiClient.ReleaseNote> releaseNotesOf(DistributionCommand command) {
        if (command.submission() == null) {
            return List.of();
        }
        List<GooglePlayDeveloperApiClient.ReleaseNote> notes = new ArrayList<>();
        for (DistributionCommand.SubmissionLocale locale : command.submission().locales()) {
            if (locale.releaseNotes() != null && !locale.releaseNotes().isBlank()) {
                notes.add(new GooglePlayDeveloperApiClient.ReleaseNote(locale.locale(), locale.releaseNotes()));
            }
        }
        return notes;
    }

    /**
     * The external id's shape: {@code track#versionCode#packageName} —
     * the refresh's three addressing facts in one provider reference
     * (the separators cannot occur in a track id, a decimal code, or a
     * package name).
     */
    static String externalIdOf(String track, long versionCode, String packageName) {
        return track + "#" + versionCode + "#" + packageName;
    }

    /** The external id split into its three facts; PERMANENT on a malformed reference. */
    private static String[] referenceOf(String externalId) {
        if (externalId == null) {
            throw malformedReference(externalId);
        }
        String[] parts = externalId.split("#");
        if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
            throw malformedReference(externalId);
        }
        try {
            Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw malformedReference(externalId);
        }
        return parts;
    }

    private static AdapterException malformedReference(String externalId) {
        return new AdapterException(
                AdapterException.Category.PERMANENT,
                "play",
                "the record's external id is not a track release reference (track#versionCode#packageName): "
                        + externalId);
    }

    private static String providerStatusOf(GooglePlayDeveloperApiClient.Track track, long versionCode) {
        if (track == null) {
            return null;
        }
        for (GooglePlayDeveloperApiClient.TrackRelease release : track.releases()) {
            if (release.versionCodes().contains(versionCode)) {
                return release.status();
            }
        }
        return null;
    }
}
