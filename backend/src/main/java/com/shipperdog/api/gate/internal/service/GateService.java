package com.shipperdog.api.gate.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.gate.GateCatalog;
import com.shipperdog.api.gate.GateCatalog.Metric;
import com.shipperdog.api.gate.GateCatalog.ResultSchema;
import com.shipperdog.api.gate.GateException;
import com.shipperdog.api.gate.GateMetricSource;
import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.gate.internal.entity.GateDefinitionEntity;
import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity;
import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity.Source;
import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity.Status;
import com.shipperdog.api.gate.internal.entity.ReleaseGateResultEntity;
import com.shipperdog.api.gate.internal.repository.GateDefinitionRepository;
import com.shipperdog.api.gate.internal.repository.ReleaseGateRepository;
import com.shipperdog.api.gate.internal.repository.ReleaseGateResultRepository;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gate module's core (issue #42, S10-03, FR-026, PRD 32/33): the
 * organization's required-gate DEFINITIONS (gates as data — a type of
 * the open vocabulary, an expected-result config payload, an optional
 * PRD 33 ring attachment), the evidence-backed RESULT records external
 * systems submit per release ("latest wins" on the current state, an
 * append-only history behind it), the audited OVERRIDE of a blocking
 * gate, and the EVALUATION both the state machine and S10-05's
 * promotion consult — every required gate of the evaluated ring passed
 * (or overridden) is the gate point's condition; anything else blocks,
 * fail-closed on missing analytics data by design.
 *
 * <p>Gates are EVALUATED FACTS, not workflows (the issue's technical
 * note): this module records results and answers completeness; the
 * DECISION lives in the crossing (the {@code ReleaseGatePolicy}
 * registrant denies VALIDATING to READY while the evaluation blocks)
 * and in promotion (S10-05 consults the {@link ReleaseGateEvaluation}
 * port for the target ring). Metric-backed gates
 * ({@code crash-free-threshold}, {@code open-critical-bugs}) refuse
 * manual submission — their values resolve from analytics at
 * evaluation time through the {@link GateMetricSource} seam, which no
 * story registers until S11-04; until then they evaluate NO_DATA,
 * which BLOCKS (fail-closed, the PRD 33 ethos) but renders distinctly
 * from FAILED (no data yet is not a bad result).
 *
 * <p>Every release-addressed route is body/query-addressed on the
 * application (the release surface's own permission-target shape), and
 * the service resolves the release through the sanctioned seam before
 * any read or write of gate rows — a foreign release is the standard
 * concealed miss. Definition routes are organization-grain reference
 * data (the {@code approval_workflows} standing).
 */
@Service
public class GateService implements ReleaseGateEvaluation {

    /** Newest last: the catalog's type order is the display order (deterministic listing). */
    private static final Sort TYPE_ORDER =
            Sort.by(Sort.Direction.ASC, "gateType").and(Sort.by(Sort.Direction.ASC, "id"));

    /** The reason cap (V31's database check pins the same width). */
    private static final int MAX_REASON_LENGTH = 1000;

    private final GateDefinitionRepository definitions;
    private final ReleaseGateRepository gates;
    private final ReleaseGateResultRepository results;
    private final OrganizationAccess organizations;
    private final ReleaseAccess releases;
    private final ApplicationAccess applications;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;
    private final ObjectProvider<GateMetricSource> metricSources;
    private final Clock clock;
    private final TraceAttributes trace;

    GateService(
            GateDefinitionRepository definitions,
            ReleaseGateRepository gates,
            ReleaseGateResultRepository results,
            OrganizationAccess organizations,
            ReleaseAccess releases,
            ApplicationAccess applications,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            ObjectProvider<GateMetricSource> metricSources,
            Clock clock,
            TraceAttributes trace) {
        this.definitions = definitions;
        this.gates = gates;
        this.results = results;
        this.organizations = organizations;
        this.releases = releases;
        this.applications = applications;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.metricSources = metricSources;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Definitions (AC1): the FR-026 catalog as organizational data
    // -------------------------------------------------------------------------

    /**
     * Creates a gate definition (AC1): a gate type of the open
     * vocabulary (normalized to the canonical lowercase kebab; the
     * built-in catalog's types carry their documented expected-result
     * schemas, integration-defined types default to binary reporting),
     * the required flag (a non-required definition records results for
     * visibility without ever blocking), the config payload validated
     * against the catalog's schema for the type (a threshold for the
     * numeric-min kinds, a ceiling for the count-max kind, nothing for
     * the pass/fail kinds), and the optional ring attachment — the
     * PRD 33 rule that each ring carries its own gates. One definition
     * per organization and gate type at each ring level (the precise
     * 409); audited with the whole definition. Honors
     * {@code Idempotency-Key}.
     */
    @Transactional
    public DefinitionView create(UUID actorId, CreateCommand command) {
        if (command.organizationId() == null) {
            throw invalid(Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(command.organizationId())) {
            throw new GateException(GateException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        String gateType = validatedType(command.gateType());
        boolean required = command.required() == null || command.required();
        Map<String, Object> config = validatedConfig(gateType, command.config());
        UUID ringId = ringIdOf(command.organizationId(), command.ring());
        if (definitionExists(command.organizationId(), gateType, ringId)) {
            throw definitionExists();
        }

        GateDefinitionEntity definition;
        try {
            definition = definitions.saveAndFlush(
                    new GateDefinitionEntity(command.organizationId(), gateType, required, config, ringId, actorId));
        } catch (DataIntegrityViolationException e) {
            // Only the duplicate authority translates (the workflow precedent).
            if (e.getMessage() != null && e.getMessage().contains("uq_gate_definitions")) {
                throw definitionExists();
            }
            throw e;
        }

        trace.organization(command.organizationId());
        audit.publish(AuditEvent.builder(AuditEventType.GATE_DEFINITION_CREATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(command.organizationId())
                .success(true)
                .detail(definitionDetail(gateType, required, config, command.ring()))
                .build());
        return viewOf(definition);
    }

    /**
     * Edits a gate definition (the governance surface): replaces the
     * required flag, the config payload, and the ring attachment (a
     * null ring DETACHES to the organization-wide standing). The gate
     * TYPE never moves — it is the identity release rows key on and
     * the catalog's schema resolves by; a changed type is a new
     * definition. Results already recorded keep their evidence (the
     * history is append-only); the next evaluation resolves the
     * definition's CURRENT content. Audited. Honors
     * {@code Idempotency-Key}.
     */
    @Transactional
    public DefinitionView update(UUID actorId, UUID organizationId, UUID definitionId, UpdateCommand command) {
        if (organizationId == null) {
            throw invalid(Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(organizationId)) {
            throw new GateException(GateException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        GateDefinitionEntity definition = definitions
                .findByIdAndOrganizationId(definitionId, organizationId)
                .orElseThrow(() -> new GateException(GateException.Code.NOT_FOUND, "gate definition not found"));
        boolean required = command.required() == null || command.required();
        Map<String, Object> config = validatedConfig(definition.getGateType(), command.config());
        UUID ringId = ringIdOf(organizationId, command.ring());
        if (!ringAttachmentEqual(definition, ringId)
                && definitionExists(organizationId, definition.getGateType(), ringId)) {
            throw definitionExists();
        }
        definition.edit(required, config, ringId);
        definitions.flush();

        trace.organization(organizationId);
        audit.publish(AuditEvent.builder(AuditEventType.GATE_DEFINITION_UPDATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(definitionDetail(definition.getGateType(), required, config, command.ring()))
                .build());
        return viewOf(definition);
    }

    /**
     * The definition listing: the organization's whole catalog (the
     * permission target and the scope), optionally narrowed to one
     * ring's attachment ({@code ring} names the ring code), type
     * order, standard limit/cursor pagination. The ring filter
     * composes over the whole catalog before the page (an
     * organization's gate vocabulary is reference data — small by
     * nature — so the filtered count stays exact).
     */
    @Transactional(readOnly = true)
    public Page<DefinitionView> list(UUID actorId, UUID organizationId, String ring, long offset, int limit) {
        if (!organizations.organizationExists(organizationId)) {
            throw new GateException(GateException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        UUID ringId = ring == null || ring.isBlank() ? null : mustResolveRing(organizationId, ring);
        List<GateDefinitionEntity> catalog = new ArrayList<>(definitions.findAllByOrganizationId(organizationId));
        catalog.sort(java.util.Comparator.comparing(GateDefinitionEntity::getGateType)
                .thenComparing(GateDefinitionEntity::getId));
        List<DefinitionView> filtered = catalog.stream()
                .filter(definition -> ringId == null || ringId.equals(definition.getRingId()))
                .map(this::viewOf)
                .toList();
        int from = (int) Math.min(offset, filtered.size());
        int to = (int) Math.min(offset + limit, filtered.size());
        return new org.springframework.data.domain.PageImpl<>(
                filtered.subList(from, to), new CursorPageable(offset, limit, TYPE_ORDER), filtered.size());
    }

    /** The definition detail: the definition read back; a foreign definition conceals exactly like an unknown id. */
    @Transactional(readOnly = true)
    public DefinitionView get(UUID actorId, UUID organizationId, UUID definitionId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new GateException(GateException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        return viewOf(definitions
                .findByIdAndOrganizationId(definitionId, organizationId)
                .orElseThrow(() -> new GateException(GateException.Code.NOT_FOUND, "gate definition not found")));
    }

    // -------------------------------------------------------------------------
    // Result ingestion (AC2): the external CI submission surface
    // -------------------------------------------------------------------------

    /**
     * Records one gate result (AC2, FR-022's "external systems may
     * submit"): pass or fail, the evidence URL, the submitter, and —
     * for a catalog numeric kind — the observed value the dashboard
     * renders beside the definition's threshold. Idempotent per gate
     * and release as LATEST WINS: the current state mirrors the newest
     * result and the append-only history keeps every report (a re-run
     * gate never rewrites what was reported before). Metric-backed
     * types refuse submission (422) — their values resolve from
     * analytics at evaluation time, fail-closed until S11-04. Audited
     * as {@code RELEASE_GATE_RESULT_RECORDED} with the outcome (the
     * pass/fail event AC6 names). Not idempotency-keyed: the recorded
     * state is the replay answer.
     */
    @Transactional
    public GateView submit(
            UUID actorId, UUID applicationId, UUID releaseId, String gateTypePath, ResultCommand command) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            String gateType = GateCatalog.normalize(gateTypePath);
            if (!GateCatalog.wellFormed(gateType)) {
                throw invalid(Map.of("gate", "gate type must be lowercase kebab (letters, digits, dashes)"));
            }
            if (GateCatalog.metricBacked(gateType)) {
                throw new GateException(
                        GateException.Code.GATE_METRIC_BACKED,
                        "gate '" + gateType + "' resolves its value from analytics at evaluation time",
                        Map.of("gate", "metric-backed gates are not manually submittable; await the evaluation"));
            }
            Status outcome = parseOutcome(command.status());
            String evidenceUrl = validatedEvidence(command.evidenceUrl());
            Double observedValue = validatedValue(gateType, command.observedValue());

            ReleaseGateEntity gate = upsertGate(facts, gateType, false);
            Instant occurredAt = Instant.now(clock);
            gate.record(outcome, evidenceUrl, observedValue, actorId, Source.SUBMITTED, occurredAt);
            gates.saveAndFlush(gate);
            results.save(new ReleaseGateResultEntity(
                    releaseId, gateType, outcome, evidenceUrl, observedValue, actorId, Source.SUBMITTED, occurredAt));

            trace.application(applicationId);
            trace.release(releaseId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("releaseRef", facts.releaseRef());
            detail.put("gateType", gateType);
            detail.put("outcome", outcome.name());
            detail.put("evidenceUrl", evidenceUrl);
            detail.put("source", Source.SUBMITTED.name());
            if (observedValue != null) {
                detail.put("observedValue", String.valueOf(observedValue));
            }
            audit.publish(AuditEvent.builder(AuditEventType.RELEASE_GATE_RESULT_RECORDED)
                    .occurredAt(occurredAt)
                    .userId(actorId)
                    .orgId(facts.organizationId())
                    .tenantId(facts.tenantId())
                    .applicationId(applicationId)
                    .success(true)
                    .detail(detail)
                    .build());
            return gateView(facts, gate, resolvedDefinitionOf(facts, gateType));
        });
    }

    // -------------------------------------------------------------------------
    // The override path (AC3): privileged, reasoned, audited
    // -------------------------------------------------------------------------

    /**
     * Overrides a blocking gate (AC3): only a FAILED or NO_DATA gate,
     * exactly once — a passed gate needs no override, a pending one
     * has nothing to override. The reason is mandatory (the operator
     * explains the override; it renders on the dashboard beside the
     * actor). The override never rewrites the underlying fact: the row
     * keeps reporting FAILED/NO_DATA with the override record beside
     * it, and evaluation stops counting the gate as blocking. Audited
     * as {@code RELEASE_GATE_OVERRIDDEN}.
     */
    @Transactional
    public GateView override(UUID actorId, UUID applicationId, UUID releaseId, String gateTypePath, String reason) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            String gateType = GateCatalog.normalize(gateTypePath);
            String validatedReason = mandatoryReason(reason);
            ReleaseGateEntity gate = gates.findByReleaseIdAndGateType(releaseId, gateType)
                    .orElseThrow(() -> new GateException(
                            GateException.Code.GATE_OVERRIDE_INVALID,
                            "no recorded gate '" + gateType + "' to override",
                            Map.of("gate", "nothing has been reported for this gate yet")));
            if (gate.getStatus() != Status.FAILED && gate.getStatus() != Status.NO_DATA) {
                throw new GateException(
                        GateException.Code.GATE_OVERRIDE_INVALID,
                        "gate '" + gateType + "' is " + gate.getStatus()
                                + "; only a FAILED or NO_DATA gate can be overridden",
                        Map.of("state", gate.getStatus().name()));
            }
            if (gate.isOverridden()) {
                throw new GateException(
                        GateException.Code.GATE_OVERRIDE_INVALID,
                        "gate '" + gateType
                                + "' is already overridden; an override is a recorded fact that never moves",
                        Map.of("state", gate.getStatus().name()));
            }
            Instant occurredAt = Instant.now(clock);
            gate.override(actorId, validatedReason, occurredAt);
            gates.saveAndFlush(gate);

            trace.application(applicationId);
            trace.release(releaseId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("releaseRef", facts.releaseRef());
            detail.put("gateType", gateType);
            detail.put("state", gate.getStatus().name());
            detail.put("reason", validatedReason);
            audit.publish(AuditEvent.builder(AuditEventType.RELEASE_GATE_OVERRIDDEN)
                    .occurredAt(occurredAt)
                    .userId(actorId)
                    .orgId(facts.organizationId())
                    .tenantId(facts.tenantId())
                    .applicationId(applicationId)
                    .success(true)
                    .detail(detail)
                    .build());
            return gateView(facts, gate, resolvedDefinitionOf(facts, gateType));
        });
    }

    // -------------------------------------------------------------------------
    // The gate read (AC5's feed): the dashboard's §32 block
    // -------------------------------------------------------------------------

    /**
     * The release's gate state — the dashboard's Quality Gates block:
     * every gate the release's ring expects (resolved live from the
     * definitions, so a definition edit reflects immediately) beside
     * every recorded result of gates outside the expected set (the
     * open vocabulary's integration-reported kinds), each with its
     * current state, observed value against the definition's
     * threshold, evidence link, override record, and full submission
     * history (newest first). No rows are written: the read resolves
     * the expected set from definitions and joins the recorded rows.
     */
    @Transactional(readOnly = true)
    public GatesView gates(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = factsOf(applicationId, releaseId);
            return gatesViewOf(facts);
        });
    }

    // -------------------------------------------------------------------------
    // ReleaseGateEvaluation: the promotion precheck port (S10-05) and
    // the transition policy's answer
    // -------------------------------------------------------------------------

    /**
     * The evaluation (AC3/AC4): resolves the target ring's required
     * gate set ({@code ringCode} names the promotion target; absent
     * evaluates the release's current ring — the state machine's own
     * consult), instantiates the expected rows (PENDING — the
     * dashboard's "awaiting result" state), resolves metric-backed
     * gates from analytics NOW (no registrant or no data records
     * NO_DATA — fail-closed by design), and answers every gate's
     * state with the blocking verdict: a required gate blocks while
     * PENDING, FAILED, or NO_DATA and not overridden. An unknown
     * release or ring answers empty — the caller renders its own miss.
     */
    @Override
    @Transactional
    public Optional<Evaluation> evaluate(UUID releaseId, String ringCode) {
        ReleaseFacts facts = releases.factsOf(releaseId).orElse(null);
        if (facts == null) {
            return Optional.empty();
        }
        UUID ringId;
        String ring;
        if (ringCode == null || ringCode.isBlank()) {
            ringId = facts.ringId();
            ring = facts.ringCode();
        } else {
            String normalized = ringCode.trim().toLowerCase(Locale.ROOT);
            ringId = releases.ringIdOf(facts.organizationId(), normalized).orElse(null);
            ring = normalized;
        }
        if (ringId == null || ring == null) {
            return Optional.empty();
        }
        Map<String, GateDefinitionEntity> resolved = resolvedDefinitions(facts.organizationId(), ringId);
        Map<String, String> denials = new LinkedHashMap<>();
        List<ReleaseGateEvaluation.Evaluation.GateState> states = new ArrayList<>();
        for (Map.Entry<String, GateDefinitionEntity> expected : resolved.entrySet()) {
            String gateType = expected.getKey();
            GateDefinitionEntity definition = expected.getValue();
            ReleaseGateEntity gate = upsertGate(facts, gateType, definition.getRequired());
            if (definition.getRequired() && GateCatalog.metricBacked(gateType)) {
                resolveMetric(facts, gate, definition);
            }
            boolean required = definition.getRequired();
            boolean overridden = gate.isOverridden();
            boolean blocking = required && !overridden && gate.getStatus() != Status.PASSED;
            if (blocking) {
                denials.put(gateType, denialOf(gate));
            }
            states.add(gateStateOf(gate, definition, overridden));
        }
        return Optional.of(new ReleaseGateEvaluation.Evaluation(
                facts.releaseId(),
                facts.releaseRef(),
                ring,
                List.copyOf(states),
                !denials.isEmpty(),
                Map.copyOf(denials)));
    }

    /**
     * The VALIDATING crossing's instantiation hook (the
     * {@code ApprovalRoundListener} precedent): the moment the
     * validation phase opens, the release's ring's expected gate rows
     * come into existence — PENDING for the externally-reported kinds
     * (the dashboard's "awaiting result" state from the crossing that
     * owns it, COMMITTED with the crossing) and the metric-backed
     * required kinds resolved immediately (NO_DATA until analytics
     * answers — the fail-closed fact recorded, audited, and visible
     * from the crossing on, never dependent on a later consult that a
     * refused crossing would roll back). Definition sets added after
     * the crossing instantiate at the first evaluation consult; the
     * read derives their expected states live.
     */
    @Transactional
    public void instantiateFromCrossing(ReleaseTransitionedEvent event) {
        if (!"VALIDATING".equals(event.toStatus())) {
            return;
        }
        ReleaseFacts facts = releases.factsOf(event.releaseId()).orElse(null);
        if (facts == null || facts.ringId() == null) {
            return;
        }
        for (Map.Entry<String, GateDefinitionEntity> expected :
                resolvedDefinitions(facts.organizationId(), facts.ringId()).entrySet()) {
            ReleaseGateEntity gate =
                    upsertGate(facts, expected.getKey(), expected.getValue().getRequired());
            if (expected.getValue().getRequired() && GateCatalog.metricBacked(expected.getKey())) {
                resolveMetric(facts, gate, expected.getValue());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The definition view: the catalog entry's facts beside the row's. */
    public record DefinitionView(
            UUID id,
            UUID organizationId,
            String gateType,
            boolean required,
            Map<String, Object> config,
            String schema,
            boolean metricBacked,
            String ring,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** The gates view: the release summary beside every gate state and its history. */
    public record GatesView(UUID releaseId, String releaseRef, String status, String ring, List<GateView> gates) {}

    /**
     * One gate of the dashboard's block: the expected/attached facts
     * beside the recorded state — {@code status} is null while nothing
     * has been reported or instantiated (the honest "not reported
     * yet"), the threshold is the definition's expected result when
     * the gate is attached (the "Coverage 87% (threshold 80%)"
     * render), and the history is the append-only submission trail,
     * newest first.
     */
    public record GateView(
            String gateType,
            boolean required,
            boolean attached,
            String schema,
            boolean metricBacked,
            String status,
            Double observedValue,
            Double threshold,
            String evidenceUrl,
            String source,
            UUID submittedBy,
            Instant evaluatedAt,
            boolean overridden,
            UUID overriddenBy,
            String overriddenReason,
            Instant overriddenAt,
            List<ResultView> history) {}

    /** One recorded result of the history: the append-only trail behind "latest wins". */
    public record ResultView(
            String outcome,
            Double observedValue,
            String evidenceUrl,
            UUID submittedBy,
            String source,
            Instant recordedAt) {}

    /** The definition creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(
            UUID organizationId, String gateType, Boolean required, Map<String, Object> config, String ring) {}

    /** The definition edit command: the mutable facts only (the type is the identity). */
    public record UpdateCommand(Boolean required, Map<String, Object> config, String ring) {}

    /** The result submission command: the externally-reported outcome with its evidence. */
    public record ResultCommand(String status, String evidenceUrl, Double observedValue) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, validation, scope, audit
    // -------------------------------------------------------------------------

    /** The release facts through the seam, verified against the addressed application (the concealed miss). */
    private ReleaseFacts factsOf(UUID applicationId, UUID releaseId) {
        ReleaseFacts facts = releases.factsOf(releaseId)
                .orElseThrow(() -> new GateException(GateException.Code.RELEASE_NOT_FOUND, "release not found"));
        if (!facts.applicationId().equals(applicationId)) {
            throw new GateException(GateException.Code.RELEASE_NOT_FOUND, "release not found");
        }
        return facts;
    }

    /** The application profile through the public port (tuples only, never the row): the concealed-miss backstop. */
    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() -> new GateException(GateException.Code.RELEASE_NOT_FOUND, "application not found"));
    }

    /** Establishes the application's owning scope and runs the work inside it (the release surface's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    /** The canonical gate type: normalized and shape-checked (one vocabulary for definitions, rows, and the catalog). */
    private static String validatedType(String gateType) {
        String normalized = GateCatalog.normalize(gateType);
        if (!GateCatalog.wellFormed(normalized)) {
            throw invalid(Map.of("gateType", "gate type is required, lowercase kebab (letters, digits, dashes)"));
        }
        return normalized;
    }

    /**
     * The expected-result config against the catalog's schema for the
     * type (AC1): a numeric-min kind carries exactly its
     * {@code threshold} (0-100), the count-max kind exactly its
     * {@code max} (a non-negative number, 0 the typical ceiling), and
     * a pass/fail kind carries nothing. Integration-defined types
     * (the open vocabulary) validate as pass/fail — no config.
     */
    private static Map<String, Object> validatedConfig(String gateType, Map<String, Object> config) {
        ResultSchema schema = GateCatalog.schemaOf(gateType);
        Map<String, Object> payload = config == null ? Map.of() : config;
        if (schema == ResultSchema.PASS_FAIL) {
            if (!payload.isEmpty()) {
                throw invalid(Map.of("config", "pass/fail gates carry no config"));
            }
            return Map.of();
        }
        String key = schema == ResultSchema.NUMERIC_MIN ? "threshold" : "max";
        Object value = payload.get(key);
        if (payload.size() != 1 || value == null) {
            throw invalid(Map.of("config", "the '" + key + "' value is required and is the config's only member"));
        }
        double parsed;
        try {
            parsed = Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw invalid(Map.of("config", "'" + key + "' must be a number"));
        }
        if (schema == ResultSchema.NUMERIC_MIN && (parsed < 0 || parsed > 100)) {
            throw invalid(Map.of("config", "'threshold' is a percentage between 0 and 100"));
        }
        if (schema == ResultSchema.COUNT_MAX && (parsed < 0 || parsed != Math.floor(parsed))) {
            throw invalid(Map.of("config", "'max' is a non-negative integer count"));
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put(key, parsed);
        return canonical;
    }

    /** The threshold/max a definition carries, as a double (the render beside an observed value). */
    private static Double thresholdOf(GateDefinitionEntity definition) {
        if (definition == null) {
            return null;
        }
        Object value = definition.getConfig().get("threshold");
        if (value == null) {
            value = definition.getConfig().get("max");
        }
        return value == null ? null : Double.parseDouble(String.valueOf(value));
    }

    /** The ring attachment resolved in the organization's vocabulary (the creation surface's own rule). */
    private UUID ringIdOf(UUID organizationId, String ring) {
        if (ring == null || ring.isBlank()) {
            return null;
        }
        return mustResolveRing(organizationId, ring);
    }

    private UUID mustResolveRing(UUID organizationId, String ring) {
        String normalized = ring.trim().toLowerCase(Locale.ROOT);
        return releases.ringIdOf(organizationId, normalized)
                .orElseThrow(() -> invalid(Map.of("ring", "unknown ring code '" + normalized + "'")));
    }

    private boolean definitionExists(UUID organizationId, String gateType, UUID ringId) {
        return ringId == null
                ? definitions
                        .findByOrganizationIdAndGateTypeAndRingIdIsNull(organizationId, gateType)
                        .isPresent()
                : definitions
                        .findByOrganizationIdAndGateTypeAndRingId(organizationId, gateType, ringId)
                        .isPresent();
    }

    private static boolean ringAttachmentEqual(GateDefinitionEntity definition, UUID ringId) {
        return java.util.Objects.equals(definition.getRingId(), ringId);
    }

    /**
     * The definition set a ring evaluates (PRD 33): the
     * organization-wide defaults (null attachment) with the
     * ring-attached definitions of the same type OVERRIDING them —
     * one definition per gate type, the ring's own when it has one.
     */
    private Map<String, GateDefinitionEntity> resolvedDefinitions(UUID organizationId, UUID ringId) {
        Map<String, GateDefinitionEntity> resolved = new LinkedHashMap<>();
        for (GateDefinitionEntity definition : definitions.findAllByOrganizationId(organizationId)) {
            if (definition.getRingId() == null) {
                resolved.put(definition.getGateType(), definition);
            }
        }
        for (GateDefinitionEntity definition : definitions.findAllByOrganizationId(organizationId)) {
            if (ringId.equals(definition.getRingId())) {
                resolved.put(definition.getGateType(), definition);
            }
        }
        return resolved;
    }

    /** The single definition a gate's display resolves (ring-attached preferred over the org-wide default). */
    private GateDefinitionEntity resolvedDefinitionOf(ReleaseFacts facts, String gateType) {
        UUID ringId = facts.ringId();
        if (ringId != null) {
            Optional<GateDefinitionEntity> attached =
                    definitions.findByOrganizationIdAndGateTypeAndRingId(facts.organizationId(), gateType, ringId);
            if (attached.isPresent()) {
                return attached.get();
            }
        }
        return definitions
                .findByOrganizationIdAndGateTypeAndRingIdIsNull(facts.organizationId(), gateType)
                .orElse(null);
    }

    /**
     * The current-state row, instantiated when absent: the upsert
     * behind submission and evaluation, translating V31's uniqueness
     * race (two first-reports of one gate) into the read-update path.
     */
    private ReleaseGateEntity upsertGate(ReleaseFacts facts, String gateType, boolean required) {
        ReleaseGateEntity gate =
                gates.findByReleaseIdAndGateType(facts.releaseId(), gateType).orElse(null);
        if (gate != null) {
            return gate;
        }
        try {
            return gates.saveAndFlush(new ReleaseGateEntity(facts.releaseId(), gateType, required));
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage() != null && e.getMessage().contains("uq_release_gates_release_type")) {
                return gates.findByReleaseIdAndGateType(facts.releaseId(), gateType)
                        .orElseThrow(() -> new IllegalStateException("gate row vanished after its unique race"));
            }
            throw e;
        }
    }

    /**
     * The metric resolution (AC4): the gate's value from analytics at
     * evaluation time — no registrant yet (S11-04 pending) or no data
     * for the release records NO_DATA, the fail-closed state; a value
     * is compared against the definition's expected result and the
     * outcome recorded with source METRIC. An existing override
     * stands: the override governs a blocking state, and a later
     * resolution records what it found beside it.
     */
    private void resolveMetric(ReleaseFacts facts, ReleaseGateEntity gate, GateDefinitionEntity definition) {
        Metric metric = GateCatalog.builtinOf(gate.getGateType())
                .map(GateCatalog.Entry::metric)
                .orElse(null);
        if (metric == null) {
            return;
        }
        GateMetricSource source = metricSources.getIfAvailable();
        Optional<Double> value = source == null ? Optional.empty() : source.metricOf(facts.releaseId(), metric);
        Instant occurredAt = Instant.now(clock);
        Status outcome;
        Double observed = null;
        Double expected = thresholdOf(definition);
        if (value.isEmpty()) {
            outcome = Status.NO_DATA;
        } else {
            observed = value.get();
            ResultSchema schema = GateCatalog.schemaOf(gate.getGateType());
            outcome = schema == ResultSchema.COUNT_MAX
                    ? (observed <= (expected == null ? 0 : expected) ? Status.PASSED : Status.FAILED)
                    : (observed >= (expected == null ? 0 : expected) ? Status.PASSED : Status.FAILED);
        }
        gate.record(outcome, null, observed, null, Source.METRIC, occurredAt);
        gates.saveAndFlush(gate);
        results.save(new ReleaseGateResultEntity(
                facts.releaseId(), gate.getGateType(), outcome, null, observed, null, Source.METRIC, occurredAt));

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", facts.releaseRef());
        detail.put("gateType", gate.getGateType());
        detail.put("outcome", outcome.name());
        detail.put("source", Source.METRIC.name());
        if (observed != null) {
            detail.put("observedValue", String.valueOf(observed));
        }
        if (value.isEmpty()) {
            detail.put("note", "fail-closed: no analytics data for this release");
        }
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_GATE_RESULT_RECORDED)
                .occurredAt(occurredAt)
                .orgId(facts.organizationId())
                .tenantId(facts.tenantId())
                .applicationId(facts.applicationId())
                .success(true)
                .detail(detail)
                .build());
    }

    /** The denial reason a blocking gate renders in the 422's errors member (the policy relays it verbatim). */
    private static String denialOf(ReleaseGateEntity gate) {
        return switch (gate.getStatus()) {
            case PENDING -> "awaiting a result (nothing reported yet)";
            case FAILED ->
                gate.getEvidenceUrl() == null ? "failed" : "failed (evidence: " + gate.getEvidenceUrl() + ")";
            case NO_DATA -> "no analytics data yet (fail-closed)";
            case PASSED -> "passed";
        };
    }

    private static ReleaseGateEvaluation.Evaluation.GateState gateStateOf(
            ReleaseGateEntity gate, GateDefinitionEntity definition, boolean overridden) {
        return new ReleaseGateEvaluation.Evaluation.GateState(
                gate.getGateType(),
                definition.getRequired(),
                gate.getStatus().name(),
                gate.getObservedValue(),
                thresholdOf(definition),
                gate.getEvidenceUrl(),
                gate.getSource() == null ? null : gate.getSource().name(),
                overridden,
                gate.getOverriddenBy() == null ? null : gate.getOverriddenBy().toString(),
                gate.getOverriddenReason());
    }

    private GatesView gatesViewOf(ReleaseFacts facts) {
        Map<String, GateDefinitionEntity> resolved =
                facts.ringId() == null ? Map.of() : resolvedDefinitions(facts.organizationId(), facts.ringId());
        Map<String, ReleaseGateEntity> rows = new LinkedHashMap<>();
        for (ReleaseGateEntity gate : gates.findByReleaseIdOrderByGateTypeAsc(facts.releaseId())) {
            rows.put(gate.getGateType(), gate);
        }
        Set<String> gateTypes = new LinkedHashSet<>();
        gateTypes.addAll(resolved.keySet());
        gateTypes.addAll(rows.keySet());
        List<GateView> views = new ArrayList<>();
        for (String gateType : gateTypes) {
            ReleaseGateEntity gate = rows.get(gateType);
            GateDefinitionEntity definition = resolved.get(gateType);
            if (gate == null) {
                // The expected-but-never-instantiated gate (a
                // definition attached after the VALIDATING crossing —
                // the first evaluation consult instantiates it): the
                // honest not-yet-reported state, EXCEPT the
                // metric-backed kinds, which read NO_DATA even here —
                // no analytics data means fail-closed NOW, the truth
                // the evaluation will record, rendered before it does.
                String derived = GateCatalog.metricBacked(gateType) ? Status.NO_DATA.name() : null;
                views.add(new GateView(
                        gateType,
                        definition.getRequired(),
                        true,
                        GateCatalog.schemaOf(gateType).name(),
                        GateCatalog.metricBacked(gateType),
                        derived,
                        null,
                        thresholdOf(definition),
                        null,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        List.of()));
                continue;
            }
            views.add(gateView(facts, gate, definition));
        }
        return new GatesView(
                facts.releaseId(), facts.releaseRef(), facts.status(), facts.ringCode(), List.copyOf(views));
    }

    /** The one-gate view: the recorded row beside its history (newest first). */
    private GateView gateView(ReleaseFacts facts, ReleaseGateEntity gate, GateDefinitionEntity definition) {
        boolean attached = definition != null;
        List<ResultView> history =
                results
                        .findByReleaseIdAndGateTypeOrderByRecordedAtDescIdDesc(facts.releaseId(), gate.getGateType())
                        .stream()
                        .map(row -> new ResultView(
                                row.getOutcome().name(),
                                row.getObservedValue(),
                                row.getEvidenceUrl(),
                                row.getSubmittedBy(),
                                row.getSource().name(),
                                row.getRecordedAt()))
                        .toList();
        return new GateView(
                gate.getGateType(),
                attached ? definition.getRequired() : gate.getRequired(),
                attached,
                GateCatalog.schemaOf(gate.getGateType()).name(),
                GateCatalog.metricBacked(gate.getGateType()),
                gate.getStatus().name(),
                gate.getObservedValue(),
                attached ? thresholdOf(definition) : null,
                gate.getEvidenceUrl(),
                gate.getSource() == null ? null : gate.getSource().name(),
                gate.getSubmittedBy(),
                gate.getEvaluatedAt(),
                gate.isOverridden(),
                gate.getOverriddenBy(),
                gate.getOverriddenReason(),
                gate.getOverriddenAt(),
                history);
    }

    private DefinitionView viewOf(GateDefinitionEntity definition) {
        String ringCode = definition.getRingId() == null
                ? null
                : releases.ringCodeOf(definition.getRingId()).orElse(null);
        return new DefinitionView(
                definition.getId(),
                definition.getOrganizationId(),
                definition.getGateType(),
                definition.getRequired(),
                definition.getConfig(),
                GateCatalog.schemaOf(definition.getGateType()).name(),
                GateCatalog.metricBacked(definition.getGateType()),
                ringCode,
                definition.getCreatedBy(),
                definition.getCreatedAt(),
                definition.getUpdatedAt());
    }

    private static Map<String, String> definitionDetail(
            String gateType, boolean required, Map<String, Object> config, String ring) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("gateType", gateType);
        detail.put("required", String.valueOf(required));
        detail.put("config", config.toString());
        detail.put("ring", ring == null || ring.isBlank() ? "(organization-wide)" : ring);
        return detail;
    }

    /** The externally-reported outcome: pass/fail in either vocabulary, one canonical answer. */
    private static Status parseOutcome(String status) {
        if (status == null || status.isBlank()) {
            throw invalid(Map.of("status", "status is required (pass or fail)"));
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "pass", "passed" -> Status.PASSED;
            case "fail", "failed" -> Status.FAILED;
            default -> throw invalid(Map.of("status", "status must be pass or fail"));
        };
    }

    /** The evidence URL (AC2): mandatory, an http(s) link a human can follow, at most the column's width. */
    private static String validatedEvidence(String evidenceUrl) {
        if (evidenceUrl == null || evidenceUrl.isBlank()) {
            throw new GateException(
                    GateException.Code.GATE_RESULT_INVALID,
                    "an evidence URL is required",
                    Map.of("evidenceUrl", "evidenceUrl is required (the report link a human can follow)"));
        }
        String trimmed = evidenceUrl.trim();
        if (trimmed.length() > 1024) {
            throw new GateException(
                    GateException.Code.GATE_RESULT_INVALID,
                    "the evidence URL is too long",
                    Map.of("evidenceUrl", "evidenceUrl is at most 1024 characters"));
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new GateException(
                    GateException.Code.GATE_RESULT_INVALID,
                    "the evidence URL must be an http(s) link",
                    Map.of("evidenceUrl", "evidenceUrl must be an http:// or https:// link"));
        }
        return trimmed;
    }

    /**
     * The observed value against the catalog's schema: a numeric kind
     * REQUIRES its value (the dashboard's "Coverage 87%" render — the
     * submitter's status is the outcome, the value is the evidence
     * beside the definition's threshold); a pass/fail kind carries
     * none.
     */
    private static Double validatedValue(String gateType, Double observedValue) {
        if (GateCatalog.numeric(gateType)) {
            if (observedValue == null) {
                throw new GateException(
                        GateException.Code.GATE_RESULT_INVALID,
                        "gate '" + gateType + "' reports a numeric value",
                        Map.of("observedValue", "observedValue is required for threshold/ceiling gates"));
            }
            if (!Double.isFinite(observedValue)) {
                throw new GateException(
                        GateException.Code.GATE_RESULT_INVALID,
                        "the observed value must be a finite number",
                        Map.of("observedValue", "observedValue must be a finite number"));
            }
            return observedValue;
        }
        if (observedValue != null) {
            throw new GateException(
                    GateException.Code.GATE_RESULT_INVALID,
                    "pass/fail gates carry no numeric value",
                    Map.of("observedValue", "observedValue applies to threshold/ceiling gates only"));
        }
        return null;
    }

    /** The override reason: mandatory, trimmed, at most the column's width. */
    private static String mandatoryReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid(Map.of("reason", "a reason is required (the override explains itself)"));
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw invalid(Map.of("reason", "reason is at most " + MAX_REASON_LENGTH + " characters"));
        }
        return trimmed;
    }

    private static GateException definitionExists() {
        return new GateException(
                GateException.Code.GATE_DEFINITION_EXISTS,
                "a definition already exists for this gate type at this ring attachment");
    }

    private static GateException invalid(Map<String, String> errors) {
        return new GateException(GateException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }
}
