package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.release.ReleaseAccess;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The TestFlight group mapping and sync surface (issue #62, S13-02,
 * AC2/AC5): the ShipperDog tester group to ASC beta group link —
 * created against an existing same-named group or on demand (the
 * create-on-demand option), synced idempotently (membership adds are
 * additive; a second run adds nothing), and reconciled for drift:
 * provider-side members ShipperDog does not know and builds manually
 * unassigned in ASC are REPORTED, never force-removed (the safe
 * re-sync doctrine — an operator's manual ASC work is evidence, not
 * garbage).
 *
 * <p>The service is the framework side of the connector (the adapter
 * stays pure: dispatch assigns builds to the links the framework
 * resolved). It speaks Apple through {@link AppStoreConnectClient}
 * with the credential the delegation chain resolved for the addressed
 * scope — the same org to app to env chain a dispatch rides.
 */
@Service
public class TestFlightGroupService {

    /** The channel this surface maps (the adapter's id). */
    static final String CHANNEL = "testflight";

    private static final String PROVIDER = "apple";

    private final ChannelGroupLinkRepository links;
    private final ChannelDistributionRepository records;
    private final DistributionCredentialService credentials;
    private final ChannelSecretStore secrets;
    private final ApplicationAccess applications;
    private final TenantAccess tenants;
    private final TesterGroupAccess testerGroups;
    private final ReleaseAccess releases;
    private final AppStoreConnectClient asc;
    private final com.shipperdog.api.tenant.TenantScopeExecutor scopes;
    private final AuditLogger audit;
    private final Clock clock;

    public TestFlightGroupService(
            ChannelGroupLinkRepository links,
            ChannelDistributionRepository records,
            DistributionCredentialService credentials,
            ChannelSecretStore secrets,
            ApplicationAccess applications,
            TenantAccess tenants,
            TesterGroupAccess testerGroups,
            ReleaseAccess releases,
            AppStoreConnectClient asc,
            com.shipperdog.api.tenant.TenantScopeExecutor scopes,
            AuditLogger audit,
            Clock clock) {
        this.links = links;
        this.records = records;
        this.credentials = credentials;
        this.secrets = secrets;
        this.applications = applications;
        this.tenants = tenants;
        this.testerGroups = testerGroups;
        this.releases = releases;
        this.asc = asc;
        this.scopes = scopes;
        this.audit = audit;
        this.clock = clock;
    }

    /** The organization's TestFlight links, group-resolved for the console view. */
    @Transactional(readOnly = true)
    public List<LinkView> list(UUID organizationId) {
        List<LinkView> views = new ArrayList<>();
        for (ChannelGroupLinkEntity link :
                links.findByOrganizationIdAndChannelOrderByGroupIdAsc(organizationId, CHANNEL)) {
            String groupName = testerGroups
                    .groupFactsOf(link.getGroupId())
                    .map(facts -> facts.name())
                    .orElse("");
            views.add(new LinkView(
                    link.getId(),
                    link.getOrganizationId(),
                    link.getTenantId(),
                    link.getGroupId(),
                    groupName,
                    link.getChannel(),
                    link.getExternalGroupId(),
                    link.getExternalGroupName(),
                    link.getLastSyncedAt(),
                    link.getCreatedAt(),
                    link.getUpdatedAt()));
        }
        return views;
    }

    /**
     * Links (or re-links) one tester group to an ASC beta group (AC2):
     * an existing external group of the same name wins; otherwise the
     * create-on-demand option mints one. Idempotent — a group already
     * linked answers its standing link.
     */
    @Transactional
    public LinkView link(UUID organizationId, UUID applicationId, UUID groupId, boolean createOnDemand, UUID actorId) {
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
        Optional<ChannelGroupLinkEntity> standing = links.findByChannelAndGroupId(CHANNEL, groupId);
        if (standing.isPresent() && !standing.get().getOrganizationId().equals(organizationId)) {
            // Defense-in-depth for an invariant that validation already
            // holds (a group of THIS organization's tenant can only be
            // linked by this organization): never touch a foreign row.
            throw invalid("unknown tester group");
        }
        if (standing.isPresent()) {
            return sync(standing.get().getId(), organizationId, actorId).link();
        }
        String bundleId = applications
                .profileOf(applicationId)
                .map(ApplicationAccess.ApplicationProfile::bundleId)
                .filter(id -> id != null && !id.isBlank())
                .orElseThrow(() -> invalid("the application carries no iOS bundle id"));
        AppleCredentials credential = credentialOf(organizationId, applicationId, null);
        String appId = asc.appIdOfBundleId(credential, bundleId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_GROUP_LINK_INVALID,
                        "no App Store Connect app matches the application's bundle id"));
        AppStoreConnectClient.BetaGroup betaGroup = asc.betaGroupsOf(credential, appId).stream()
                .filter(existing -> group.name().equals(existing.name()) && !existing.internalGroup())
                .findFirst()
                .orElse(null);
        if (betaGroup == null && createOnDemand) {
            betaGroup = asc.createBetaGroup(credential, appId, group.name());
        }
        if (betaGroup == null) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_GROUP_LINK_INVALID,
                    "no TestFlight group named '" + group.name()
                            + "' exists for the app; pass createOnDemand to mint one");
        }
        AppStoreConnectClient.BetaGroup linked = betaGroup;
        ChannelGroupLinkEntity saved = standing.map(existing -> {
                    existing.rotate(linked.id(), linked.name());
                    return links.save(existing);
                })
                .orElseGet(() -> links.save(new ChannelGroupLinkEntity(
                        organizationId,
                        group.tenantId(),
                        applicationId,
                        groupId,
                        CHANNEL,
                        linked.id(),
                        linked.name())));
        // The link lands with its first membership push: the same idempotent
        // sync the re-sync route runs.
        return sync(saved.getId(), organizationId, actorId).link();
    }

    /** Removes one link (the ShipperDog side only — an ASC group is never deleted). */
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
                        "externalGroupId",
                        link.getExternalGroupId()))
                .success(true)
                .build());
    }

    /**
     * The idempotent re-sync (AC5): pushes the group's ShipperDog
     * membership to the mapped beta group (adds only) and reports the
     * provider-side drift — ASC members ShipperDog does not know and
     * distributed builds manually unassigned from the group. Safe by
     * construction: nothing provider-side is ever removed.
     */
    @Transactional
    public SyncView sync(UUID linkId, UUID organizationId, UUID actorId) {
        ChannelGroupLinkEntity link = links.findByIdAndOrganizationId(linkId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_GROUP_LINK_NOT_FOUND, "unknown group link"));
        // The chain resolved at the link's own application scope: an
        // app-level apple binding serves the sync exactly as it serves
        // the dispatch (PRD 25's matrix -- the reviewer's finding).
        AppleCredentials credential = credentialOf(organizationId, link.getApplicationId(), null);
        List<String> providerEmails = new ArrayList<>(asc.testerEmailsOfGroup(credential, link.getExternalGroupId()));
        Set<String> providerSide = new LinkedHashSet<>(providerEmails);
        List<TesterGroupAccess.GroupMember> members = testerGroups.membersOf(link.getGroupId());
        Set<String> shipperDogSide = new LinkedHashSet<>(members.stream()
                .map(TesterGroupAccess.GroupMember::email)
                .filter(email -> email != null && !email.isBlank())
                .toList());
        List<String> toAdd = shipperDogSide.stream()
                .filter(email -> !providerSide.contains(email))
                .sorted()
                .toList();
        List<String> driftTesters = providerSide.stream()
                .filter(email -> !shipperDogSide.contains(email))
                .sorted()
                .toList();
        if (!toAdd.isEmpty()) {
            asc.addTestersToGroup(credential, link.getExternalGroupId(), toAdd);
        }
        // Build drift: the org's distributed testflight builds whose
        // releases target this group but ASC no longer assigns to it
        // (a manual console removal -- reported, never re-forced). The
        // target-group reads ride the sanctioned organization scope:
        // this is an organization-grain console operation, and the
        // executor's establish-only rule is exactly the guard.
        List<String> assigned = asc.buildIdsOfGroup(credential, link.getExternalGroupId());
        Set<String> assignedSide = new LinkedHashSet<>(assigned);
        List<String> driftBuilds = new ArrayList<>();
        for (ChannelDistributionEntity record :
                records.findByOrganizationIdAndChannelOrderByCreatedAtAsc(organizationId, CHANNEL)) {
            boolean live = record.getStatus() == ChannelDistributionEntity.Status.DISTRIBUTED
                    || record.getStatus() == ChannelDistributionEntity.Status.DISTRIBUTING;
            if (!live || record.getExternalId() == null) {
                continue;
            }
            // The target-group read rides the release's OWN owning
            // scope (the manifest-service pattern: the release module's
            // scoped seam sees tenant-owned rows only under their
            // tenant's scope); the facts read that decides the scope is
            // the non-seam-served one this module already owns.
            ReleaseAccess.ReleaseFacts facts =
                    releases.distributionFactsOf(record.getReleaseId()).orElse(null);
            if (facts == null) {
                continue;
            }
            java.util.function.Supplier<Boolean> targetsGroup =
                    () -> releases.targetGroupsOf(record.getReleaseId()).stream()
                            .anyMatch(target -> target.groupId().equals(link.getGroupId()));
            boolean groupTargeted = facts.tenantId() != null
                    ? scopes.inScope(facts.tenantId(), actorId, targetsGroup)
                    : scopes.inOrganizationScope(facts.organizationId(), actorId, targetsGroup);
            if (groupTargeted && !assignedSide.contains(record.getExternalId())) {
                driftBuilds.add(record.getExternalId());
            }
        }
        link.markSynced(Instant.now(clock));
        links.save(link);

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("linkId", link.getId().toString());
        detail.put("action", "synced");
        detail.put("pushed", String.join(",", toAdd));
        detail.put("driftTesters", String.join(",", driftTesters));
        detail.put("driftBuilds", String.join(",", driftBuilds));
        audit.record(AuditEvent.builder(com.shipperdog.api.audit.AuditEventType.DISTRIBUTION_GROUP_SYNCED)
                .userId(actorId)
                .orgId(organizationId)
                .detail(detail)
                .success(true)
                .build());
        return new SyncView(viewOf(link), toAdd, driftTesters, driftBuilds);
    }

    /** The credential for a sync: the chain resolved at the addressed scope (org default when no app named). */
    private AppleCredentials credentialOf(UUID organizationId, UUID applicationId, UUID environmentId) {
        var account = credentials
                .resolve(organizationId, applicationId, environmentId, PROVIDER)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_GROUP_LINK_INVALID,
                        "no apple credential is bound on the delegation chain for this scope"
                                + " (bind provider apple at the organization, application, or environment level)"));
        SecretMaterial material = secrets.resolve(account.getSecretReference()).orElseGet(() -> new SecretMaterial(""));
        AppleCredentials credential = AppleCredentials.of(new DistributionCommand.ResolvedCredential(
                account.getProvider(),
                account.getName(),
                account.getSecretReference(),
                material,
                account.getMetadata()));
        if (!credential.complete()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                    "the apple credential is incomplete (issuerId and keyId in the binding metadata, the .p8 as material)");
        }
        return credential;
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
            UUID groupId,
            String groupName,
            String channel,
            String externalGroupId,
            String externalGroupName,
            Instant lastSyncedAt,
            Instant createdAt,
            Instant updatedAt) {}

    /** The sync's verdict: the link after the push, what was pushed, and the drift reported (never removed). */
    public record SyncView(
            LinkView link, List<String> pushedEmails, List<String> driftTesterEmails, List<String> driftBuildIds) {}
}
