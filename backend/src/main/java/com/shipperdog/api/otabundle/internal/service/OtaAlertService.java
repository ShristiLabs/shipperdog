package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.otabundle.OtaAlertFiredEvent;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.internal.entity.OtaAlertFiringEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaAlertRuleEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaAlertRuleEntity.Comparator;
import com.shipperdog.api.otabundle.internal.entity.OtaAlertRuleEntity.Severity;
import com.shipperdog.api.otabundle.internal.repository.OtaAlertFiringRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaAlertRuleScopedRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleRegistryRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OTA alert machinery (S16-11, issue #173, ADR-0010 D10): the
 * persisted rules' CRUD and the evaluation pass that watches the
 * S16-09 rollups for crossings. A rule names a dictionary metric, a
 * comparator, a threshold, and a lookback window; the pass computes
 * the metric over the window and, on a crossing, lands one {@code
 * ota_alert_firings} row and publishes {@code OtaAlertFiredEvent} in
 * the SAME transaction (the outbox guarantee, ADR-0007) -- the firing
 * surfaces on the console badge, the tenant feed, and the bus.
 *
 * <p><strong>No auto-halt, anywhere</strong> (D10): nothing this
 * service does touches a rollout plan. The firing is the signal; the
 * halt is an operator's manual, audit-trailed act through the S16-06
 * endpoints -- the acceptance criterion pins this by test.
 *
 * <p><strong>The evaluation dictionary</strong> (closed, validated at
 * the CRUD; the schema's open row-value vocabulary admits only these
 * through the service):
 * <ul>
 *   <li>{@code OTA_CHECKS}, {@code OTA_SEEN}, {@code OTA_DOWNLOADS},
 *       {@code OTA_APPLIED}, {@code OTA_FAILURES}, {@code
 *       OTA_ROLLBACKS} -- the window SUM of the S16-09 hourly rollup
 *       counts, read at bucket grain (a window shorter than an hour
 *       reads the buckets that START inside it -- the rollup grain's
 *       honest approximation, the documented cost of evaluating the
 *       rollups instead of the facts).</li>
 *   <li>{@code OTA_APPLY_FAILURE_RATE} -- failures / (applied +
 *       failures) over the same window sums: the fraction of apply
 *       ATTEMPTS that failed, the plan's own example rule. NO_DATA
 *       (no attempts in the window) never fires.</li>
 *   <li>{@code OTA_CRASH_FREE_RATE} -- 1 - crashes / sessions over
 *       the window's SESSION reports on the rule's scope (the
 *       bundle's release, or every release holding one of the
 *       application's bundles when the rule watches the funnel as a
 *       whole) -- the S16-10 bundle-health read's own arithmetic,
 *       windowed. NO_DATA (no sessions) never fires.</li>
 * </ul>
 *
 * <p><strong>One standing firing per rule</strong> (V56's own
 * semantics): while a crossing persists the pass leaves the original
 * firing alone -- {@code fired_at} stays the degradation's start --
 * and the recovery (the metric no longer crossing) stamps {@code
 * resolved_at}. NO_DATA neither fires nor resolves: an empty window
 * is silence, not recovery.
 *
 * <p><strong>The pass is per-rule transactional</strong> (the
 * TransactionTemplate): one rule's write failing never rolls back the
 * pass's earlier firings, and the next pass re-detects whatever did
 * not land -- the rollup job's own idempotent-recompute posture. The
 * pass reads through JDBC, not the seam: a platform-side job has no
 * single organization context to bind ({@code
 * MetricRollupService.runLookback}'s posture).
 */
@Service
public class OtaAlertService {

    private static final Logger log = LoggerFactory.getLogger(OtaAlertService.class);

    /** The listing order: newest rule first, the id breaking ties. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /** The derived apply-failure rate (the plan's example rule). */
    public static final String APPLY_FAILURE_RATE = "OTA_APPLY_FAILURE_RATE";

    /** The derived crash-free rate (the S16-10 bundle-health arithmetic, windowed). */
    public static final String CRASH_FREE_RATE = "OTA_CRASH_FREE_RATE";

    /** The rollup count metrics the dictionary admits (the S16-09 names). */
    private static final Set<String> COUNT_METRICS =
            Set.of("OTA_CHECKS", "OTA_SEEN", "OTA_DOWNLOADS", "OTA_APPLIED", "OTA_FAILURES", "OTA_ROLLBACKS");

    /** The whole closed dictionary the CRUD validates against. */
    private static final Set<String> DICTIONARY = expandDictionary();

    /** The rule detail's firing history bound. */
    private static final int FIRING_HISTORY_LIMIT = 10;

    private final OtaAlertRuleScopedRepository rules;
    private final OtaAlertFiringRepository firings;
    private final ApplicationAccess applications;
    private final OtaBundleRegistryRepository bundleRegistry;
    private final TenantScopeExecutor scopeExecutor;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final java.time.Clock clock;

    OtaAlertService(
            OtaAlertRuleScopedRepository rules,
            OtaAlertFiringRepository firings,
            ApplicationAccess applications,
            OtaBundleRegistryRepository bundleRegistry,
            TenantScopeExecutor scopeExecutor,
            NamedParameterJdbcTemplate jdbc,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            java.time.Clock clock) {
        this.rules = rules;
        this.firings = firings;
        this.applications = applications;
        this.bundleRegistry = bundleRegistry;
        this.scopeExecutor = scopeExecutor;
        this.jdbc = jdbc;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // CRUD: the persisted watches
    // -------------------------------------------------------------------------

    /**
     * Creates a rule: the whole watch shape validated (the closed
     * metric dictionary, the comparator/severity vocabularies, the
     * V56 window bound, a finite threshold), the application probed
     * (the concealed miss; OTA-enabled and not archived, the
     * authoring guard's own standing), and the optional bundle proven
     * to belong to that application.
     */
    @Transactional
    public AlertRuleView create(UUID actorId, RuleCommand command) {
        ShapedRule shaped = validate(command);
        ApplicationProfile application = requireAuthorable(applicationOf(command.applicationId()));
        UUID bundleId = shaped.bundleId() == null ? null : bundleOf(application.applicationId(), shaped.bundleId());
        OtaAlertRuleEntity rule = new OtaAlertRuleEntity(
                application.organizationId(),
                application.applicationId(),
                bundleId,
                shaped.metric(),
                shaped.comparator(),
                shaped.threshold(),
                shaped.windowMinutes(),
                shaped.severity(),
                actorId);
        return inOrganizationScope(application, actorId, () -> viewOf(rules.saveScoped(rule), null));
    }

    /** The application's rules, newest first, with each rule's standing firing beside it (one batched probe). */
    @Transactional(readOnly = true)
    public PageResponse<AlertRuleView> list(UUID actorId, UUID applicationId, int limit, long offset) {
        ApplicationProfile application = applicationOf(applicationId);
        return inOrganizationScope(application, actorId, () -> {
            Page<OtaAlertRuleEntity> page = rules.findAllScoped(
                    (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId),
                    new CursorPageable(offset, limit, NEWEST_FIRST));
            Map<UUID, OtaAlertFiringEntity> standing = standingOf(page.getContent());
            List<AlertRuleView> items = page.getContent().stream()
                    .map(rule -> viewOf(rule, standing.get(rule.getId())))
                    .toList();
            return PageResponse.of(items, page.hasNext(), offset + limit);
        });
    }

    /** One rule with its firing history (newest first, bounded). */
    @Transactional(readOnly = true)
    public AlertRuleDetailView get(UUID actorId, UUID applicationId, UUID ruleId) {
        ApplicationProfile application = applicationOf(applicationId);
        return inOrganizationScope(application, actorId, () -> {
            OtaAlertRuleEntity rule = ruleOf(ruleId);
            OtaAlertFiringEntity standing =
                    firings.findByRuleIdAndResolvedAtIsNull(ruleId).orElse(null);
            List<AlertFiringView> history =
                    firings
                            .findByRuleIdOrderByFiredAtDescIdDesc(ruleId, PageRequest.of(0, FIRING_HISTORY_LIMIT))
                            .stream()
                            .map(OtaAlertService::firingViewOf)
                            .toList();
            return new AlertRuleDetailView(viewOf(rule, standing), history);
        });
    }

    /** Replaces the rule's whole watch shape (the full-replace update; the identity and scope stay). */
    @Transactional
    public AlertRuleView update(UUID actorId, UUID applicationId, UUID ruleId, RuleCommand command) {
        ShapedRule shaped = validate(command);
        ApplicationProfile application = requireAuthorable(applicationOf(applicationId));
        UUID bundleId = shaped.bundleId() == null ? null : bundleOf(application.applicationId(), shaped.bundleId());
        return inOrganizationScope(application, actorId, () -> {
            OtaAlertRuleEntity rule = ruleOf(ruleId);
            rule.reshape(
                    bundleId,
                    shaped.metric(),
                    shaped.comparator(),
                    shaped.threshold(),
                    shaped.windowMinutes(),
                    shaped.severity());
            OtaAlertFiringEntity standing =
                    firings.findByRuleIdAndResolvedAtIsNull(ruleId).orElse(null);
            return viewOf(rules.saveScoped(rule), standing);
        });
    }

    /**
     * Deletes a rule that has never fired -- the mis-authored watch's
     * own remedy. A rule with firing history refuses (the module's
     * 409): the trail stays addressable through its rule, so the
     * console's timeline and the bus payload's rule id keep their
     * referent. Retiring a live rule without losing history is the
     * update's job (reshape it to a threshold it can never cross).
     */
    @Transactional
    public void delete(UUID actorId, UUID applicationId, UUID ruleId) {
        ApplicationProfile application = requireAuthorable(applicationOf(applicationId));
        inOrganizationScope(application, actorId, () -> {
            OtaAlertRuleEntity rule = ruleOf(ruleId);
            if (firings.existsByRuleId(ruleId)) {
                throw new OtaBundleException(
                        OtaBundleException.Code.ALERT_RULE_HAS_FIRINGS,
                        "the rule has firing history; update it instead of deleting it");
            }
            // The seam's guarded single-entity delete (the org-scope
            // predicate rides the row's mapped attributes).
            rules.delete(rule);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // The evaluation pass: the scheduler's whole job
    // -------------------------------------------------------------------------

    /**
     * Evaluates every rule of every organization against the rollups
     * and the SESSION facts: crossings land firings (with the bus
     * event, one transaction each), recoveries stamp the standing
     * rows, NO_DATA stays silent. One rule's failure never stalls the
     * pass -- it logs and continues, and the next pass re-detects.
     */
    public EvaluationReport evaluateAll(Instant now) {
        List<RuleRow> all = jdbc.query(
                """
                SELECT id, organization_id, application_id, ota_bundle_id, metric, comparator, threshold,
                       window_minutes, severity
                FROM ota_alert_rules
                """,
                (rs, i) -> new RuleRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getObject("ota_bundle_id", UUID.class),
                        rs.getString("metric"),
                        rs.getString("comparator"),
                        rs.getDouble("threshold"),
                        rs.getInt("window_minutes"),
                        rs.getString("severity")));
        int fired = 0;
        int resolved = 0;
        for (RuleRow rule : all) {
            try {
                Observation observation = observe(rule, now);
                if (observation == null) {
                    continue;
                }
                boolean crossing = crosses(rule.comparator(), observation.value(), rule.threshold());
                if (crossing
                        && transactions.execute(status -> {
                            return fireIfStanding(rule, observation, now);
                        })) {
                    fired++;
                } else if (!crossing
                        && Boolean.TRUE.equals(transactions.execute(status -> resolveIfStanding(rule, now)))) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "alert rule {} evaluation failed: {}",
                        rule.id(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new EvaluationReport(all.size(), fired, resolved);
    }

    /**
     * One rule's window observation: the metric's value over the
     * window with its arithmetic detail, or NULL when the window is
     * silent (NO_DATA -- the honest answer that neither fires nor
     * resolves).
     */
    private Observation observe(RuleRow rule, Instant now) {
        Instant from = now.minusSeconds(rule.windowMinutes() * 60L);
        if (CRASH_FREE_RATE.equals(rule.metric())) {
            return crashFreeOf(rule, from, now);
        }
        Map<String, Double> sums = rollupSums(rule, from, now);
        if (COUNT_METRICS.contains(rule.metric())) {
            Double value = sums.get(rule.metric());
            // No rollup rows in the window is silence, never a zero
            // crossing -- a count metric cannot fire on emptiness.
            return value == null
                    ? null
                    : new Observation(
                            value,
                            rule.metric() + " " + whole(value) + " " + rule.comparator() + " " + rate(rule.threshold())
                                    + " over " + rule.windowMinutes() + "m");
        }
        double failures = sums.getOrDefault("OTA_FAILURES", 0.0);
        double applied = sums.getOrDefault("OTA_APPLIED", 0.0);
        double attempts = applied + failures;
        return attempts <= 0
                ? null
                : new Observation(
                        failures / attempts,
                        APPLY_FAILURE_RATE + " " + rate(failures / attempts) + " " + rule.comparator() + " "
                                + rate(rule.threshold()) + " over " + rule.windowMinutes() + "m ("
                                + whole(failures) + " failures / " + whole(attempts) + " attempts)");
    }

    /**
     * The window's rollup sums for the OTA count metrics, at the
     * rule's scope (one bundle, or the application's funnel as a
     * whole -- every OTA rollup row carries a bundle, so an
     * unscoped rule sums across all of them).
     */
    private Map<String, Double> rollupSums(RuleRow rule, Instant from, Instant to) {
        MapSqlParameterSource bound = new MapSqlParameterSource()
                .addValue("applicationId", rule.applicationId())
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        String scope;
        if (rule.otaBundleId() == null) {
            scope = "";
        } else {
            scope = " AND ota_bundle_id = :bundleId";
            bound.addValue("bundleId", rule.otaBundleId());
        }
        Map<String, Double> sums = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT metric, sum(value) AS total FROM metric_hourly
                WHERE application_id = :applicationId
                  AND metric IN ('OTA_CHECKS','OTA_SEEN','OTA_DOWNLOADS','OTA_APPLIED','OTA_FAILURES','OTA_ROLLBACKS')
                  AND bucket_start >= :from AND bucket_start < :to
                """ + scope + " GROUP BY metric", bound)) {
            sums.put((String) row.get("metric"), ((Number) row.get("total")).doubleValue());
        }
        return sums;
    }

    /**
     * The crash-free observation: the window's SESSION arithmetic on
     * the rule's scope -- the named bundle's release, or every
     * release holding one of the application's bundles when the rule
     * watches the funnel as a whole (the S16-10 health read's own
     * scoping, windowed; SESSION rows never carry the bundle
     * dimension, so the release join is the only honest attribution).
     */
    private Observation crashFreeOf(RuleRow rule, Instant from, Instant to) {
        MapSqlParameterSource bound = new MapSqlParameterSource()
                .addValue("applicationId", rule.applicationId())
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        String scope;
        if (rule.otaBundleId() == null) {
            scope = """
                    JOIN ota_bundles ob ON ob.release_id = e.release_id AND ob.application_id = :applicationId
                    """;
        } else {
            scope = """
                    JOIN ota_bundles ob ON ob.release_id = e.release_id AND ob.id = :bundleId
                    """;
            bound.addValue("bundleId", rule.otaBundleId());
        }
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT coalesce(sum(e.sessions), 0) AS sessions, coalesce(sum(e.crash_sessions), 0) AS crashes
                FROM analytics_events e
                """ + scope + " WHERE e.event_type = 'SESSION' AND e.occurred_at >= :from AND e.occurred_at < :to",
                bound);
        long sessions = ((Number) row.get("sessions")).longValue();
        long crashes = ((Number) row.get("crashes")).longValue();
        return sessions <= 0
                ? null
                : new Observation(
                        1.0 - (double) crashes / sessions,
                        CRASH_FREE_RATE + " " + rate(1.0 - (double) crashes / sessions) + " " + rule.comparator()
                                + " " + rate(rule.threshold()) + " over " + rule.windowMinutes() + "m (" + crashes
                                + " crashes / " + sessions + " sessions)");
    }

    /**
     * The firing write: one row plus its bus event, one transaction.
     * Answers FALSE when a standing firing already exists -- the
     * crossing persists and the original firing (the degradation's
     * start) stays untouched.
     */
    private boolean fireIfStanding(RuleRow rule, Observation observation, Instant now) {
        if (firings.findByRuleIdAndResolvedAtIsNull(rule.id()).isPresent()) {
            return false;
        }
        OtaAlertFiringEntity firing =
                new OtaAlertFiringEntity(rule.id(), rule.otaBundleId(), observation.value(), observation.detail(), now);
        firings.save(firing);
        firings.flush();
        events.publishEvent(new OtaAlertFiredEvent(
                rule.id(),
                firing.getId(),
                rule.applicationId(),
                rule.organizationId(),
                rule.otaBundleId(),
                rule.metric(),
                rule.comparator(),
                rule.threshold(),
                observation.value(),
                rule.severity(),
                observation.detail(),
                now));
        return true;
    }

    /** The recovery stamp: answers TRUE when a standing firing was resolved. */
    private Boolean resolveIfStanding(RuleRow rule, Instant now) {
        OtaAlertFiringEntity standing =
                firings.findByRuleIdAndResolvedAtIsNull(rule.id()).orElse(null);
        if (standing == null) {
            return false;
        }
        standing.resolve(now);
        firings.save(standing);
        return true;
    }

    /** The comparator's arithmetic (V56's closed vocabulary). */
    static boolean crosses(String comparator, double observed, double threshold) {
        return switch (comparator) {
            case "GT" -> observed > threshold;
            case "GTE" -> observed >= threshold;
            case "LT" -> observed < threshold;
            case "LTE" -> observed <= threshold;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Validation, probes, and the scope
    // -------------------------------------------------------------------------

    /** The command's whole shape, validated at once (field-addressed problem+json). */
    private static ShapedRule validate(RuleCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        UUID applicationId = command.applicationId();
        if (applicationId == null) {
            errors.put("applicationId", "applicationId is required");
        }
        String metric = command.metric();
        if (metric == null || metric.isBlank()) {
            errors.put("metric", "metric is required");
        } else {
            metric = metric.trim().toUpperCase(Locale.ROOT);
            if (!DICTIONARY.contains(metric)) {
                errors.put("metric", "unknown metric " + command.metric());
            }
        }
        Comparator comparator;
        try {
            comparator = command.comparator() == null
                    ? null
                    : Comparator.valueOf(command.comparator().trim());
        } catch (IllegalArgumentException e) {
            comparator = null;
        }
        if (comparator == null) {
            errors.put("comparator", "comparator must be one of GT, GTE, LT, LTE");
        }
        Double threshold = command.threshold();
        if (threshold == null || threshold.isNaN() || threshold.isInfinite()) {
            errors.put("threshold", "threshold must be a finite number");
        }
        Integer windowMinutes = command.windowMinutes();
        if (windowMinutes == null || windowMinutes < 1 || windowMinutes > 10080) {
            errors.put("windowMinutes", "windowMinutes must be between 1 and 10080");
        }
        Severity severity;
        try {
            severity = command.severity() == null
                    ? null
                    : Severity.valueOf(command.severity().trim());
        } catch (IllegalArgumentException e) {
            severity = null;
        }
        if (severity == null) {
            errors.put("severity", "severity must be one of INFO, WARNING, CRITICAL");
        }
        UUID bundleId = null;
        if (command.otaBundleId() != null && !command.otaBundleId().isBlank()) {
            try {
                bundleId = UUID.fromString(command.otaBundleId().trim());
            } catch (IllegalArgumentException e) {
                errors.put("otaBundleId", "otaBundleId must be a UUID");
            }
        }
        if (!errors.isEmpty()) {
            throw new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, "the alert rule is invalid", errors);
        }
        return new ShapedRule(bundleId, metric, comparator, threshold, windowMinutes, severity);
    }

    /**
     * The read-side application probe: the concealed miss only (an
     * unknown or archived application). The OTA opt-in is deliberately
     * NOT checked on reads -- a rule list of an opted-out application
     * is the honest empty page, and anything else would leak the
     * opt-in distinction the concealed 404 exists to hide (the bundle
     * reads' own shape; the authoring guard below is the writes').
     */
    private ApplicationProfile applicationOf(UUID applicationId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaAlertService::applicationMissing);
        if ("ARCHIVED".equals(application.status())) {
            throw applicationMissing();
        }
        return application;
    }

    /**
     * The writes' authoring guard (the {@code requireOtaAuthorable}
     * standing): beyond the probe, the application must have opted in
     * to OTA bundle updates -- rules watch a funnel only the opt-in
     * produces.
     */
    private static ApplicationProfile requireAuthorable(ApplicationProfile application) {
        if (!application.otaEnabled()) {
            throw new OtaBundleException(
                    OtaBundleException.Code.APPLICATION_NOT_OTA_ENABLED,
                    "the application has not enabled OTA bundle updates; no OTA alert rules without the opt-in");
        }
        return application;
    }

    /**
     * The bundle probe: the rule's scope must name one of the
     * application's own bundles (the concealed miss). The registry's
     * application-addressed probe (the create's probe-then-scope
     * order: no operator scope is bound yet, and the seam's id-based
     * reads refuse without one by construction).
     */
    private UUID bundleOf(UUID applicationId, UUID bundleId) {
        return bundleRegistry
                .findByIdAndApplicationId(bundleId, applicationId)
                .map(found -> bundleId)
                .orElseThrow(() -> new OtaBundleException(
                        OtaBundleException.Code.BUNDLE_NOT_FOUND, "the addressed bundle did not resolve"));
    }

    /** The scoped rule read: unknown and foreign ids are the one concealed miss. */
    private OtaAlertRuleEntity ruleOf(UUID ruleId) {
        return rules.findScoped(ruleId).orElseThrow(OtaAlertService::ruleMissing);
    }

    /** Establishes the organization's scope and runs inside it (the org-grain doctrine, the rollout plans' own). */
    private <T> T inOrganizationScope(
            ApplicationProfile application, UUID actorId, java.util.function.Supplier<T> work) {
        return scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private static OtaBundleException applicationMissing() {
        return new OtaBundleException(OtaBundleException.Code.APPLICATION_NOT_FOUND, "unknown application");
    }

    private static OtaBundleException ruleMissing() {
        return new OtaBundleException(
                OtaBundleException.Code.ALERT_RULE_NOT_FOUND, "the addressed rule did not resolve");
    }

    // -------------------------------------------------------------------------
    // Views, standing probes, and formatting
    // -------------------------------------------------------------------------

    /** The page's standing firings in one batched probe (the no-N+1 rule). */
    private Map<UUID, OtaAlertFiringEntity> standingOf(List<OtaAlertRuleEntity> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = page.stream().map(OtaAlertRuleEntity::getId).toList();
        List<OtaAlertFiringEntity> standingRows = jdbc.query(
                """
                SELECT rule_id, ota_bundle_id, observed_value, detail, fired_at
                FROM ota_alert_firings WHERE resolved_at IS NULL AND rule_id IN (:ids)
                """,
                new MapSqlParameterSource("ids", ids),
                (rs, i) -> new OtaAlertFiringEntity(
                        rs.getObject("rule_id", UUID.class),
                        rs.getObject("ota_bundle_id", UUID.class),
                        rs.getDouble("observed_value"),
                        rs.getString("detail"),
                        rs.getObject("fired_at", Instant.class)));
        Map<UUID, OtaAlertFiringEntity> standing = new HashMap<>();
        for (OtaAlertFiringEntity firing : standingRows) {
            standing.put(firing.getRuleId(), firing);
        }
        return standing;
    }

    private static AlertRuleView viewOf(OtaAlertRuleEntity rule, OtaAlertFiringEntity standing) {
        return new AlertRuleView(
                rule.getId(),
                rule.getApplicationId(),
                rule.getOtaBundleId(),
                rule.getMetric(),
                rule.getComparator().name(),
                rule.getThreshold(),
                rule.getWindowMinutes(),
                rule.getSeverity().name(),
                rule.getCreatedAt(),
                rule.getUpdatedAt(),
                standing == null ? null : firingViewOf(standing));
    }

    private static AlertFiringView firingViewOf(OtaAlertFiringEntity firing) {
        return new AlertFiringView(
                firing.getId(),
                firing.getOtaBundleId(),
                firing.getObservedValue(),
                firing.getDetail(),
                firing.getFiredAt(),
                firing.getResolvedAt());
    }

    private static String rate(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String whole(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static Set<String> expandDictionary() {
        Set<String> dictionary = new java.util.LinkedHashSet<>(COUNT_METRICS);
        dictionary.add(APPLY_FAILURE_RATE);
        dictionary.add(CRASH_FREE_RATE);
        return Set.copyOf(dictionary);
    }

    // -------------------------------------------------------------------------
    // Commands, rows, and views
    // -------------------------------------------------------------------------

    /** The create/update body's whole shape. */
    public record RuleCommand(
            UUID applicationId,
            String otaBundleId,
            String metric,
            String comparator,
            Double threshold,
            Integer windowMinutes,
            String severity) {}

    /** The validated command (the enums and bound numbers the row lands). */
    private record ShapedRule(
            UUID bundleId,
            String metric,
            Comparator comparator,
            double threshold,
            int windowMinutes,
            Severity severity) {}

    /** One rule row as the pass reads it (the scheduler's own grain). */
    private record RuleRow(
            UUID id,
            UUID organizationId,
            UUID applicationId,
            UUID otaBundleId,
            String metric,
            String comparator,
            double threshold,
            int windowMinutes,
            String severity) {}

    /** One observation: the window's value beside its arithmetic detail. */
    private record Observation(double value, String detail) {}

    /** The pass's counts (the scheduler's audit line). */
    public record EvaluationReport(int rules, int fired, int resolved) {}

    /** The served rule view, the standing firing beside it when one exists. */
    public record AlertRuleView(
            UUID id,
            UUID applicationId,
            UUID otaBundleId,
            String metric,
            String comparator,
            double threshold,
            int windowMinutes,
            String severity,
            Instant createdAt,
            Instant updatedAt,
            AlertFiringView standingFiring) {}

    /** One firing row's served shape. */
    public record AlertFiringView(
            UUID id, UUID otaBundleId, double observedValue, String detail, Instant firedAt, Instant resolvedAt) {}

    /** The rule detail: the view beside the bounded firing history. */
    public record AlertRuleDetailView(AlertRuleView rule, List<AlertFiringView> firings) {}
}
