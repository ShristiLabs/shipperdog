package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.entity.ScimGroupEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupMemberEntity;
import com.shipperdog.api.identity.internal.entity.ScimGroupMemberId;
import com.shipperdog.api.identity.internal.entity.ScimUserEntity;
import com.shipperdog.api.identity.internal.repository.ScimGroupMemberRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupRepository;
import com.shipperdog.api.identity.internal.repository.ScimUserRepository;
import com.shipperdog.api.identity.internal.scim.ScimException;
import com.shipperdog.api.identity.internal.scim.ScimFilter;
import com.shipperdog.api.identity.internal.scim.ScimGroupResource;
import com.shipperdog.api.identity.internal.scim.ScimListResponse;
import com.shipperdog.api.identity.internal.scim.ScimMeta;
import com.shipperdog.api.identity.internal.scim.ScimPatchRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SCIM Groups resource (S14-03, issue #69, RFC 7644 section 3):
 * the pushed groups whose display names key the organization's
 * mapping rules. Every membership change re-derives the affected
 * users' assignments ({@link ScimAssignmentService}) — group sync IS
 * the role/tenant assignment update of acceptance criterion 3.
 *
 * <p>Members reference SCIM User ids (never internal account ids); a
 * member value no provisioning created is a 400 invalidValue refusal,
 * so a connector mis-keyed against account UUIDs fails visibly
 * instead of silently matching nothing.
 */
@Service
public class ScimGroupService {

    /** The core Group schema URN every resource carries. */
    public static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    /** The RFC 7644 remove-with-filter path Okta and Entra send. */
    private static final Pattern MEMBER_FILTER_PATH =
            Pattern.compile("^members\\[\\s*value\\s+eq\\s+\"([^\"]+)\"\\s*]$");

    private final ScimGroupRepository scimGroups;
    private final ScimGroupMemberRepository groupMembers;
    private final ScimUserRepository scimUsers;
    private final ScimAssignmentService assignments;
    private final ScimProvisioningAttempt provisioning;
    private final AuditEventPublisher audit;
    private final Clock clock;

    ScimGroupService(
            ScimGroupRepository scimGroups,
            ScimGroupMemberRepository groupMembers,
            ScimUserRepository scimUsers,
            ScimAssignmentService assignments,
            ScimProvisioningAttempt provisioning,
            AuditEventPublisher audit,
            Clock clock) {
        this.scimGroups = scimGroups;
        this.groupMembers = groupMembers;
        this.scimUsers = scimUsers;
        this.assignments = assignments;
        this.provisioning = provisioning;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * RFC 7644 section 3.3 create: the externalId upsert (a known
     * externalId replaces the group's state and answers 200), the
     * displayName uniqueness (409 for a different group's name), and
     * the initial membership.
     *
     * <p>The fresh leg runs through {@link ScimProvisioningAttempt}
     * (its own transaction, flushed inside) for the same reason the
     * Users create does: concurrent pushes of the same group race
     * past the checks, and the losers of the {@code
     * uq_scim_groups_external_id}/{@code uq_scim_groups_display_name}
     * races re-derive the committed winner — the idempotent 200 or
     * the honest 409, never the dead-catch 500 of an insert under
     * this transaction (the #26/#27 discipline).
     */
    @Transactional
    public Created create(UUID organizationId, ScimGroupResource resource) {
        if (resource == null) {
            throw ScimException.invalidSyntax("a SCIM Group resource body is required");
        }
        String displayName = requireDisplayName(resource.displayName());
        String externalId = optionalBounded(resource.externalId(), 255, "externalId");
        List<UUID> memberIds = resolveMembers(organizationId, resource.members());
        Optional<ScimGroupEntity> existing =
                externalId == null ? Optional.empty() : lookupByExternalId(organizationId, externalId);
        if (existing.isPresent()) {
            ScimGroupResource updated =
                    replaceState(organizationId, existing.get(), externalId, displayName, memberIds, "replaced");
            return new Created(updated, false);
        }
        if (displayNameOwner(organizationId, displayName) != null) {
            // A same-externalId winner may have committed between the
            // two probes (each statement snapshots independently under
            // READ COMMITTED): re-read the externalId before refusing —
            // the raced duplicate is the idempotent replace, not a
            // conflict.
            ScimGroupEntity raced = externalId == null
                    ? null
                    : lookupByExternalId(organizationId, externalId).orElse(null);
            if (raced != null) {
                return new Created(
                        replaceState(organizationId, raced, externalId, displayName, memberIds, "replaced"), false);
            }
            throw displayNameConflict();
        }
        try {
            return provisioning.attempt(() -> provisionFresh(organizationId, externalId, displayName, memberIds));
        } catch (DataIntegrityViolationException e) {
            // Lost an insert race: only the attempt's own transaction
            // rolled back — the concurrent winner's committed row
            // decides between the idempotent replace and the honest
            // displayName refusal.
            ScimGroupEntity winner = externalId == null
                    ? null
                    : lookupByExternalId(organizationId, externalId).orElse(null);
            if (winner != null) {
                return new Created(
                        replaceState(organizationId, winner, externalId, displayName, memberIds, "replaced"), false);
            }
            if (displayNameOwner(organizationId, displayName) != null) {
                // Same commit-in-between window as the pre-check:
                // re-read the externalId before refusing.
                ScimGroupEntity raced = externalId == null
                        ? null
                        : lookupByExternalId(organizationId, externalId).orElse(null);
                if (raced != null) {
                    return new Created(
                            replaceState(organizationId, raced, externalId, displayName, memberIds, "replaced"), false);
                }
                throw displayNameConflict();
            }
            throw e;
        }
    }

    /**
     * The fresh-group leg, run inside the attempt's own transaction:
     * the group row flushes before the membership diff and the
     * re-derivation run, so a lost uniqueness race aborts the whole
     * push at the boundary.
     */
    private Created provisionFresh(UUID organizationId, String externalId, String displayName, List<UUID> memberIds) {
        ScimGroupEntity group = scimGroups.save(new ScimGroupEntity(organizationId, externalId, displayName));
        scimGroups.flush();
        return new Created(replaceState(organizationId, group, externalId, displayName, memberIds, "created"), true);
    }

    /** The displayName-under-another-group probe of the create path. */
    private ScimGroupEntity displayNameOwner(UUID organizationId, String displayName) {
        return scimGroups
                .findByOrganizationIdAndDisplayName(organizationId, displayName)
                .orElse(null);
    }

    private static ScimException displayNameConflict() {
        return ScimException.uniqueness("displayName is already used by another group in this organization");
    }

    /** RFC 7644 section 3.4.1 read (by id): the uniform 404 on miss. */
    @Transactional(readOnly = true)
    public ScimGroupResource get(UUID organizationId, UUID scimGroupId) {
        return render(organizationId, mustFind(organizationId, scimGroupId));
    }

    /**
     * RFC 7644 section 3.4.2 list: the displayName/externalId filter
     * subset, startIndex/count pagination, stable order.
     */
    @Transactional(readOnly = true)
    public ScimListResponse<ScimGroupResource> list(UUID organizationId, ScimFilter filter, int startIndex, int count) {
        int page = Math.max(1, startIndex);
        int size = count <= 0 ? ScimUserService.DEFAULT_COUNT : Math.min(count, ScimUserService.MAX_COUNT);
        List<ScimGroupEntity> pageRows;
        int total;
        if (filter == null) {
            pageRows = scimGroups.findByOrganizationId(organizationId, pageRequest(page, size));
            total = (int) scimGroups.countByOrganizationId(organizationId);
        } else {
            Optional<ScimGroupEntity> match = filter.attribute() == ScimFilter.Attribute.USER_NAME
                    ? scimGroups.findByOrganizationIdAndDisplayName(organizationId, filter.value())
                    : lookupByExternalId(organizationId, filter.value());
            pageRows = page == 1 ? match.stream().toList() : List.of();
            total = match.isPresent() ? 1 : 0;
        }
        List<ScimGroupResource> resources =
                pageRows.stream().map(group -> render(organizationId, group)).toList();
        return new ScimListResponse<>(total, page, size, resources);
    }

    /** RFC 7644 section 3.5.1 replace: the full declarative membership state. */
    @Transactional
    public ScimGroupResource replace(UUID organizationId, UUID scimGroupId, ScimGroupResource resource) {
        ScimGroupEntity group = mustFind(organizationId, scimGroupId);
        if (resource == null) {
            throw ScimException.invalidSyntax("a SCIM Group resource body is required");
        }
        String displayName = requireDisplayName(resource.displayName());
        String externalId = optionalBounded(resource.externalId(), 255, "externalId");
        List<UUID> memberIds = resolveMembers(organizationId, resource.members());
        return replaceState(organizationId, group, externalId, displayName, memberIds, "replaced");
    }

    /**
     * RFC 7644 section 3.5.2 PATCH: the member operations IdPs send —
     * add (path {@code members}, value a member array), remove (the
     * filter path {@code members[value eq "id"]}, or the value-array
     * shape), and displayName/externalId replaces.
     */
    @Transactional
    public ScimGroupResource patch(UUID organizationId, UUID scimGroupId, ScimPatchRequest request) {
        ScimGroupEntity group = mustFind(organizationId, scimGroupId);
        List<ScimPatchRequest.Operation> operations =
                request == null || request.operations() == null ? List.of() : request.operations();
        if (operations.isEmpty()) {
            throw ScimException.invalidSyntax("at least one PATCH operation is required");
        }
        String displayName = group.getDisplayName();
        String externalId = group.getExternalId();
        Set<UUID> members = new LinkedHashSet<>(memberIdsOf(organizationId, group));
        for (ScimPatchRequest.Operation operation : operations) {
            String op = operation.op() == null ? "" : operation.op().trim().toLowerCase(Locale.ROOT);
            String path = operation.path() == null ? "" : operation.path().trim();
            if ("add".equals(op) || "replace".equals(op)) {
                if (path.isEmpty() || "members".equals(path)) {
                    if ("replace".equals(op) && "members".equals(path)) {
                        members.clear();
                    }
                    members.addAll(resolveMembers(organizationId, memberValues(operation.value())));
                } else if ("displayName".equals(path)) {
                    displayName = requireDisplayName(stringOf(operation.value(), "displayName"));
                } else if ("externalId".equals(path)) {
                    externalId = optionalBounded(stringOf(operation.value(), "externalId"), 255, "externalId");
                } else {
                    throw ScimException.invalidPath("unsupported PATCH path '" + path + "'");
                }
            } else if ("remove".equals(op)) {
                Matcher filter = MEMBER_FILTER_PATH.matcher(path);
                if (filter.matches()) {
                    members.remove(resolveMember(organizationId, filter.group(1)));
                } else if (path.isEmpty() || "members".equals(path)) {
                    if (operation.value() == null) {
                        members.clear();
                    } else {
                        resolveMembers(organizationId, memberValues(operation.value()))
                                .forEach(members::remove);
                    }
                } else {
                    throw ScimException.invalidPath("unsupported PATCH path '" + path + "'");
                }
            } else {
                throw ScimException.invalidPath("unsupported PATCH op '" + operation.op() + "'");
            }
        }
        return replaceState(organizationId, group, externalId, displayName, new ArrayList<>(members), "patched");
    }

    /**
     * RFC 7644 section 3.6 DELETE: the group row and its memberships
     * go, and every former member's assignments are re-derived (the
     * mapping rules no longer see the group).
     */
    @Transactional
    public void delete(UUID organizationId, UUID scimGroupId) {
        ScimGroupEntity group = mustFind(organizationId, scimGroupId);
        List<UUID> formerMemberIds = memberIdsOf(organizationId, group);
        groupMembers.deleteByScimGroupId(group.getId());
        scimGroups.delete(group);
        rederiveAll(organizationId, group, formerMemberIds);
        auditSynced(organizationId, group, "deleted", 0, formerMemberIds.size());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Applies the declared state onto the group row: identity fields
     * (with the uniqueness guards), the membership diff, and the
     * re-derivation for every affected user.
     */
    private ScimGroupResource replaceState(
            UUID organizationId,
            ScimGroupEntity group,
            String externalId,
            String displayName,
            List<UUID> memberIds,
            String kind) {
        if (!displayName.equals(group.getDisplayName())) {
            scimGroups
                    .findByOrganizationIdAndDisplayName(organizationId, displayName)
                    .ifPresent(other -> {
                        if (!other.getId().equals(group.getId())) {
                            throw ScimException.uniqueness(
                                    "displayName is already used by another group in this organization");
                        }
                    });
            group.setDisplayName(displayName);
        }
        if (!java.util.Objects.equals(externalId, group.getExternalId())) {
            if (externalId != null) {
                lookupByExternalId(organizationId, externalId).ifPresent(other -> {
                    if (!other.getId().equals(group.getId())) {
                        throw ScimException.uniqueness(
                                "externalId is already used by another group in this organization");
                    }
                });
            }
            group.setExternalId(externalId);
        }
        scimGroups.save(group);

        List<UUID> before = memberIdsOf(organizationId, group);
        int added = 0;
        int removed = 0;
        Set<UUID> declared = new LinkedHashSet<>(memberIds);
        for (UUID memberId : declared) {
            if (!before.contains(memberId)) {
                groupMembers.save(new ScimGroupMemberEntity(group.getId(), memberId));
                added++;
            }
        }
        for (UUID previous : before) {
            if (!declared.contains(previous)) {
                groupMembers.deleteById(new ScimGroupMemberId(group.getId(), previous));
                removed++;
            }
        }
        List<UUID> affected = new ArrayList<>(declared);
        for (UUID previous : before) {
            if (!declared.contains(previous)) {
                affected.add(previous);
            }
        }
        rederiveAll(organizationId, group, affected);
        auditSynced(organizationId, group, kind, added, removed);
        return render(organizationId, group);
    }

    private void rederiveAll(UUID organizationId, ScimGroupEntity group, List<UUID> scimUserIds) {
        for (UUID scimUserId : scimUserIds) {
            scimUsers.findByIdAndOrganizationId(scimUserId, organizationId).ifPresent(scimUser -> {
                // Only ACTIVE users re-derive: a deprovisioned user's
                // assignments stay parked until the re-provision.
                if (scimUser.isActive()) {
                    assignments.rederive(organizationId, scimUser);
                }
            });
        }
    }

    private void auditSynced(
            UUID organizationId, ScimGroupEntity group, String kind, int membersAdded, int membersRemoved) {
        audit.publish(AuditEvent.builder(AuditEventType.SCIM_GROUP_SYNCED)
                .occurredAt(Instant.now(clock))
                .orgId(organizationId)
                .success(true)
                .detail(Map.of(
                        "kind", kind,
                        "groupId", group.getId().toString(),
                        "displayName", group.getDisplayName(),
                        "externalId", group.getExternalId() == null ? "" : group.getExternalId(),
                        "membersAdded", Integer.toString(membersAdded),
                        "membersRemoved", Integer.toString(membersRemoved)))
                .build());
    }

    /** Resolves a member array against the organization's provisioned users. */
    private List<UUID> resolveMembers(UUID organizationId, List<ScimGroupResource.Member> members) {
        List<UUID> resolved = new ArrayList<>();
        if (members == null) {
            return resolved;
        }
        for (ScimGroupResource.Member member : members) {
            if (member == null || member.value() == null || member.value().isBlank()) {
                continue;
            }
            resolved.add(resolveMember(organizationId, member.value().trim()));
        }
        return resolved;
    }

    private UUID resolveMember(UUID organizationId, String scimUserId) {
        try {
            UUID id = UUID.fromString(scimUserId);
            return scimUsers
                    .findByIdAndOrganizationId(id, organizationId)
                    .map(ScimUserEntity::getId)
                    .orElseThrow(() -> ScimException.invalidValue(
                            "member '" + scimUserId + "' is not a provisioned user of this organization"));
        } catch (IllegalArgumentException e) {
            throw ScimException.invalidValue("member '" + scimUserId + "' is not a valid SCIM user id");
        }
    }

    /** The raw member values of a PATCH value node. */
    private static List<ScimGroupResource.Member> memberValues(Object value) {
        if (!(value instanceof List<?> list)) {
            throw ScimException.invalidValue("the members value must be an array of member objects");
        }
        List<ScimGroupResource.Member> members = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && map.get("value") != null) {
                members.add(new ScimGroupResource.Member(
                        map.get("value").toString(),
                        map.get("display") == null ? null : map.get("display").toString(),
                        map.get("ref") == null ? null : map.get("ref").toString()));
            }
        }
        if (members.isEmpty()) {
            throw ScimException.invalidValue("the members value must carry at least one {\"value\": ...} entry");
        }
        return members;
    }

    private List<UUID> memberIdsOf(UUID organizationId, ScimGroupEntity group) {
        return groupMembers.findByScimGroupId(group.getId()).stream()
                .map(ScimGroupMemberEntity::getScimUserId)
                .toList();
    }

    private Optional<ScimGroupEntity> lookupByExternalId(UUID organizationId, String externalId) {
        if (externalId == null) {
            return Optional.empty();
        }
        return scimGroups.findByOrganizationIdAndExternalId(organizationId, externalId);
    }

    private ScimGroupResource render(UUID organizationId, ScimGroupEntity group) {
        List<ScimGroupResource.Member> members = groupMembers.findByScimGroupId(group.getId()).stream()
                .map(membership ->
                        scimUsers.findById(membership.getScimUserId()).orElse(null))
                .filter(user -> user != null && organizationId.equals(user.getOrganizationId()))
                .map(user -> new ScimGroupResource.Member(
                        user.getId().toString(), user.getUserName(), "/scim/v2/Users/" + user.getId()))
                .toList();
        return new ScimGroupResource(
                List.of(GROUP_SCHEMA),
                group.getId().toString(),
                group.getExternalId(),
                group.getDisplayName(),
                members,
                new ScimMeta("Group", group.getCreatedAt(), group.getUpdatedAt()));
    }

    private ScimGroupEntity mustFind(UUID organizationId, UUID scimGroupId) {
        return scimGroups.findByIdAndOrganizationId(scimGroupId, organizationId).orElseThrow(ScimException::notFound);
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw ScimException.invalidValue("displayName is required");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 255) {
            throw ScimException.invalidValue("displayName must be 1-255 characters");
        }
        return trimmed;
    }

    private static String optionalBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw ScimException.invalidValue(field + " must be 1-" + max + " characters when provided");
        }
        return trimmed;
    }

    private static String stringOf(Object value, String field) {
        if (value instanceof String string) {
            return string;
        }
        throw ScimException.invalidValue(field + " must be a string");
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
    }

    /** The create answer: the resource and whether a row was created (201 vs 200). */
    public record Created(ScimGroupResource resource, boolean created) {}
}
