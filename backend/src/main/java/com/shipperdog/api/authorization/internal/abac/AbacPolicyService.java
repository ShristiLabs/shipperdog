package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AbacCondition;
import com.shipperdog.api.authorization.AbacPolicyException;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.Effect;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.organization.OrganizationAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The ABAC policy surface's core (issue #70, S14-04): the
 * organization-grain policy CRUD with write-time validation (the
 * S10-06 discipline — a malformed condition or an unresolvable
 * reference NEVER reaches storage; AC4's "syntax errors rejected at
 * save" is answered here, the console editor renders these errors)
 * and the VERSIONING AC4 demands: every create and replace appends
 * one immutable {@code abac_policy_versions} row (the complete
 * authored facts, the author, the moment) while the policy row holds
 * the current standing — history is the data, never a diff to
 * reconstruct.
 *
 * <p>Policies are reference data the rings/workflow-definitions way:
 * the organization dimension only, every write audited
 * (ABAC_POLICY_CREATED/UPDATED/DELETED with the whole authored
 * facts), and the in-memory compiled-policy cache dropped
 * write-through at commit so a saved policy evaluates on the very
 * next decision (the single-node immediacy; the TTL is the
 * cross-instance backstop).
 */
@Service
public class AbacPolicyService {

    /** The policy name shape: lowercase kebab, 3-64 (the ring code family). */
    private static final String NAME_PATTERN = "^[a-z][a-z0-9-]{2,63}$";

    private final AbacPolicyRepository policies;
    private final AbacPolicyVersionRepository versions;
    private final AbacPolicyCache cache;
    private final OrganizationAccess organizations;
    private final ApplicationAccess applications;
    private final EnvironmentAccess environments;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    AbacPolicyService(
            AbacPolicyRepository policies,
            AbacPolicyVersionRepository versions,
            AbacPolicyCache cache,
            OrganizationAccess organizations,
            ApplicationAccess applications,
            EnvironmentAccess environments,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.policies = policies;
        this.versions = versions;
        this.cache = cache;
        this.organizations = organizations;
        this.applications = applications;
        this.environments = environments;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // The policy store: list, detail, history, create, replace, delete
    // -------------------------------------------------------------------------

    /** The organization's policies in the defined evaluation order (the console editor's list). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PolicyView> list(
            UUID actorId, UUID organizationId, long offset, int limit) {
        requireOrganization(organizationId);
        List<PolicyView> all = policies.findByOrganizationIdOrderByPositionAscNameAsc(organizationId).stream()
                .map(policy -> viewOf(policy))
                .toList();
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min(offset + limit, all.size());
        return new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), new com.shipperdog.api.config.CursorPageable(offset, limit), all.size());
    }

    /** One policy of the organization: a foreign policy conceals exactly like an unknown id. */
    @Transactional(readOnly = true)
    public PolicyView get(UUID actorId, UUID organizationId, UUID policyId) {
        requireOrganization(organizationId);
        return viewOf(
                policies.findByIdAndOrganizationId(policyId, organizationId).orElseThrow(AbacPolicyService::notFound));
    }

    /** The policy's immutable version history, newest first (rows survive the policy's deletion). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<VersionView> history(
            UUID actorId, UUID organizationId, UUID policyId, long offset, int limit) {
        requireOrganization(organizationId);
        // The loose-key read: history serves even after deletion, but
        // only to the organization that owns it (a foreign
        // organization's history is the concealed miss).
        if (policies.findByIdAndOrganizationId(policyId, organizationId).isEmpty()
                && versions.findByPolicyIdOrderByVersionDesc(policyId).stream()
                        .noneMatch(row -> row.getOrganizationId().equals(organizationId))) {
            throw notFound();
        }
        List<VersionView> all = versions.findByPolicyIdOrderByVersionDesc(policyId).stream()
                .filter(row -> row.getOrganizationId().equals(organizationId))
                .map(row -> new VersionView(
                        row.getId(),
                        row.getPolicyId(),
                        row.getName(),
                        row.getVersion(),
                        row.getEffect().name(),
                        row.getAction().name(),
                        row.getApplicationId(),
                        row.getEnvironmentId(),
                        row.getRing(),
                        row.getCondition(),
                        conditionSummary(row.getCondition()),
                        row.getPosition(),
                        row.isEnabled(),
                        row.getAuthoredBy(),
                        row.getCreatedAt()))
                .toList();
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min(offset + limit, all.size());
        return new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), new com.shipperdog.api.config.CursorPageable(offset, limit), all.size());
    }

    /**
     * Creates a policy (AC4): every reference validated before any
     * write — the condition parses (the typed tree, bounded), the
     * action/attribute pairing is sound (release-grain atoms only on
     * PROMOTE policies — the surfaces that carry them), the selectors
     * resolve (the application belongs to the organization, the
     * environment is its own, the ring code is in its vocabulary),
     * the name is unique. Audited; born at version 1 with its history
     * row. Honors {@code Idempotency-Key}.
     */
    @Transactional
    public PolicyView create(UUID actorId, PolicyCommand command) {
        requireOrganization(command.organizationId());
        Validated validated = validated(command.organizationId(), command);
        if (policies.findByOrganizationIdAndName(command.organizationId(), validated.name())
                .isPresent()) {
            throw nameTaken();
        }
        AbacPolicyEntity policy = new AbacPolicyEntity(
                command.organizationId(),
                validated.name(),
                validated.effect(),
                validated.action(),
                validated.applicationId(),
                validated.environmentId(),
                validated.ring(),
                validated.condition(),
                validated.position(),
                validated.enabled());
        AbacPolicyEntity saved;
        try {
            saved = policies.saveAndFlush(policy);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("uq_abac_policies_org_name")) {
                throw nameTaken();
            }
            throw e;
        }
        versions.saveAndFlush(new AbacPolicyVersionEntity(
                saved.getId(),
                command.organizationId(),
                saved.getName(),
                saved.getVersion(),
                saved.getEffect(),
                saved.getAction(),
                saved.getApplicationId(),
                saved.getEnvironmentId(),
                saved.getRing(),
                saved.getCondition(),
                saved.getPosition(),
                saved.isEnabled(),
                actorId));
        auditPolicy(actorId, command.organizationId(), AuditEventType.ABAC_POLICY_CREATED, saved, null);
        invalidateAtCommit(command.organizationId());
        return viewOf(saved);
    }

    /**
     * Replaces a policy (the editor's PUT): the policy's meaning end
     * to end, revalidated as a whole (a half-edited policy never
     * reaches storage), versioned — the current row moves to the new
     * version and the immutable history row lands beside it. A policy
     * of another organization conceals like unknown. Audited with the
     * changed facts. Honors {@code Idempotency-Key}.
     */
    @Transactional
    public PolicyView update(UUID actorId, UUID organizationId, UUID policyId, PolicyCommand command) {
        requireOrganization(organizationId);
        AbacPolicyEntity policy =
                policies.findByIdAndOrganizationId(policyId, organizationId).orElseThrow(AbacPolicyService::notFound);
        Validated validated = validated(organizationId, command);
        Optional<AbacPolicyEntity> named = policies.findByOrganizationIdAndName(organizationId, validated.name());
        if (named.isPresent() && !named.get().getId().equals(policyId)) {
            throw nameTaken();
        }
        Map<String, String> changed = new LinkedHashMap<>();
        if (!policy.getName().equals(validated.name())) {
            changed.put("name", policy.getName() + " -> " + validated.name());
        }
        if (policy.getEffect() != validated.effect()) {
            changed.put(
                    "effect",
                    policy.getEffect().name() + " -> " + validated.effect().name());
        }
        if (policy.getAction() != validated.action()) {
            changed.put(
                    "action",
                    policy.getAction().name() + " -> " + validated.action().name());
        }
        if (!Objects.equals(policy.getApplicationId(), validated.applicationId())) {
            changed.put("applicationId", String.valueOf(validated.applicationId()));
        }
        if (!Objects.equals(policy.getEnvironmentId(), validated.environmentId())) {
            changed.put("environmentId", String.valueOf(validated.environmentId()));
        }
        if (!Objects.equals(policy.getRing(), validated.ring())) {
            changed.put("ring", validated.ring() == null ? "-" : validated.ring());
        }
        if (!Objects.equals(policy.getCondition(), validated.condition())) {
            changed.put("condition", conditionSummary(validated.condition()));
        }
        if (policy.getPosition() != validated.position()) {
            changed.put("position", policy.getPosition() + " -> " + validated.position());
        }
        if (policy.isEnabled() != validated.enabled()) {
            changed.put("enabled", policy.isEnabled() + " -> " + validated.enabled());
        }
        policy.edit(
                validated.name(),
                validated.effect(),
                validated.action(),
                validated.applicationId(),
                validated.environmentId(),
                validated.ring(),
                validated.condition(),
                validated.position(),
                validated.enabled());
        // Fold of #70's review follow-up: a concurrent replace of the
        // same policy wins the version history's uniqueness first and
        // the loser's flush trips it -- translate that race (and the
        // concurrent rename's name index) to the public 409 here,
        // inside the method, instead of the opaque 500 the raw
        // violation rendered. The rollback is safe either way.
        try {
            policies.flush();
            versions.saveAndFlush(new AbacPolicyVersionEntity(
                    policy.getId(),
                    organizationId,
                    policy.getName(),
                    policy.getVersion(),
                    policy.getEffect(),
                    policy.getAction(),
                    policy.getApplicationId(),
                    policy.getEnvironmentId(),
                    policy.getRing(),
                    policy.getCondition(),
                    policy.getPosition(),
                    policy.isEnabled(),
                    actorId));
        } catch (DataIntegrityViolationException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("uq_abac_policy_versions")) {
                throw new AbacPolicyException(
                        AbacPolicyException.Code.CONFLICT, "the policy changed concurrently; reload and retry");
            }
            if (message.contains("uq_abac_policies_org_name")) {
                throw nameTaken();
            }
            throw e;
        }
        auditPolicy(actorId, organizationId, AuditEventType.ABAC_POLICY_UPDATED, policy, changed);
        invalidateAtCommit(organizationId);
        return viewOf(policy);
    }

    /**
     * Deletes a policy: the current row goes, the immutable version
     * history stays (the V34 evaluation-log precedent — "which rule
     * version denied last month" survives the deletion), and the
     * deletion is audited with the full deleted facts. The next
     * decision sees the policy gone (write-through cache drop).
     */
    @Transactional
    public void delete(UUID actorId, UUID organizationId, UUID policyId) {
        requireOrganization(organizationId);
        AbacPolicyEntity policy =
                policies.findByIdAndOrganizationId(policyId, organizationId).orElseThrow(AbacPolicyService::notFound);
        policies.delete(policy);
        policies.flush();
        Map<String, String> detail = policyDetail(policy);
        detail.put("deleted", "true");
        trace.organization(organizationId);
        audit.publish(AuditEvent.builder(AuditEventType.ABAC_POLICY_DELETED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
        invalidateAtCommit(organizationId);
    }

    // -------------------------------------------------------------------------
    // Validation (AC4): shape, references, and action/attribute pairing
    // -------------------------------------------------------------------------

    /** One validated policy's facts (the create/update surfaces' shared authority). */
    private record Validated(
            String name,
            Effect effect,
            GoverningAction action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            int position,
            boolean enabled) {}

    private Validated validated(UUID organizationId, PolicyCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = command.name() == null ? "" : command.name().trim().toLowerCase(Locale.ROOT);
        if (!name.matches(NAME_PATTERN)) {
            errors.put("name", "3-64 chars, lowercase letters/digits/hyphens, starting with a letter");
        }
        Effect effect = parseEnum(Effect.class, command.effect(), "effect", errors);
        GoverningAction action = parseEnum(GoverningAction.class, command.action(), "action", errors);
        UUID applicationId = validateApplication(organizationId, command, errors);
        UUID environmentId = validateEnvironment(organizationId, command, errors);
        String ring = validateRing(organizationId, command, errors);
        List<String> conditionErrors = new ArrayList<>();
        AbacCondition condition = command.condition() == null
                ? null
                : AbacCondition.parse(command.condition(), "condition", conditionErrors);
        if (command.condition() == null) {
            conditionErrors.add("condition: a condition is required");
        }
        errors.putAll(indexed(conditionErrors));
        if (condition != null && AbacCondition.nodeCountOf(condition) > AbacCondition.MAX_NODES) {
            errors.put("condition", "at most " + AbacCondition.MAX_NODES + " condition nodes are allowed");
        }
        if (condition != null && action != null) {
            if (action != GoverningAction.PROMOTE && referencesReleaseGrainAttributes(condition)) {
                errors.put(
                        "condition",
                        "environmentName, ringCode, and securityStatus are release attributes "
                                + "carried by the PROMOTE consult alone; a policy governing " + action
                                + " cannot reference them");
            }
            if (action == GoverningAction.LIST_APPLICATIONS && referencesPlatform(condition)) {
                errors.put(
                        "condition",
                        "applicationPlatform never resolves for a LIST_APPLICATIONS decision "
                                + "(no platform is pinned); reference it from READ_ARTIFACT or DOWNLOAD policies");
            }
        }
        // Name atoms (applicationName, environmentName) are
        // evaluation-time matches by design: the registry's names are
        // live data the modules expose by id, not by org-wide listing,
        // and a renamed application flips a referencing condition to
        // NOT_MET — the fail-closed direction for REQUIRE, the
        // documented miss for DENY (the ring-code precedent).
        Integer position = command.position();
        if (position == null || position < 0 || position > 999) {
            errors.put("position", "0-999 (the evaluation order)");
        }
        if (!errors.isEmpty()) {
            throw new AbacPolicyException(AbacPolicyException.Code.INVALID_REQUEST, "the request is invalid", errors);
        }
        return new Validated(
                name,
                effect,
                action,
                applicationId,
                environmentId,
                ring,
                command.condition(),
                position,
                command.enabled() == null || command.enabled());
    }

    private UUID validateApplication(UUID organizationId, PolicyCommand command, Map<String, String> errors) {
        UUID applicationId = command.applicationId();
        if (applicationId == null) {
            return null;
        }
        ApplicationAccess.ApplicationProfile profile =
                applications.profileOf(applicationId).orElse(null);
        if (profile == null || !profile.organizationId().equals(organizationId)) {
            errors.put("applicationId", "unknown application of this organization");
            return null;
        }
        return applicationId;
    }

    private UUID validateEnvironment(UUID organizationId, PolicyCommand command, Map<String, String> errors) {
        UUID environmentId = command.environmentId();
        if (environmentId == null) {
            return null;
        }
        if (!organizationId.equals(
                environments.owningOrganizationOf(environmentId).orElse(null))) {
            errors.put("environmentId", "unknown environment of this organization");
            return null;
        }
        return environmentId;
    }

    private String validateRing(UUID organizationId, PolicyCommand command, Map<String, String> errors) {
        if (command.ring() == null || command.ring().isBlank()) {
            return null;
        }
        // A NAME-ONLY code (the V34 release-policy precedent): the
        // ring vocabulary is the organization's to redefine and lives
        // behind the release module's boundary, so the write surface
        // normalizes and stores — the console's editor picks from the
        // organization's live vocabulary — and an unmatched code at
        // evaluation means the policy does not apply (documented, the
        // same standing a redefined ring code carries).
        return command.ring().trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the tree references the release-grain atoms only the PROMOTE consult carries. */
    private static boolean referencesReleaseGrainAttributes(AbacCondition condition) {
        return switch (condition) {
            case AbacCondition.AllOf all ->
                all.children().stream().anyMatch(AbacPolicyService::referencesReleaseGrainAttributes);
            case AbacCondition.AnyOf any ->
                any.children().stream().anyMatch(AbacPolicyService::referencesReleaseGrainAttributes);
            case AbacCondition.EnvironmentName ignored -> true;
            case AbacCondition.RingCode ignored -> true;
            case AbacCondition.SecurityStatus ignored -> true;
            default -> false;
        };
    }

    private static boolean referencesPlatform(AbacCondition condition) {
        return switch (condition) {
            case AbacCondition.AllOf all -> all.children().stream().anyMatch(AbacPolicyService::referencesPlatform);
            case AbacCondition.AnyOf any -> any.children().stream().anyMatch(AbacPolicyService::referencesPlatform);
            case AbacCondition.ApplicationPlatform ignored -> true;
            default -> false;
        };
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String value, String field, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put(field, field + " is required");
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.put(field, "unknown " + field + " '" + value + "'");
            return null;
        }
    }

    private static Map<String, String> indexed(List<String> messages) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (String message : messages) {
            String field = message.contains(":") ? message.substring(0, message.indexOf(':')) : "condition";
            errors.putIfAbsent(
                    field, message.substring(message.indexOf(':') + 1).trim());
        }
        return errors;
    }

    private void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw new AbacPolicyException(
                    AbacPolicyException.Code.INVALID_REQUEST,
                    "the request is invalid",
                    Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(organizationId)) {
            throw new AbacPolicyException(AbacPolicyException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    /** The cache drop at commit: a reader never sees committed state through a stale compile. */
    private void invalidateAtCommit(UUID organizationId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.invalidate(organizationId);
            }
        });
    }

    private void auditPolicy(
            UUID actorId,
            UUID organizationId,
            AuditEventType type,
            AbacPolicyEntity policy,
            Map<String, String> changed) {
        Map<String, String> detail = policyDetail(policy);
        if (changed != null) {
            detail.putAll(changed);
        }
        trace.organization(organizationId);
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
    }

    private static Map<String, String> policyDetail(AbacPolicyEntity policy) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", policy.getName());
        detail.put("version", String.valueOf(policy.getVersion()));
        detail.put("effect", policy.getEffect().name());
        detail.put("action", policy.getAction().name());
        detail.put("condition", conditionSummary(policy.getCondition()));
        detail.put("position", String.valueOf(policy.getPosition()));
        detail.put("enabled", String.valueOf(policy.isEnabled()));
        return detail;
    }

    private static String conditionSummary(Map<String, Object> condition) {
        AbacCondition parsed = AbacCondition.parse(condition, "condition", new ArrayList<>());
        return parsed == null ? "unreadable" : parsed.describe();
    }

    private static PolicyView viewOf(AbacPolicyEntity policy) {
        return new PolicyView(
                policy.getId(),
                policy.getOrganizationId(),
                policy.getName(),
                policy.getVersion(),
                policy.getEffect().name(),
                policy.getAction().name(),
                policy.getApplicationId(),
                policy.getEnvironmentId(),
                policy.getRing(),
                policy.getCondition(),
                conditionSummary(policy.getCondition()),
                policy.getPosition(),
                policy.isEnabled(),
                policy.getCreatedAt(),
                policy.getUpdatedAt());
    }

    private static AbacPolicyException notFound() {
        return new AbacPolicyException(AbacPolicyException.Code.NOT_FOUND, "policy not found");
    }

    private static AbacPolicyException nameTaken() {
        return new AbacPolicyException(AbacPolicyException.Code.NAME_TAKEN, "a policy with this name already exists");
    }

    // -------------------------------------------------------------------------
    // Commands and views
    // -------------------------------------------------------------------------

    /** One policy's create/replace command: the policy's meaning end to end. */
    public record PolicyCommand(
            UUID organizationId,
            String name,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            Integer position,
            Boolean enabled) {}

    /** One policy read back: the current standing with the condition and its human summary. */
    public record PolicyView(
            UUID id,
            UUID organizationId,
            String name,
            int version,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            String conditionSummary,
            int position,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {}

    /** One immutable history row: the complete authored facts with the author and the moment. */
    public record VersionView(
            UUID id,
            UUID policyId,
            String name,
            int version,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            String conditionSummary,
            int position,
            boolean enabled,
            UUID authoredBy,
            Instant createdAt) {}
}
