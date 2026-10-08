package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.gate.GateDefinitionAccess;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.PolicyCondition;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.policy.ReleasePolicyHoldEntity.Status;
import com.shipperdog.api.release.internal.policy.ReleasePolicyRuleEntity.Action;
import com.shipperdog.api.release.internal.policy.ReleasePolicyRuleEntity.Scope;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.release.internal.service.RingService;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The policy surface's core (issue #45, S10-06, PRD 33): the
 * organization-grain rule CRUD with write-time validation (the S10-02
 * discipline: a malformed condition or an unresolvable reference
 * NEVER reaches storage — AC6's "expression compiles, references
 * resolvable" is answered here, the console editor renders these
 * errors), the skip-relaxation settings (the seam's widening knob),
 * and the release-addressed reads and acts: the on-demand dry-run
 * (AC3 — the same evaluation the promote act runs, answered as a
 * read with nothing written), the hold ledger with its explicit
 * privileged acknowledge (AC2, audited), and the evaluation log
 * (AC5's per-attempt record).
 *
 * <p>The rule model is DATA, not code (the story's whole point):
 * standing rules an organization authors once — scoped org-wide, to
 * an application, to the ring a hop ENTERS, or to the release's
 * targeted environment — and every promotion attempt of scope
 * evaluates mechanically thereafter. Rules are reference data the
 * rings/workflow-definitions way: the organization dimension only,
 * every write audited, deletion refused while the hold ledger
 * references the rule (the ledger is audit evidence; the evaluation
 * log keeps loose name snapshots and survives deletion by design).
 */
@Service
public class ReleasePolicyService {

    /** The rule name shape: lowercase kebab, 3-64 (the ring code family). */
    private static final String NAME_PATTERN = "^[a-z][a-z0-9-]{2,63}$";

    private final ReleasePolicyRuleRepository rules;
    private final ReleasePolicyHoldRepository holds;
    private final ReleasePolicyEvaluationRepository evaluations;
    private final ReleasePolicyEvaluationRuleRepository evaluationRules;
    private final ReleasePolicySettingsRepository settings;
    private final ReleasePolicyEngine engine;
    private final RingService ringService;
    private final RingRepository rings;
    private final OrganizationAccess organizations;
    private final ApplicationAccess applications;
    private final EnvironmentAccess environments;
    private final GateDefinitionAccess gateDefinitions;
    private final ReleaseScopedRepository releases;
    private final ReleaseTargetRepository targets;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    ReleasePolicyService(
            ReleasePolicyRuleRepository rules,
            ReleasePolicyHoldRepository holds,
            ReleasePolicyEvaluationRepository evaluations,
            ReleasePolicyEvaluationRuleRepository evaluationRules,
            ReleasePolicySettingsRepository settings,
            ReleasePolicyEngine engine,
            RingService ringService,
            RingRepository rings,
            OrganizationAccess organizations,
            ApplicationAccess applications,
            EnvironmentAccess environments,
            GateDefinitionAccess gateDefinitions,
            ReleaseScopedRepository releases,
            ReleaseTargetRepository targets,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.rules = rules;
        this.holds = holds;
        this.evaluations = evaluations;
        this.evaluationRules = evaluationRules;
        this.settings = settings;
        this.engine = engine;
        this.ringService = ringService;
        this.rings = rings;
        this.organizations = organizations;
        this.applications = applications;
        this.environments = environments;
        this.gateDefinitions = gateDefinitions;
        this.releases = releases;
        this.targets = targets;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // The rule store (AC1/AC6): list, detail, create, replace, delete
    // -------------------------------------------------------------------------

    /** The organization's rules in the defined evaluation order (the governance console's list). */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RuleView> list(
            UUID actorId, UUID organizationId, long offset, int limit) {
        requireOrganization(organizationId);
        List<RuleView> all = rules.findByOrganizationIdOrderByPositionAscNameAsc(organizationId).stream()
                .map(rule -> viewOf(rule))
                .toList();
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min(offset + limit, all.size());
        return new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), new com.shipperdog.api.config.CursorPageable(offset, limit), all.size());
    }

    /** One rule of the organization: a rule of another organization conceals exactly like an unknown id. */
    @Transactional(readOnly = true)
    public RuleView get(UUID actorId, UUID organizationId, UUID ruleId) {
        requireOrganization(organizationId);
        return viewOf(
                rules.findByIdAndOrganizationId(ruleId, organizationId).orElseThrow(ReleasePolicyService::notFound));
    }

    /**
     * Creates a rule (AC1/AC6): every reference validated before any
     * write — the condition parses (the typed tree, bounded), the
     * scope's reference resolves (the application belongs to the
     * organization, the ring code is in its vocabulary, the
     * environment is its own, the gate type is a known kind), the
     * name is unique. One name per organization (the precise 409).
     * Audited with the whole rule. Honors {@code Idempotency-Key}.
     */
    @Transactional
    public RuleView create(UUID actorId, CreateCommand command) {
        requireOrganization(command.organizationId());
        Validated validated = validated(command.organizationId(), command);
        if (rules.findByOrganizationIdAndName(command.organizationId(), validated.name())
                .isPresent()) {
            throw nameTaken();
        }
        ReleasePolicyRuleEntity rule = new ReleasePolicyRuleEntity(
                command.organizationId(),
                validated.name(),
                validated.scope(),
                validated.applicationId(),
                validated.ring(),
                validated.environmentId(),
                validated.action(),
                validated.condition(),
                validated.position(),
                validated.enabled());
        ReleasePolicyRuleEntity saved;
        try {
            saved = rules.saveAndFlush(rule);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("uq_release_policy_rules_org_name")) {
                throw nameTaken();
            }
            throw e;
        }
        auditRule(actorId, command.organizationId(), AuditEventType.RELEASE_POLICY_RULE_CREATED, saved, null);
        return viewOf(saved);
    }

    /**
     * Replaces a rule (the editor's PUT): the rule's meaning end to
     * end — name, scope, action, condition, position, enabled —
     * revalidated as a whole (a half-edited rule never reaches
     * storage). A rule of another organization conceals like unknown.
     * Audited with the changed facts.
     */
    @Transactional
    public RuleView update(UUID actorId, UUID organizationId, UUID ruleId, CreateCommand command) {
        requireOrganization(organizationId);
        ReleasePolicyRuleEntity rule =
                rules.findByIdAndOrganizationId(ruleId, organizationId).orElseThrow(ReleasePolicyService::notFound);
        Validated validated = validated(organizationId, command);
        Optional<ReleasePolicyRuleEntity> named = rules.findByOrganizationIdAndName(organizationId, validated.name());
        if (named.isPresent() && !named.get().getId().equals(ruleId)) {
            throw nameTaken();
        }
        Map<String, String> changed = new LinkedHashMap<>();
        if (!rule.getName().equals(validated.name())) {
            changed.put("name", rule.getName() + " -> " + validated.name());
        }
        if (rule.getScope() != validated.scope()) {
            changed.put(
                    "scope", rule.getScope().name() + " -> " + validated.scope().name());
        }
        if (!Objects.equals(rule.getApplicationId(), validated.applicationId())) {
            changed.put("applicationId", String.valueOf(validated.applicationId()));
        }
        if (!Objects.equals(rule.getRing(), validated.ring())) {
            changed.put("ring", validated.ring() == null ? "-" : validated.ring());
        }
        if (!Objects.equals(rule.getEnvironmentId(), validated.environmentId())) {
            changed.put("environmentId", String.valueOf(validated.environmentId()));
        }
        if (rule.getAction() != validated.action()) {
            changed.put(
                    "action",
                    rule.getAction().name() + " -> " + validated.action().name());
        }
        if (!Objects.equals(rule.getCondition(), validated.condition())) {
            changed.put("condition", conditionSummary(validated.condition()));
        }
        if (rule.getPosition() != validated.position()) {
            changed.put("position", rule.getPosition() + " -> " + validated.position());
        }
        if (rule.isEnabled() != validated.enabled()) {
            changed.put("enabled", rule.isEnabled() + " -> " + validated.enabled());
        }
        rule.edit(
                validated.name(),
                validated.scope(),
                validated.applicationId(),
                validated.ring(),
                validated.environmentId(),
                validated.action(),
                validated.condition(),
                validated.position(),
                validated.enabled());
        rules.flush();
        auditRule(actorId, organizationId, AuditEventType.RELEASE_POLICY_RULE_UPDATED, rule, changed);
        return viewOf(rule);
    }

    /**
     * Deletes a rule: a rule the hold ledger references refuses (the
     * ledger is audit evidence — edit the rule to {@code enabled =
     * false} instead); the evaluation log's name snapshots survive by
     * design. Audited with the full deleted facts.
     */
    @Transactional
    public void delete(UUID actorId, UUID organizationId, UUID ruleId) {
        requireOrganization(organizationId);
        ReleasePolicyRuleEntity rule =
                rules.findByIdAndOrganizationId(ruleId, organizationId).orElseThrow(ReleasePolicyService::notFound);
        if (holds.existsByRuleId(ruleId)) {
            throw new ReleaseException(
                    ReleaseException.Code.POLICY_RULE_IN_USE,
                    "the rule is referenced by recorded policy holds; disable it instead of deleting it",
                    Map.of("rule", rule.getName()));
        }
        rules.delete(rule);
        rules.flush();
        Map<String, String> detail = ruleDetail(rule);
        detail.put("deleted", "true");
        trace.organization(organizationId);
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_POLICY_RULE_DELETED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
    }

    // -------------------------------------------------------------------------
    // The skip-relaxation knob (the seam's widening path)
    // -------------------------------------------------------------------------

    /** The organization's skip allowances: the named rings a promotion may carry beyond the next. */
    @Transactional(readOnly = true)
    public SkipAllowancesView skipAllowances(UUID actorId, UUID organizationId) {
        requireOrganization(organizationId);
        List<String> allowances = settings.findByOrganizationId(organizationId)
                .map(row -> row.getSkipAllowances() == null ? List.<String>of() : row.getSkipAllowances())
                .orElseGet(List::of);
        return new SkipAllowancesView(organizationId, allowances);
    }

    /**
     * Replaces the skip allowances (the whole set, PUT semantics):
     * every named ring must resolve in the organization's effective
     * vocabulary. Audited with the new set — the knob widens the
     * promotion walk, and the widening is a governed act.
     */
    @Transactional
    public SkipAllowancesView replaceSkipAllowances(UUID actorId, UUID organizationId, List<String> named) {
        requireOrganization(organizationId);
        List<String> validated = validatedSkipAllowances(organizationId, named);
        ReleasePolicySettingsEntity row =
                settings.findByOrganizationId(organizationId).orElse(null);
        if (row == null) {
            row = new ReleasePolicySettingsEntity(organizationId, validated);
        } else {
            row.editSkipAllowances(validated);
        }
        settings.saveAndFlush(row);
        trace.organization(organizationId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("skipAllowances", validated.isEmpty() ? "-" : String.join(",", validated));
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_POLICY_SETTINGS_UPDATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
        return new SkipAllowancesView(organizationId, validated);
    }

    // -------------------------------------------------------------------------
    // The release-addressed surfaces: dry-run, holds, acknowledge, evaluations
    // -------------------------------------------------------------------------

    /**
     * The on-demand dry-run (AC3): the SAME evaluation the promote act
     * runs — the next ring resolved the promotion engine's way (the
     * targets' current ring, one ring forward in the effective order),
     * every scoped rule answered with its outcome and evidence — as a
     * pure read. Nothing is written: no evaluation row, no hold
     * (would-fire rules name what a hold WOULD demand).
     */
    @Transactional(readOnly = true)
    public DryRunView dryRun(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            String toRing = nextRingOf(release);
            ReleasePolicyEngine.EngineResult result =
                    engine.evaluate(release.getId(), release.getOrganizationId(), release.getApplicationId(), toRing);
            List<DryRunView.RuleOutcomeView> outcomes = new ArrayList<>();
            for (ReleasePolicyEngine.RuleOutcome outcome : result.outcomes()) {
                outcomes.add(new DryRunView.RuleOutcomeView(
                        outcome.ruleId(),
                        outcome.ruleName(),
                        outcome.action(),
                        outcome.outcome().name(),
                        outcome.detail(),
                        outcome.holdId()));
            }
            return new DryRunView(
                    release.getId(),
                    release.releaseRef(),
                    toRing,
                    result.verdict().name(),
                    outcomes);
        });
    }

    /** The release's hold ledger: the pending demands first-class, the acknowledged history beside them. */
    @Transactional(readOnly = true)
    public HoldsView holds(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            List<HoldsView.HoldView> rows = new ArrayList<>();
            for (ReleasePolicyHoldEntity hold : this.holds.findByReleaseIdOrderByCreatedAtAscIdAsc(release.getId())) {
                String ruleName = rules.findById(hold.getRuleId())
                        .map(ReleasePolicyRuleEntity::getName)
                        .orElse(null);
                rows.add(new HoldsView.HoldView(
                        hold.getId(),
                        hold.getRuleId(),
                        ruleName,
                        hold.getRing(),
                        hold.getStatus().name(),
                        hold.getEvidence(),
                        hold.getAcknowledgedBy(),
                        hold.getAcknowledgedAt(),
                        hold.getAcknowledgeReason(),
                        hold.getCreatedAt()));
            }
            return new HoldsView(release.getId(), release.releaseRef(), rows);
        });
    }

    /**
     * The explicit privileged acknowledge (AC2): a PENDING hold of the
     * addressed release flips to ACKNOWLEDGED with the actor, the
     * mandatory reason, and the moment — audited (the console's
     * acknowledge button, the gate-override discipline). The decision
     * STANDS: later hops of the same release into the same ring pass
     * the hold, the evaluation log naming every suppressed firing.
     */
    @Transactional
    public HoldsView.HoldView acknowledge(
            UUID actorId, UUID applicationId, UUID releaseId, UUID holdId, String reason) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            if (reason == null || reason.isBlank()) {
                throw new ReleaseException(
                        ReleaseException.Code.INVALID_REQUEST,
                        "the request is invalid",
                        Map.of("reason", "a reason is required to acknowledge a policy hold"));
            }
            ReleasePolicyHoldEntity hold = this.holds.findById(holdId).orElseThrow(ReleasePolicyService::notFound);
            if (!hold.getReleaseId().equals(release.getId())) {
                throw notFound();
            }
            if (hold.getStatus() == Status.ACKNOWLEDGED) {
                throw new ReleaseException(
                        ReleaseException.Code.POLICY_HOLD_ALREADY_ACKNOWLEDGED,
                        "the hold is already acknowledged; its decision stands",
                        Map.of("hold", hold.getId().toString()));
            }
            hold.acknowledge(actorId, reason.trim(), Instant.now(clock));
            this.holds.saveAndFlush(hold);
            String ruleName = rules.findById(hold.getRuleId())
                    .map(ReleasePolicyRuleEntity::getName)
                    .orElse(null);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("rule", ruleName == null ? "-" : ruleName);
            detail.put("ring", hold.getRing());
            detail.put("reason", reason.trim());
            trace.organization(release.getOrganizationId());
            audit.publish(AuditEvent.builder(AuditEventType.RELEASE_POLICY_HOLD_ACKNOWLEDGED)
                    .occurredAt(Instant.now(clock))
                    .userId(actorId)
                    .orgId(release.getOrganizationId())
                    .tenantId(release.getTenantId())
                    .applicationId(release.getApplicationId())
                    .success(true)
                    .detail(detail)
                    .build());
            return new HoldsView.HoldView(
                    hold.getId(),
                    hold.getRuleId(),
                    ruleName,
                    hold.getRing(),
                    hold.getStatus().name(),
                    hold.getEvidence(),
                    hold.getAcknowledgedBy(),
                    hold.getAcknowledgedAt(),
                    hold.getAcknowledgeReason(),
                    hold.getCreatedAt());
        });
    }

    /** The release's evaluation log (AC5): every attempt's verdict and per-rule outcomes, newest first. */
    @Transactional(readOnly = true)
    public EvaluationsView evaluations(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            List<EvaluationsView.EvaluationView> rows = new ArrayList<>();
            for (ReleasePolicyEvaluationEntity evaluation :
                    this.evaluations.findByReleaseIdOrderByOccurredAtDescIdAsc(release.getId())) {
                List<EvaluationsView.EvaluationView.RuleOutcomeView> outcomes = new ArrayList<>();
                for (ReleasePolicyEvaluationRuleEntity outcome :
                        evaluationRules.findByEvaluationIdOrderByRulePositionAscRuleNameAsc(evaluation.getId())) {
                    outcomes.add(new EvaluationsView.EvaluationView.RuleOutcomeView(
                            outcome.getRuleId(),
                            outcome.getRuleName(),
                            outcome.getAction(),
                            outcome.getOutcome(),
                            outcome.getDetail(),
                            outcome.getRulePosition()));
                }
                rows.add(new EvaluationsView.EvaluationView(
                        evaluation.getId(),
                        evaluation.getRing(),
                        evaluation.getTrigger().name(),
                        evaluation.getVerdict().name(),
                        evaluation.getWinningRuleId(),
                        evaluation.getActorId(),
                        evaluation.getOccurredAt(),
                        outcomes));
            }
            return new EvaluationsView(release.getId(), release.releaseRef(), rows);
        });
    }

    // -------------------------------------------------------------------------
    // Validation (AC6): shape and references, refused before any write
    // -------------------------------------------------------------------------

    /** One validated rule's facts (the create/update surfaces' shared authority). */
    private record Validated(
            String name,
            Scope scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            Action action,
            Map<String, Object> condition,
            int position,
            boolean enabled) {}

    private Validated validated(UUID organizationId, CreateCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = command.name() == null ? "" : command.name().trim().toLowerCase(Locale.ROOT);
        if (!name.matches(NAME_PATTERN)) {
            errors.put("name", "3-64 chars, lowercase letters/digits/hyphens, starting with a letter");
        }
        Scope scope = parseEnum(Scope.class, command.scope(), "scope", errors);
        Action action = parseEnum(Action.class, command.action(), "action", errors);
        UUID applicationId = null;
        String ring = null;
        UUID environmentId = null;
        if (scope != null) {
            switch (scope) {
                case ORGANIZATION -> {
                    if (command.applicationId() != null || command.ring() != null || command.environmentId() != null) {
                        errors.put("scope", "an ORGANIZATION rule carries no application, ring, or environment");
                    }
                }
                case APPLICATION -> {
                    applicationId = command.applicationId();
                    if (applicationId == null) {
                        errors.put("applicationId", "an APPLICATION rule names its application");
                    } else {
                        ApplicationAccess.ApplicationProfile profile =
                                applications.profileOf(applicationId).orElse(null);
                        if (profile == null || !profile.organizationId().equals(organizationId)) {
                            errors.put("applicationId", "unknown application of this organization");
                        }
                    }
                    if (command.ring() != null || command.environmentId() != null) {
                        errors.put("scope", "an APPLICATION rule carries no ring or environment");
                    }
                }
                case RING -> {
                    if (command.ring() == null || command.ring().isBlank()) {
                        errors.put("ring", "a RING rule names the ring it governs entry to");
                    } else {
                        String named = command.ring().trim().toLowerCase(Locale.ROOT);
                        if (rings.byCode(named, organizationId).isEmpty()) {
                            errors.put("ring", "unknown ring code '" + named + "' for this organization");
                        } else {
                            ring = named;
                        }
                    }
                    if (command.applicationId() != null || command.environmentId() != null) {
                        errors.put("scope", "a RING rule carries no application or environment");
                    }
                }
                case ENVIRONMENT -> {
                    environmentId = command.environmentId();
                    if (environmentId == null) {
                        errors.put("environmentId", "an ENVIRONMENT rule names its environment");
                    } else if (!organizationId.equals(
                            environments.owningOrganizationOf(environmentId).orElse(null))) {
                        errors.put("environmentId", "unknown environment of this organization");
                    }
                    if (command.applicationId() != null || command.ring() != null) {
                        errors.put("scope", "an ENVIRONMENT rule carries no application or ring");
                    }
                }
            }
        }
        List<String> conditionErrors = new ArrayList<>();
        PolicyCondition condition = command.condition() == null
                ? null
                : PolicyCondition.parse(command.condition(), "condition", conditionErrors);
        if (command.condition() == null) {
            conditionErrors.add("condition: a condition is required");
        }
        errors.putAll(indexed(conditionErrors));
        if (condition != null && PolicyCondition.nodeCountOf(condition) > PolicyCondition.MAX_NODES) {
            errors.put("condition", "at most " + PolicyCondition.MAX_NODES + " condition nodes are allowed");
        }
        if (condition != null) {
            for (String reference : referencedGateTypes(condition)) {
                if (!gateTypeResolvable(organizationId, reference)) {
                    errors.put(
                            "condition",
                            "gate type '" + reference + "' is neither a catalog kind nor defined by this organization");
                }
            }
        }
        Integer position = command.position();
        if (position == null || position < 0 || position > 999) {
            errors.put("position", "0-999 (the evaluation order)");
        }
        if (!errors.isEmpty()) {
            throw new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "the request is invalid", errors);
        }
        return new Validated(
                name,
                scope,
                applicationId,
                ring,
                environmentId,
                action,
                command.condition(),
                position,
                command.enabled() == null || command.enabled());
    }

    private List<String> validatedSkipAllowances(UUID organizationId, List<String> named) {
        if (named == null) {
            return List.of();
        }
        Map<String, String> errors = new LinkedHashMap<>();
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        for (String raw : named) {
            if (raw == null || raw.isBlank()) {
                errors.put("rings", "ring codes must be non-blank");
                continue;
            }
            String code = raw.trim().toLowerCase(Locale.ROOT);
            if (rings.byCode(code, organizationId).isEmpty()) {
                errors.put("rings", "unknown ring code '" + code + "' for this organization");
            } else {
                canonical.add(code);
            }
        }
        if (canonical.size() > 16) {
            errors.put("rings", "at most 16 skip allowances are allowed");
        }
        if (!errors.isEmpty()) {
            throw new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "the request is invalid", errors);
        }
        return List.copyOf(canonical);
    }

    /** The gate types a condition tree references (the write-time resolvability check, AC6). */
    private static List<String> referencedGateTypes(PolicyCondition condition) {
        List<String> types = new ArrayList<>();
        collectGateTypes(condition, types);
        return types;
    }

    private static void collectGateTypes(PolicyCondition condition, List<String> types) {
        switch (condition) {
            case PolicyCondition.AllOf all -> all.children().forEach(child -> collectGateTypes(child, types));
            case PolicyCondition.AnyOf any -> any.children().forEach(child -> collectGateTypes(child, types));
            case PolicyCondition.GateCondition gate -> types.add(gate.gateType());
            default -> {}
        }
    }

    private boolean gateTypeResolvable(UUID organizationId, String gateType) {
        if (com.shipperdog.api.gate.GateCatalog.builtinOf(gateType).isPresent()) {
            return true;
        }
        return gateDefinitions.definitionsOf(organizationId).stream()
                .anyMatch(fact -> fact.gateType().equals(gateType));
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
            throw new ReleaseException(
                    ReleaseException.Code.INVALID_REQUEST,
                    "the request is invalid",
                    Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(organizationId)) {
            throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "organization not found");
        }
    }

    private void auditRule(
            UUID actorId,
            UUID organizationId,
            AuditEventType type,
            ReleasePolicyRuleEntity rule,
            Map<String, String> changed) {
        Map<String, String> detail = ruleDetail(rule);
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

    private static Map<String, String> ruleDetail(ReleasePolicyRuleEntity rule) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", rule.getName());
        detail.put("scope", rule.getScope().name());
        detail.put("action", rule.getAction().name());
        detail.put("condition", conditionSummary(rule.getCondition()));
        detail.put("position", String.valueOf(rule.getPosition()));
        detail.put("enabled", String.valueOf(rule.isEnabled()));
        return detail;
    }

    private static String conditionSummary(Map<String, Object> condition) {
        PolicyCondition parsed = PolicyCondition.parse(condition, "condition", new ArrayList<>());
        return parsed == null ? "unreadable" : parsed.describe();
    }

    private RuleView viewOf(ReleasePolicyRuleEntity rule) {
        return new RuleView(
                rule.getId(),
                rule.getOrganizationId(),
                rule.getName(),
                rule.getScope().name(),
                rule.getApplicationId(),
                rule.getRing(),
                rule.getEnvironmentId(),
                rule.getAction().name(),
                rule.getCondition(),
                conditionSummary(rule.getCondition()),
                rule.getPosition(),
                rule.isEnabled(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }

    /**
     * The next ring the promotion engine would walk the release's
     * targets into: the move set's uniform current ring, one step
     * forward in the effective order — the promote act's own hop
     * arithmetic, answered as a read (a staggered set and the final
     * ring answer the promote act's own refusals; any lifecycle
     * status may dry-run — the answer says what WOULD fire).
     */
    private String nextRingOf(ReleaseEntity release) {
        List<ReleaseTargetEntity> targetRows = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        if (targetRows.isEmpty()) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_PROMOTION_RING_FINAL, "the targets already sit on the final ring");
        }
        LinkedHashSet<String> effectiveCodes = new LinkedHashSet<>();
        for (ReleaseTargetEntity target : targetRows) {
            effectiveCodes.add(effectiveCodeOf(release, target.getRingId()));
        }
        if (effectiveCodes.size() > 1) {
            Map<String, String> byTenant = new LinkedHashMap<>();
            for (ReleaseTargetEntity target : targetRows) {
                byTenant.put(target.getTenantId().toString(), effectiveCodeOf(release, target.getRingId()));
            }
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_PROMOTION_RING_SPLIT,
                    "the promotion's targets sit on more than one ring; promote them per ring",
                    byTenant);
        }
        String fromCode = effectiveCodes.iterator().next();
        List<RingEntity> order = ringService.effectiveVocabularyOf(release.getOrganizationId());
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getCode().equals(fromCode)) {
                if (i == order.size() - 1) {
                    throw new ReleaseException(
                            ReleaseException.Code.RELEASE_PROMOTION_RING_FINAL,
                            "the targets already sit on the final ring",
                            Map.of("ring", fromCode));
                }
                return order.get(i + 1).getCode();
            }
        }
        throw new ReleaseException(
                ReleaseException.Code.RELEASE_PROMOTION_RING_SPLIT,
                "the promotion's targets sit on more than one ring; promote them per ring");
    }

    private String effectiveCodeOf(ReleaseEntity release, UUID ringId) {
        RingEntity referenced = rings.findById(ringId).orElse(null);
        if (referenced == null) {
            return "?" + ringId;
        }
        return rings.byCode(referenced.getCode(), release.getOrganizationId())
                .map(RingEntity::getCode)
                .orElse(referenced.getCode());
    }

    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    private ReleaseEntity scopedReleaseOf(UUID applicationId, UUID releaseId) {
        ReleaseEntity release = releases.findScoped(releaseId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
        if (!release.getApplicationId().equals(applicationId)) {
            throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
        }
        return release;
    }

    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private static ReleaseException notFound() {
        return new ReleaseException(ReleaseException.Code.NOT_FOUND, "resource not found");
    }

    private static ReleaseException nameTaken() {
        return new ReleaseException(
                ReleaseException.Code.POLICY_RULE_NAME_TAKEN,
                "a rule with this name already exists for the organization");
    }

    // -------------------------------------------------------------------------
    // Commands and views
    // -------------------------------------------------------------------------

    /** One rule's create/replace command: the rule's meaning end to end. */
    public record CreateCommand(
            UUID organizationId,
            String name,
            String scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            String action,
            Map<String, Object> condition,
            Integer position,
            Boolean enabled) {}

    /** One rule read back, with the condition and its human summary. */
    public record RuleView(
            UUID id,
            UUID organizationId,
            String name,
            String scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            String action,
            Map<String, Object> condition,
            String conditionSummary,
            int position,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {}

    /** The skip-relaxation standing of one organization. */
    public record SkipAllowancesView(UUID organizationId, List<String> rings) {}

    /** The dry-run answer (AC3): the target ring, the verdict, every scoped rule's outcome — nothing written. */
    public record DryRunView(
            UUID releaseId, String releaseRef, String ring, String verdict, List<RuleOutcomeView> rules) {

        /** One rule's would-fire standing with its evidence. */
        public record RuleOutcomeView(
                UUID ruleId, String name, String action, String outcome, String detail, UUID holdId) {}
    }

    /** The release's hold ledger: every demand, pending and acknowledged. */
    public record HoldsView(UUID releaseId, String releaseRef, List<HoldView> holds) {

        /** One hold: the rule, the ring it guards, the standing, the acknowledge record. */
        public record HoldView(
                UUID id,
                UUID ruleId,
                String ruleName,
                String ring,
                String status,
                Map<String, Object> evidence,
                UUID acknowledgedBy,
                Instant acknowledgedAt,
                String acknowledgeReason,
                Instant createdAt) {}
    }

    /** The release's evaluation log (AC5): every attempt, newest first. */
    public record EvaluationsView(UUID releaseId, String releaseRef, List<EvaluationView> evaluations) {

        /** One attempt's record: the ring, the verdict, the winning rule, the per-rule outcomes. */
        public record EvaluationView(
                UUID id,
                String ring,
                String trigger,
                String verdict,
                UUID winningRuleId,
                UUID actorId,
                Instant occurredAt,
                List<RuleOutcomeView> rules) {

            /** One logged rule outcome, in the order it was evaluated. */
            public record RuleOutcomeView(
                    UUID ruleId, String ruleName, String action, String outcome, String detail, int position) {}
        }
    }
}
