package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.entity.ScimConfigEntity;
import com.shipperdog.api.identity.internal.entity.ScimUserEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.ScimConfigRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupMemberRepository;
import com.shipperdog.api.identity.internal.repository.ScimGroupRepository;
import com.shipperdog.api.identity.internal.repository.ScimUserRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.identity.internal.scim.ScimException;
import com.shipperdog.api.identity.internal.scim.ScimFilter;
import com.shipperdog.api.identity.internal.scim.ScimListResponse;
import com.shipperdog.api.identity.internal.scim.ScimMeta;
import com.shipperdog.api.identity.internal.scim.ScimPatchRequest;
import com.shipperdog.api.identity.internal.scim.ScimUserResource;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import com.shipperdog.api.tester.TesterDeprovisioning;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SCIM Users resource (S14-03, issue #69, RFC 7644 section 3):
 * the joiner/leaver automation itself. Idempotent create
 * (externalId-first upsert), email-match linking, the deprovisioning
 * hard lock, re-provision re-derivation, and the filtered/paged
 * listing.
 *
 * <p><strong>Identities and joins.</strong> The SCIM id is a separate
 * UUID from the account's; externalId (the IdP's stable person id) is
 * the join key, UNIQUE per organization. Create semantics: a known
 * externalId applies the payload onto the existing resource and
 * answers 200 (never a duplicate); a userName that exists under a
 * DIFFERENT externalId is a 409; an account whose email matches links
 * (the story's conflict-handling rule) — the SSO linking discipline
 * minus the login flow.
 *
 * <p><strong>Deprovisioning is the hard lock</strong> (the
 * security-critical leaver flow, PRD 25): SCIM DELETE or active=false
 * crosses the account to LOCKED with no expiry (the idp_inactive
 * machinery: password login, token refresh, and PAT authentication
 * all refuse it immediately), revokes every refresh family (reason
 * {@code scim_deactivated}), suspends ALL tenant memberships (the
 * S6-04 cascade), and deactivates a linked tester record (its
 * membership cascade and grant inertness ride along). Re-provision
 * (active=true, or re-create) lifts the account lock and re-derives
 * assignments from the CURRENT group state — nothing suspended is
 * silently restored (the S6-04 manual-restoration doctrine); the
 * linked tester record returns to ACTIVE because the IdP's
 * re-provision is the organization's authoritative onboarding signal.
 */
@Service
public class ScimUserService {

    /** The core User schema URN every resource carries. */
    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    /** The RFC 7643 default page size and this server's page ceiling. */
    static final int DEFAULT_COUNT = 100;

    static final int MAX_COUNT = 200;

    private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    private final ScimUserRepository scimUsers;
    private final ScimGroupRepository scimGroups;
    private final ScimGroupMemberRepository groupMembers;
    private final ScimConfigRepository scimConfigs;
    private final UserRepository users;
    private final OrganizationAccess organizations;
    private final TokenService tokens;
    private final com.shipperdog.api.tenant.TenantProvisioning tenantProvisioning;
    private final TesterDeprovisioning testerDeprovisioning;
    private final ScimAssignmentService assignments;
    private final ScimProvisioningAttempt provisioning;
    private final AuditEventPublisher audit;
    private final Clock clock;

    ScimUserService(
            ScimUserRepository scimUsers,
            ScimGroupRepository scimGroups,
            ScimGroupMemberRepository groupMembers,
            ScimConfigRepository scimConfigs,
            UserRepository users,
            OrganizationAccess organizations,
            TokenService tokens,
            com.shipperdog.api.tenant.TenantProvisioning tenantProvisioning,
            TesterDeprovisioning testerDeprovisioning,
            ScimAssignmentService assignments,
            ScimProvisioningAttempt provisioning,
            AuditEventPublisher audit,
            Clock clock) {
        this.scimUsers = scimUsers;
        this.scimGroups = scimGroups;
        this.groupMembers = groupMembers;
        this.scimConfigs = scimConfigs;
        this.users = users;
        this.organizations = organizations;
        this.tokens = tokens;
        this.tenantProvisioning = tenantProvisioning;
        this.testerDeprovisioning = testerDeprovisioning;
        this.assignments = assignments;
        this.provisioning = provisioning;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * RFC 7644 section 3.3 create — the externalId-first upsert. A
     * fresh externalId provisions (linking by email match when the
     * address already belongs to an account); a known externalId
     * applies the payload onto the existing resource and answers 200.
     *
     * <p>The fresh leg runs through {@link ScimProvisioningAttempt}
     * (its own transaction, flushed inside): concurrent creates of
     * the same externalId race past the checks, and the losers of
     * the unique races ({@code uq_users_email} on the account
     * insert, the membership floor, {@code
     * uq_scim_users_external_id} on the SCIM row) re-derive the
     * committed winner and answer 200 — never the dead-catch 500 a
     * find-then-insert under this transaction produced (the #26/#27
     * discipline, held for the tenant surfaces since #28).
     */
    @Transactional
    public Created create(UUID organizationId, ScimUserResource resource) {
        Parsed payload = parse(resource, true);
        ScimUserEntity existing = scimUsers
                .findByOrganizationIdAndExternalId(organizationId, payload.externalId())
                .orElse(null);
        if (existing != null) {
            return new Created(applyUpdate(organizationId, existing, payload), false);
        }
        requireUserNameFree(organizationId, payload.userName());
        try {
            return provisioning.attempt(() -> provisionFresh(organizationId, payload));
        } catch (DataIntegrityViolationException e) {
            // Lost an insert race: only the attempt's own transaction
            // rolled back — this one stayed sound — so the concurrent
            // winner's committed row decides the answer.
            ScimUserEntity winner = scimUsers
                    .findByOrganizationIdAndExternalId(organizationId, payload.externalId())
                    .orElse(null);
            if (winner != null) {
                return new Created(applyUpdate(organizationId, winner, payload), false);
            }
            requireUserNameFree(organizationId, payload.userName());
            // No SCIM row is visible under either key: the race was on
            // the account address or the membership floor — a
            // concurrent create linked the same email under a
            // different externalId. One fresh attempt resolves it
            // exactly as the sequential order would have (the email
            // match now links instead of inserting).
            return provisioning.attempt(() -> provisionFresh(organizationId, payload));
        }
    }

    /**
     * The fresh-externalId provisioning leg, run inside the attempt's
     * own transaction: the account insert, the membership floor, and
     * the SCIM row flush BEFORE any re-derivation runs, so a lost
     * uniqueness race aborts the leg at its boundary and nothing
     * half-provisioned survives.
     */
    private Created provisionFresh(UUID organizationId, Parsed payload) {
        UserEntity account = resolveAccount(organizationId, payload);
        ScimUserEntity scimUser = scimUsers.save(new ScimUserEntity(
                organizationId, account.getId(), payload.externalId(), payload.userName(), payload.active()));
        scimUsers.flush();
        ScimAssignmentService.Outcome outcome = null;
        if (payload.active()) {
            outcome = assignments.rederive(organizationId, scimUser);
        } else {
            // active=false on create: provision, then immediately take
            // the leaver state (the honest reading of the payload).
            deprovision(organizationId, scimUser, account);
        }
        auditProvisioned(organizationId, scimUser, "created", outcome);
        return new Created(render(organizationId, scimUser, account), true);
    }

    /** The userName-under-another-externalId refusal of the create path. */
    private void requireUserNameFree(UUID organizationId, String userName) {
        scimUsers.findByOrganizationIdAndUserName(organizationId, userName).ifPresent(other -> {
            throw ScimException.uniqueness(
                    "userName is already provisioned for a different externalId in this organization");
        });
    }

    /** RFC 7644 section 3.4.1 read (by id): the uniform 404 on miss. */
    @Transactional(readOnly = true)
    public ScimUserResource get(UUID organizationId, UUID scimUserId) {
        ScimUserEntity scimUser = mustFind(organizationId, scimUserId);
        return render(organizationId, scimUser);
    }

    /**
     * RFC 7644 section 3.4.2 list: the userName/externalId filter
     * subset, startIndex (ONE-based)/count pagination, stable order.
     */
    @Transactional(readOnly = true)
    public ScimListResponse<ScimUserResource> list(UUID organizationId, ScimFilter filter, int startIndex, int count) {
        int page = Math.max(1, startIndex);
        int size = count <= 0 ? DEFAULT_COUNT : Math.min(count, MAX_COUNT);
        List<ScimUserEntity> pageRows;
        int total;
        if (filter == null) {
            pageRows = scimUsers.findByOrganizationId(organizationId, pageRequest(page, size));
            total = (int) scimUsers.countByOrganizationId(organizationId);
        } else {
            Optional<ScimUserEntity> match = filter.attribute() == ScimFilter.Attribute.USER_NAME
                    ? scimUsers.findByOrganizationIdAndUserName(organizationId, filter.value())
                    : scimUsers.findByOrganizationIdAndExternalId(organizationId, filter.value());
            pageRows = page == 1 ? match.stream().toList() : List.of();
            total = match.isPresent() ? 1 : 0;
        }
        List<ScimUserResource> resources =
                pageRows.stream().map(row -> render(organizationId, row)).toList();
        return new ScimListResponse<>(total, page, size, resources);
    }

    /** RFC 7644 section 3.5.1 replace: the full declarative state. */
    @Transactional
    public ScimUserResource replace(UUID organizationId, UUID scimUserId, ScimUserResource resource) {
        ScimUserEntity scimUser = mustFind(organizationId, scimUserId);
        return applyUpdate(organizationId, scimUser, parse(resource, false));
    }

    /**
     * RFC 7644 section 3.5.2 PATCH: the operations IdPs actually send
     * — attribute replaces (Entra's active arrives as the STRING
     * "True"/"False"), whole-object replaces on a blank path, name
     * sub-attribute adds, email array replaces. {@code remove} is
     * refused loudly (invalidPath): this subset has no removable
     * optional attribute, and a quiet acceptance would lie about
     * state.
     */
    @Transactional
    public ScimUserResource patch(UUID organizationId, UUID scimUserId, ScimPatchRequest request) {
        ScimUserEntity scimUser = mustFind(organizationId, scimUserId);
        List<ScimPatchRequest.Operation> operations =
                request == null || request.operations() == null ? List.of() : request.operations();
        if (operations.isEmpty()) {
            throw ScimException.invalidSyntax("at least one PATCH operation is required");
        }
        Mutable state = captureOf(scimUser);
        for (ScimPatchRequest.Operation operation : operations) {
            applyOperation(state, operation);
        }
        return applyUpdate(
                organizationId,
                scimUser,
                new Parsed(state.externalId, state.userName, state.formattedName, state.email, state.active));
    }

    /**
     * RFC 7644 section 3.6 DELETE — this server's deactivation
     * semantics (the story's rule): the hard-lock cascade, never a
     * row delete (the SCIM id and the join stay stable for the
     * re-provision).
     */
    @Transactional
    public void delete(UUID organizationId, UUID scimUserId) {
        ScimUserEntity scimUser = mustFind(organizationId, scimUserId);
        deprovision(organizationId, scimUser, accountOf(scimUser));
    }

    // -------------------------------------------------------------------------
    // Payload parsing
    // -------------------------------------------------------------------------

    /** The create/update payload's validated facts. */
    private record Parsed(String externalId, String userName, String formattedName, String email, boolean active) {}

    /** The mutable PATCH state captured from the current rows. */
    private static final class Mutable {
        String externalId;
        String userName;
        String formattedName;
        String email;
        boolean active;

        Mutable(String externalId, String userName, String formattedName, String email, boolean active) {
            this.externalId = externalId;
            this.userName = userName;
            this.formattedName = formattedName;
            this.email = email;
            this.active = active;
        }
    }

    private Parsed parse(ScimUserResource resource, boolean requireExternalId) {
        if (resource == null) {
            throw ScimException.invalidSyntax("a SCIM User resource body is required");
        }
        String externalId = optionalBounded(resource.externalId(), 255, "externalId");
        if (externalId == null && requireExternalId) {
            throw ScimException.invalidValue("externalId is required: it is the stable join key of provisioning");
        }
        String userName = requireBounded(resource.userName(), 320, "userName");
        String formatted = formattedNameOf(resource.name());
        String email = primaryEmailOf(resource.emails(), userName);
        boolean active = resource.active() == null || resource.active();
        return new Parsed(externalId, userName, formatted, email, active);
    }

    private static String formattedNameOf(ScimUserResource.Name name) {
        if (name == null) {
            return null;
        }
        if (name.formatted() != null && !name.formatted().isBlank()) {
            return name.formatted().trim();
        }
        StringBuilder composed = new StringBuilder();
        if (name.givenName() != null && !name.givenName().isBlank()) {
            composed.append(name.givenName().trim());
        }
        if (name.familyName() != null && !name.familyName().isBlank()) {
            if (!composed.isEmpty()) {
                composed.append(' ');
            }
            composed.append(name.familyName().trim());
        }
        return composed.isEmpty() ? null : composed.toString();
    }

    /** The account address: the primary email, else userName when email-shaped. */
    private static String primaryEmailOf(List<ScimUserResource.Email> emails, String userName) {
        if (emails != null) {
            ScimUserResource.Email primary = null;
            ScimUserResource.Email first = null;
            for (ScimUserResource.Email email : emails) {
                if (email == null || email.value() == null || email.value().isBlank()) {
                    continue;
                }
                if (first == null) {
                    first = email;
                }
                if (Boolean.TRUE.equals(email.primary())) {
                    primary = email;
                    break;
                }
            }
            ScimUserResource.Email chosen = primary != null ? primary : first;
            if (chosen != null) {
                return normalizeEmail(chosen.value());
            }
        }
        if (userName.matches(EMAIL_PATTERN)) {
            return normalizeEmail(userName);
        }
        throw ScimException.invalidValue(
                "no usable email: provide emails or an email-shaped userName (the account's address is required)");
    }

    private static String normalizeEmail(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !normalized.matches(EMAIL_PATTERN)) {
            throw ScimException.invalidValue("the email address is invalid");
        }
        return normalized;
    }

    private static String requireBounded(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            throw ScimException.invalidValue(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw ScimException.invalidValue(field + " must be 1-" + max + " characters");
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

    // -------------------------------------------------------------------------
    // Account resolution and membership
    // -------------------------------------------------------------------------

    /**
     * The account a provisioning lands on: an email match links (the
     * story's conflict rule); otherwise a fresh password-less account
     * (the JIT shape). Platform administrators are refused — the IdP
     * of one organization does not own the platform's operators.
     */
    private UserEntity resolveAccount(UUID organizationId, Parsed payload) {
        Optional<UserEntity> byEmail = users.findByEmail(payload.email());
        UserEntity account;
        if (byEmail.isPresent()) {
            account = byEmail.get();
            if (account.isPlatformAdmin()) {
                throw ScimException.uniqueness("this account cannot be provisioned through SCIM");
            }
        } else {
            String fallback = payload.email().substring(0, payload.email().indexOf('@'));
            account = users.save(new UserEntity(
                    payload.email(), null, payload.formattedName() == null ? fallback : payload.formattedName()));
        }
        ensureMembership(organizationId, account);
        return account;
    }

    /** The org membership floor: the configured default role. */
    private void ensureMembership(UUID organizationId, UserEntity account) {
        if (organizations.activeOrganizationRoles(account.getId()).containsKey(organizationId)) {
            return;
        }
        try {
            organizations.addMember(organizationId, account.getId(), defaultRoleOf(organizationId), account.getId());
        } catch (OrganizationException e) {
            throw ScimException.uniqueness("the organization refuses new members");
        }
    }

    private OrgRole defaultRoleOf(UUID organizationId) {
        return scimConfigs
                .findByOrganizationId(organizationId)
                .map(ScimConfigEntity::getDefaultRole)
                .orElse(OrgRole.VIEWER);
    }

    // -------------------------------------------------------------------------
    // Updates and the lifecycle legs
    // -------------------------------------------------------------------------

    /**
     * Applies a parsed payload onto an existing SCIM user: identity
     * fields, account display name/email, and the active switch's
     * lifecycle legs. Emits no event when nothing changed.
     */
    private ScimUserResource applyUpdate(UUID organizationId, ScimUserEntity scimUser, Parsed payload) {
        UserEntity account = accountOf(scimUser);
        Map<String, String> changes = new LinkedHashMap<>();
        if (payload.externalId() != null && !payload.externalId().equals(scimUser.getExternalId())) {
            scimUser.setExternalId(payload.externalId());
            changes.put("externalId", "changed");
        }
        if (!payload.userName().equals(scimUser.getUserName())) {
            scimUsers
                    .findByOrganizationIdAndUserName(organizationId, payload.userName())
                    .filter(other -> !other.getId().equals(scimUser.getId()))
                    .ifPresent(other -> {
                        throw ScimException.uniqueness(
                                "userName is already provisioned for a different externalId in this organization");
                    });
            scimUser.setUserName(payload.userName());
            changes.put("userName", "changed");
        }
        if (payload.formattedName() != null && !payload.formattedName().equals(account.getFullName())) {
            account.setFullName(payload.formattedName());
            changes.put("name", "changed");
        }
        if (!payload.email().equals(account.getEmail())) {
            Optional<UserEntity> holder = users.findByEmail(payload.email());
            if (holder.isPresent() && !holder.get().getId().equals(account.getId())) {
                throw ScimException.uniqueness("the email address belongs to another account");
            }
            account.setEmail(payload.email());
            changes.put("email", "changed");
        }
        users.save(account);

        boolean wasActive = scimUser.isActive();
        boolean targetActive = payload.active();
        ScimAssignmentService.Outcome outcome = null;
        if (!wasActive && targetActive) {
            // The re-provision leg audits SCIM_USER_REACTIVATED itself.
            reactivate(organizationId, scimUser, account);
            outcome = assignments.rederive(organizationId, scimUser);
        } else if (wasActive && !targetActive) {
            // The leaver leg audits SCIM_USER_DEACTIVATED itself.
            deprovision(organizationId, scimUser, account);
        } else if (wasActive) {
            outcome = assignments.rederive(organizationId, scimUser);
        }
        // Staying inactive with field changes only: the leaver state
        // holds; the attribute update below is the whole story.
        scimUsers.save(scimUser);

        if (!changes.isEmpty()) {
            Map<String, String> detail = new LinkedHashMap<>(changes);
            detail.put("externalId", scimUser.getExternalId());
            detail.put("userName", scimUser.getUserName());
            if (outcome != null) {
                fold(outcome, detail);
            }
            audit.publish(AuditEvent.builder(AuditEventType.SCIM_USER_UPDATED)
                    .occurredAt(Instant.now(clock))
                    .userId(account.getId())
                    .orgId(organizationId)
                    .success(true)
                    .detail(detail)
                    .build());
        }
        return render(organizationId, scimUser, account);
    }

    /**
     * The hard lock (the #67 idp_inactive machinery reused for the
     * SCIM leaver): LOCKED with no expiry, every refresh family
     * revoked, all tenant memberships suspended, a linked tester
     * record deactivated.
     */
    private void deprovision(UUID organizationId, ScimUserEntity scimUser, UserEntity account) {
        boolean revoked = false;
        if (account != null) {
            account.setStatus(UserEntity.Status.LOCKED);
            account.setLockedUntil(null);
            users.save(account);
            tokens.revokeRefreshTokens(account.getId(), "scim_deactivated");
            revoked = true;
        }
        int suspended =
                tenantProvisioning.suspendMembershipsOf(scimUser.getUserId(), "scim_deactivated", scimUser.getUserId());
        boolean testerDeactivated = testerDeprovisioning
                .deactivateLinked(scimUser.getUserId(), "scim_deactivated", scimUser.getUserId())
                .isPresent();
        scimUser.setActive(false);
        scimUsers.save(scimUser);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("kind", "deprovisioned");
        detail.put("externalId", scimUser.getExternalId());
        detail.put("userName", scimUser.getUserName());
        detail.put("suspendedMemberships", Integer.toString(suspended));
        detail.put("testerDeactivated", Boolean.toString(testerDeactivated));
        detail.put("refreshFamiliesRevoked", Boolean.toString(revoked));
        audit.publish(AuditEvent.builder(AuditEventType.SCIM_USER_DEACTIVATED)
                .occurredAt(Instant.now(clock))
                .userId(scimUser.getUserId())
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
    }

    /** The re-provision leg: the account lock lifts; nothing else is silently restored. */
    private void reactivate(UUID organizationId, ScimUserEntity scimUser, UserEntity account) {
        if (account.getStatus() == UserEntity.Status.LOCKED && account.getLockedUntil() == null) {
            account.setStatus(UserEntity.Status.ACTIVE);
            account.setLockedUntil(null);
            users.save(account);
        }
        scimUser.setActive(true);
        testerDeprovisioning.reactivateLinked(scimUser.getUserId(), scimUser.getUserId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("kind", "reprovisioned");
        detail.put("externalId", scimUser.getExternalId());
        detail.put("userName", scimUser.getUserName());
        audit.publish(AuditEvent.builder(AuditEventType.SCIM_USER_REACTIVATED)
                .occurredAt(Instant.now(clock))
                .userId(scimUser.getUserId())
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
    }

    private void auditProvisioned(
            UUID organizationId, ScimUserEntity scimUser, String kind, ScimAssignmentService.Outcome outcome) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("kind", kind);
        detail.put("externalId", scimUser.getExternalId());
        detail.put("userName", scimUser.getUserName());
        if (outcome != null) {
            fold(outcome, detail);
        }
        audit.publish(AuditEvent.builder(AuditEventType.SCIM_USER_PROVISIONED)
                .occurredAt(Instant.now(clock))
                .userId(scimUser.getUserId())
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
    }

    private static void fold(ScimAssignmentService.Outcome outcome, Map<String, String> detail) {
        if (outcome.mappedRole() != null) {
            detail.put("mappedRole", outcome.mappedRole().name());
        }
        if (outcome.roleRefused()) {
            detail.put("roleChangeRefused", "true");
        }
        detail.put("tenantsEnsured", Integer.toString(outcome.tenantsEnsured()));
        detail.put("tenantsSkipped", Integer.toString(outcome.tenantsSkipped()));
    }

    // -------------------------------------------------------------------------
    // PATCH application
    // -------------------------------------------------------------------------

    private Mutable captureOf(ScimUserEntity scimUser) {
        UserEntity account = accountOf(scimUser);
        return new Mutable(
                scimUser.getExternalId(),
                scimUser.getUserName(),
                account.getFullName(),
                account.getEmail(),
                scimUser.isActive());
    }

    private static void applyOperation(Mutable state, ScimPatchRequest.Operation operation) {
        String op = operation.op() == null ? "" : operation.op().trim().toLowerCase(Locale.ROOT);
        String path = operation.path() == null ? "" : operation.path().trim();
        if ("remove".equals(op)) {
            throw ScimException.invalidPath(
                    "remove operations are not supported for User attributes; use replace (deprovisioning is"
                            + " PATCH active=false or DELETE)");
        }
        if (!"replace".equals(op) && !"add".equals(op)) {
            throw ScimException.invalidPath("unsupported PATCH op '" + operation.op() + "'");
        }
        Object value = operation.value();
        switch (path) {
            case "" -> applyObject(state, value);
            case "active" -> state.active = booleanOf(value, "active");
            case "userName" -> state.userName = requireBounded(stringOf(value, "userName"), 320, "userName");
            case "externalId" -> state.externalId = optionalBounded(stringOf(value, "externalId"), 255, "externalId");
            case "name" -> {
                if (value instanceof Map<?, ?> map) {
                    String formatted = formattedNameOf(nameOf(map));
                    if (formatted != null) {
                        state.formattedName = formatted;
                    }
                } else {
                    state.formattedName = requireBounded(stringOf(value, "name"), 255, "name");
                }
            }
            case "name.formatted", "name.givenName", "name.familyName" ->
                state.formattedName = requireBounded(stringOf(value, path), 255, path);
            case "emails" -> state.email = primaryEmailOf(emailsOf(value), state.userName);
            default -> throw ScimException.invalidPath("unsupported PATCH path '" + path + "'");
        }
    }

    /** The blank-path whole-object replace (Entra's bulk shape). */
    private static void applyObject(Mutable state, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw ScimException.invalidPath("a blank path requires a User object value");
        }
        if (map.containsKey("active")) {
            state.active = booleanOf(map.get("active"), "active");
        }
        if (map.containsKey("userName")) {
            state.userName = requireBounded(stringOf(map.get("userName"), "userName"), 320, "userName");
        }
        if (map.containsKey("externalId")) {
            state.externalId = optionalBounded(stringOf(map.get("externalId"), "externalId"), 255, "externalId");
        }
        if (map.containsKey("name") && map.get("name") instanceof Map<?, ?> nameMap) {
            String formatted = formattedNameOf(nameOf(nameMap));
            if (formatted != null) {
                state.formattedName = formatted;
            }
        }
        if (map.containsKey("emails")) {
            state.email = primaryEmailOf(emailsOf(map.get("emails")), state.userName);
        }
    }

    /** Entra's quirk: booleans arrive as the strings "True"/"False". */
    private static boolean booleanOf(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && !string.isBlank()) {
            return Boolean.parseBoolean(string.trim());
        }
        throw ScimException.invalidValue(field + " must be a boolean");
    }

    private static String stringOf(Object value, String field) {
        if (value instanceof String string) {
            return string;
        }
        throw ScimException.invalidValue(field + " must be a string");
    }

    private static ScimUserResource.Name nameOf(Map<?, ?> map) {
        String formatted =
                map.get("formatted") == null ? null : map.get("formatted").toString();
        String family =
                map.get("familyName") == null ? null : map.get("familyName").toString();
        String given =
                map.get("givenName") == null ? null : map.get("givenName").toString();
        return new ScimUserResource.Name(formatted, family, given);
    }

    private static List<ScimUserResource.Email> emailsOf(Object value) {
        if (!(value instanceof List<?> list)) {
            throw ScimException.invalidValue("emails must be an array");
        }
        List<ScimUserResource.Email> emails = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                Object address = map.get("value");
                Object primary = map.get("primary");
                emails.add(new ScimUserResource.Email(
                        address == null ? null : address.toString(),
                        map.get("type") == null ? null : map.get("type").toString(),
                        primary == null ? null : Boolean.parseBoolean(primary.toString())));
            }
        }
        return emails;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /** Renders the wire shape: the SCIM id, derived groups, and meta. */
    private ScimUserResource render(UUID organizationId, ScimUserEntity scimUser) {
        return render(organizationId, scimUser, accountOf(scimUser));
    }

    private ScimUserResource render(UUID organizationId, ScimUserEntity scimUser, UserEntity account) {
        List<ScimUserResource.GroupRef> groupRefs = groupMembers.findByScimUserId(scimUser.getId()).stream()
                .map(membership ->
                        scimGroups.findById(membership.getScimGroupId()).orElse(null))
                .filter(group -> group != null && organizationId.equals(group.getOrganizationId()))
                .map(group -> new ScimUserResource.GroupRef(
                        group.getId().toString(), group.getDisplayName(), "/scim/v2/Groups/" + group.getId()))
                .toList();
        String email = account == null ? scimUser.getUserName() : account.getEmail();
        return new ScimUserResource(
                List.of(USER_SCHEMA),
                scimUser.getId().toString(),
                scimUser.getExternalId(),
                scimUser.getUserName(),
                new ScimUserResource.Name(account == null ? null : account.getFullName(), null, null),
                scimUser.isActive(),
                List.of(new ScimUserResource.Email(email, "work", true)),
                groupRefs,
                new ScimMeta("User", scimUser.getCreatedAt(), scimUser.getUpdatedAt()));
    }

    private UserEntity accountOf(ScimUserEntity scimUser) {
        return users.findById(scimUser.getUserId()).orElseThrow(ScimException::notFound);
    }

    private ScimUserEntity mustFind(UUID organizationId, UUID scimUserId) {
        return scimUsers.findByIdAndOrganizationId(scimUserId, organizationId).orElseThrow(ScimException::notFound);
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
    }

    /** The create answer: the resource and whether a row was created (201 vs 200). */
    public record Created(ScimUserResource resource, boolean created) {}
}
