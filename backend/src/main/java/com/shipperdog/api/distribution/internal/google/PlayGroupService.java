package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TesterGroupAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Play group mapping, tester-list sync, and track-model surface
 * (issue #64, S13-04, AC2/AC4): the ShipperDog tester group to Play
 * track link — one row per (channel, group) carrying the TRACK NAME
 * as the provider-side id — and the sync that makes the track's
 * tester email list exactly what ShipperDog says. The sync doctrine
 * is the PLAY split of #62's never-remove: a track's email list IS
 * managed here (the story's own contract — "add/remove reflected in
 * Play Console"), so the write REPLACES the list with the union of
 * every group linked to that track, and the adds AND removes the
 * write carried are reported in the answer and the audit. Manual
 * Play Console additions do not survive a sync of a track the
 * platform owns — the report is the operator's evidence of what
 * changed.
 *
 * <p>The service is the framework side of the connector (the adapter
 * stays pure). It speaks Play through {@link GooglePlayDeveloperApiClient}
 * with the credential the delegation chain resolved for the addressed
 * scope — the same org-to-app-to-env chain a dispatch rides.
 */
@Service
public class PlayGroupService {

    /** The channel this surface maps (the adapter's id). */
    static final String CHANNEL = "play";

    private static final String PROVIDER = "google-play";

    private final ChannelGroupLinkRepository links;
    private final DistributionCredentialService credentials;
    private final ChannelSecretStore secrets;
    private final ApplicationAccess applications;
    private final TenantAccess tenants;
    private final TesterGroupAccess testerGroups;
    private final GooglePlayDeveloperApiClient play;
    private final AuditLogger audit;
    private final Clock clock;

    public PlayGroupService(
            ChannelGroupLinkRepository links,
            DistributionCredentialService credentials,
            ChannelSecretStore secrets,
            ApplicationAccess applications,
            TenantAccess tenants,
            TesterGroupAccess testerGroups,
            GooglePlayDeveloperApiClient play,
            AuditLogger audit,
            Clock clock) {
        this.links = links;
        this.credentials = credentials;
        this.secrets = secrets;
        this.applications = applications;
        this.tenants = tenants;
        this.testerGroups = testerGroups;
        this.play = play;
        this.audit = audit;
        this.clock = clock;
    }

    /** The organization's Play links, group-resolved for the console view. */
    @Transactional(readOnly = true)
    public List<LinkView> list(UUID organizationId) {
        List<LinkView> views = new ArrayList<>();
        for (ChannelGroupLinkEntity link :
                links.findByOrganizationIdAndChannelOrderByGroupIdAsc(organizationId, CHANNEL)) {
            views.add(viewOf(link));
        }
        return views;
    }

    /**
     * Links (or re-links) one tester group to a Play testing track
     * (AC2): the link write validates the track exists on the app
     * (the track model read) and refuses the production track —
     * S13-05's surface, the adapter's own boundary held here too.
     * Idempotent: a group already linked answers its standing link
     * rotated to the named track.
     */
    @Transactional
    public LinkView link(UUID organizationId, UUID applicationId, UUID groupId, String track, UUID actorId) {
        var ownership = applications.ownershipOf(applicationId).orElseThrow(() -> invalid("unknown application"));
        if (!organizationId.equals(ownership.organizationId())) {
            throw invalid("unknown application");
        }
        TesterGroupAccess.GroupFacts group =
                testerGroups.groupFactsOf(groupId).orElseThrow(() -> invalid("unknown tester group"));
        if (!tenants.owningOrganizationId(group.tenantId())
                .map(organizationId::equals)
                .orElse(false)) {
            throw invalid("unknown tester group");
        }
        String packageName = packageNameOf(applicationId);
        String normalizedTrack = normalizedTrack(track);
        GooglePlayCredentials credential = credentialOf(organizationId, applicationId, null);
        // The track-model read (the app's real tracks, one edit): the
        // link write refuses a track the app does not carry.
        GooglePlayDeveloperApiClient.AppEdit edit = play.insertEdit(credential, packageName);
        boolean known = play.tracksOfEdit(credential, packageName, edit.id()).stream()
                .anyMatch(existing -> normalizedTrack.equals(existing.track()));
        if (!known) {
            throw invalid("the app carries no Play track named '" + normalizedTrack
                    + "' (the testing tracks are internal and the app's closed tracks)");
        }
        Optional<ChannelGroupLinkEntity> standing = links.findByChannelAndGroupId(CHANNEL, groupId);
        ChannelGroupLinkEntity saved = standing.map(existing -> {
                    if (!existing.getOrganizationId().equals(organizationId)) {
                        // Defense-in-depth for an invariant validation
                        // already holds: never touch a foreign row.
                        throw invalid("unknown tester group");
                    }
                    existing.rotate(normalizedTrack, normalizedTrack);
                    return links.save(existing);
                })
                .orElseGet(() -> links.save(new ChannelGroupLinkEntity(
                        organizationId,
                        group.tenantId(),
                        applicationId,
                        groupId,
                        CHANNEL,
                        normalizedTrack,
                        normalizedTrack)));
        audit.record(AuditEvent.builder(com.shipperdog.api.audit.AuditEventType.DISTRIBUTION_GROUP_SYNCED)
                .userId(actorId)
                .orgId(organizationId)
                .applicationId(applicationId)
                .detail(Map.of(
                        "channel",
                        CHANNEL,
                        "linkId",
                        saved.getId().toString(),
                        "action",
                        "linked",
                        "track",
                        normalizedTrack))
                .success(true)
                .build());
        return viewOf(saved);
    }

    /** Removes one link (the ShipperDog side only — a Play track is never deleted). */
    @Transactional
    public void unlink(UUID linkId, UUID organizationId, UUID actorId) {
        ChannelGroupLinkEntity link = links.findByIdAndOrganizationId(linkId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_GROUP_LINK_NOT_FOUND, "unknown group link"));
        links.delete(link);
        audit.record(AuditEvent.builder(com.shipperdog.api.audit.AuditEventType.DISTRIBUTION_GROUP_SYNCED)
                .userId(actorId)
                .orgId(organizationId)
                .detail(Map.of(
                        "channel",
                        CHANNEL,
                        "linkId",
                        linkId.toString(),
                        "action",
                        "unlinked",
                        "track",
                        link.getExternalGroupId()))
                .success(true)
                .build());
    }

    /**
     * The tester-list sync (AC4): the track's email list becomes
     * exactly the union of the members of every group linked to that
     * track — adds and removes BOTH reflect (the Play contract), and
     * the answer plus the audit report every change the write carried.
     */
    @Transactional
    public SyncView sync(UUID linkId, UUID organizationId, UUID actorId) {
        ChannelGroupLinkEntity link = links.findByIdAndOrganizationId(linkId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_GROUP_LINK_NOT_FOUND, "unknown group link"));
        String packageName = packageNameOf(link.getApplicationId());
        String track = link.getExternalGroupId();
        // The desired list: every member of every group of THIS
        // application linked to the same track (the platform is the
        // list's owner once a track carries a link).
        Set<String> desired = new LinkedHashSet<>();
        for (ChannelGroupLinkEntity sameTrack :
                links.findByOrganizationIdAndChannelOrderByGroupIdAsc(organizationId, CHANNEL)) {
            if (sameTrack.getApplicationId().equals(link.getApplicationId())
                    && track.equals(sameTrack.getExternalGroupId())) {
                for (TesterGroupAccess.GroupMember member : testerGroups.membersOf(sameTrack.getGroupId())) {
                    if (member.email() != null && !member.email().isBlank()) {
                        desired.add(member.email());
                    }
                }
            }
        }
        GooglePlayCredentials credential = credentialOf(organizationId, link.getApplicationId(), null);
        GooglePlayDeveloperApiClient.AppEdit edit = play.insertEdit(credential, packageName);
        List<String> current = play.testerEmailsOfTrack(credential, packageName, edit.id(), track);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Set<String> currentSet = new LinkedHashSet<>(current);
        for (String email : new TreeSet<>(desired)) {
            if (!currentSet.contains(email)) {
                added.add(email);
            }
        }
        for (String email : current) {
            if (!desired.contains(email)) {
                removed.add(email);
            }
        }
        play.putTesterEmailsOfTrack(credential, packageName, edit.id(), track, List.copyOf(new TreeSet<>(desired)));
        play.commitEdit(credential, packageName, edit.id());
        link.markSynced(Instant.now(clock));
        links.save(link);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("linkId", link.getId().toString());
        detail.put("action", "synced");
        detail.put("track", track);
        detail.put("pushed", String.join(",", added));
        detail.put("removed", String.join(",", removed));
        audit.record(AuditEvent.builder(com.shipperdog.api.audit.AuditEventType.DISTRIBUTION_GROUP_SYNCED)
                .userId(actorId)
                .orgId(organizationId)
                .applicationId(link.getApplicationId())
                .detail(detail)
                .success(true)
                .build());
        return new SyncView(viewOf(link), added, removed);
    }

    /**
     * The app's Play track model (the listing console's read, AC2/AC5):
     * every track the app carries with its releases — Google's own
     * status words verbatim for the release dashboard.
     */
    @Transactional(readOnly = true)
    public List<TrackView> tracks(UUID organizationId, UUID applicationId) {
        var ownership = applications.ownershipOf(applicationId).orElseThrow(() -> invalid("unknown application"));
        if (!organizationId.equals(ownership.organizationId())) {
            throw invalid("unknown application");
        }
        String packageName = packageNameOf(applicationId);
        GooglePlayCredentials credential = credentialOf(organizationId, applicationId, null);
        GooglePlayDeveloperApiClient.AppEdit edit = play.insertEdit(credential, packageName);
        List<TrackView> views = new ArrayList<>();
        for (GooglePlayDeveloperApiClient.Track track : play.tracksOfEdit(credential, packageName, edit.id())) {
            List<TrackReleaseView> releases = new ArrayList<>();
            for (GooglePlayDeveloperApiClient.TrackRelease release : track.releases()) {
                releases.add(new TrackReleaseView(
                        release.name(),
                        release.versionCodes(),
                        release.releaseNotes().stream()
                                .map(note -> new ReleaseNoteView(note.language(), note.text()))
                                .toList(),
                        release.status()));
            }
            views.add(new TrackView(track.track(), releases));
        }
        return views;
    }

    /** The credential for a sync or listing: the chain resolved at the addressed scope (org default when no app named). */
    private GooglePlayCredentials credentialOf(UUID organizationId, UUID applicationId, UUID environmentId) {
        var account = credentials
                .resolve(organizationId, applicationId, environmentId, PROVIDER)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                        "no google-play credential is bound on the delegation chain for this scope"
                                + " (bind provider google-play at the organization, application, or environment"
                                + " level)"));
        SecretMaterial material = secrets.resolve(account.getSecretReference()).orElseGet(() -> new SecretMaterial(""));
        GooglePlayCredentials credential = GooglePlayCredentials.of(new DistributionCommand.ResolvedCredential(
                account.getProvider(),
                account.getName(),
                account.getSecretReference(),
                material,
                account.getMetadata()));
        if (!credential.complete()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                    "the google-play credential is incomplete (the service-account JSON document as material)");
        }
        return credential;
    }

    /** The application's Android package name (the Play app's identity). */
    private String packageNameOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .map(ApplicationAccess.ApplicationProfile::packageName)
                .filter(name -> name != null && !name.isBlank())
                .orElseThrow(() -> invalid("the application carries no Android package name"));
    }

    private static String normalizedTrack(String track) {
        String normalized = track == null ? "" : track.trim();
        if (normalized.isBlank() || normalized.length() > 64) {
            throw invalid("the track must name a Play testing track");
        }
        if (PlayChannelAdapter.TRACK_PRODUCTION.equals(normalized)) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_GROUP_LINK_INVALID,
                    "the production track is not a tester-list surface (production promotion arrives with S13-05)");
        }
        return normalized;
    }

    private static DistributionException invalid(String message) {
        return new DistributionException(DistributionException.Code.DISTRIBUTION_GROUP_LINK_INVALID, message);
    }

    private LinkView viewOf(ChannelGroupLinkEntity link) {
        String groupName = testerGroups
                .groupFactsOf(link.getGroupId())
                .map(facts -> facts.name())
                .orElse("");
        return new LinkView(
                link.getId(),
                link.getOrganizationId(),
                link.getTenantId(),
                link.getApplicationId(),
                link.getGroupId(),
                groupName,
                link.getChannel(),
                link.getExternalGroupId(),
                link.getExternalGroupName(),
                link.getLastSyncedAt(),
                link.getCreatedAt(),
                link.getUpdatedAt());
    }

    /** One link's served view. */
    public record LinkView(
            UUID id,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID groupId,
            String groupName,
            String channel,
            String externalGroupId,
            String externalGroupName,
            Instant lastSyncedAt,
            Instant createdAt,
            Instant updatedAt) {}

    /** The sync's verdict: the link after the write, the emails added, and the emails removed. */
    public record SyncView(LinkView link, List<String> addedEmails, List<String> removedEmails) {}

    /** One Play track as the listing console renders it. */
    public record TrackView(String track, List<TrackReleaseView> releases) {}

    /** One track release: the release's name, version codes, per-language notes, and Google's own status word. */
    public record TrackReleaseView(
            String name, List<Long> versionCodes, List<ReleaseNoteView> releaseNotes, String status) {}

    /** One language's release note as the listing renders it. */
    public record ReleaseNoteView(String language, String text) {}
}
