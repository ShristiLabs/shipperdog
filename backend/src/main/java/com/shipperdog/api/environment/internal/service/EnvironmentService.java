package com.shipperdog.api.environment.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.environment.EnvironmentException;
import com.shipperdog.api.environment.EnvironmentProvisioning;
import com.shipperdog.api.environment.SecretReferenceResolver;
import com.shipperdog.api.environment.internal.entity.EnvironmentConfigVersionEntity;
import com.shipperdog.api.environment.internal.entity.EnvironmentCredentialEntity;
import com.shipperdog.api.environment.internal.entity.EnvironmentEntity;
import com.shipperdog.api.environment.internal.entity.EnvironmentTenantEntity;
import com.shipperdog.api.environment.internal.repository.EnvironmentConfigVersionRepository;
import com.shipperdog.api.environment.internal.repository.EnvironmentCredentialRepository;
import com.shipperdog.api.environment.internal.repository.EnvironmentRepository;
import com.shipperdog.api.environment.internal.repository.EnvironmentTenantRepository;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The environment module's core (issue #58, S9-04, FR-025, PRD 8.11):
 * the organization-grain deployment contexts a release flows through —
 * CRUD with FR-025's five seeded defaults (the migration seeds existing
 * organizations; {@link #seedDefaultEnvironments} seeds every new one
 * inside the organization-creation transaction, the S6-02
 * default-group precedent), the versioned configuration snapshots
 * (append-only ledger, retrieval by version, the manifest's feed), the
 * tenant assignments release creation validates targets against (AC4,
 * deny-by-default), and the reference-only credential bindings with
 * their resolution probe (AC3/AC5 — the database never sees secret
 * material because no surface ever accepts any).
 *
 * <p>Environments are organization-grain (the issue's scope decision):
 * no isolation seam, explicit organization checks — the rings/testers
 * tenth-amendment standing, with the evaluator performing the
 * environment-to-organization hop through {@link EnvironmentAccess}
 * for the item routes. Environments are orthogonal to rings and the
 * tables never collapse (the issue's technical note): a ring is who
 * receives, an environment is what the application points to.
 *
 * <p>Every mutation is audited (AC6) with the facts the next auditor
 * needs: what changed, to what, and who changed it. Configuration
 * writes serialize on the environment row itself (a pessimistic lock
 * taken for the write's duration) so the ledger's next version is
 * race-free without a retry loop; the composite key remains the
 * authority behind the lock.
 */
@Service
public class EnvironmentService implements EnvironmentAccess, EnvironmentProvisioning {

    /** FR-025's seeded defaults, in PRD 8.11's progression order (code, name, position). */
    static final List<Object[]> SEEDED_DEFAULTS = List.of(
            new Object[] {"development", "Development", 0},
            new Object[] {"qa", "QA", 1},
            new Object[] {"uat", "UAT", 2},
            new Object[] {"pilot", "Pilot", 3},
            new Object[] {"production", "Production", 4});

    /** The stable code vocabulary (the rings shape): lowercase kebab. */
    private static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]{0,49}$");

    /** The configuration key vocabulary: lowercase, dot/dash namespaced. */
    private static final Pattern CONFIG_KEY_SHAPE = Pattern.compile("^[a-z][a-z0-9._-]{0,99}$");

    /** The credential binding label vocabulary: lowercase kebab. */
    private static final Pattern CREDENTIAL_NAME_SHAPE = Pattern.compile("^[a-z][a-z0-9-]{0,99}$");

    /** The secret reference vocabulary: printable, no whitespace (mirrors V27's database check). */
    private static final Pattern SECRET_REFERENCE_SHAPE = Pattern.compile("^[\\x21-\\x7E]{1,500}$");

    private static final int MAX_NAME_LENGTH = 100;

    private static final int MAX_BINDING_LENGTH = 64;

    private static final int MAX_CONFIG_ENTRIES = 200;

    private static final int MAX_CONFIG_VALUE_LENGTH = 10000;

    private static final int MAX_REASON_LENGTH = 1000;

    /** The listing's order: the progression, then code (deterministic). */
    private static final Sort PROGRESSION_ORDER =
            Sort.by("position").ascending().and(Sort.by("code").ascending());

    /**
     * The entries document mapper: snapshots serialize from a sorted map
     * so the stored jsonb bytes are deterministic (snapshot equality is
     * byte equality), and parse back into plain string maps.
     */
    private static final ObjectMapper ENTRIES = JsonMapper.builder().build();

    private final EnvironmentRepository environments;
    private final EnvironmentConfigVersionRepository configVersions;
    private final EnvironmentTenantRepository assignments;
    private final EnvironmentCredentialRepository credentials;
    private final OrganizationAccess organizations;
    private final TenantAccess tenants;
    private final SecretReferenceResolver secrets;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    EnvironmentService(
            EnvironmentRepository environments,
            EnvironmentConfigVersionRepository configVersions,
            EnvironmentTenantRepository assignments,
            EnvironmentCredentialRepository credentials,
            OrganizationAccess organizations,
            TenantAccess tenants,
            SecretReferenceResolver secrets,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.environments = environments;
        this.configVersions = configVersions;
        this.assignments = assignments;
        this.credentials = credentials;
        this.organizations = organizations;
        this.tenants = tenants;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // CRUD: create, list, read, update, lifecycle (AC1)
    // -------------------------------------------------------------------------

    /**
     * Creates a custom environment (AC1: the seeded defaults plus
     * whatever the organization adds): organization-grain, born ACTIVE,
     * with its stable code, display name, progression position, and the
     * optional name-only channel and policy bindings. One code and one
     * name per organization (V27's and V1's guards; the lost-race
     * translation answers the same 409). Audited as
     * {@code ENVIRONMENT_CREATED}. Honors {@code Idempotency-Key}.
     */
    @Transactional
    public EnvironmentView create(UUID actorId, CreateCommand command) {
        if (command.organizationId() == null) {
            throw invalid(Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(command.organizationId())) {
            throw new EnvironmentException(EnvironmentException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        String code = validatedCode(command.code());
        String name = validatedName(command.name());
        int position = validatedPosition(command.position());
        String channel = validatedBinding(command.distributionChannel(), "distributionChannel");
        String policy = validatedBinding(command.approvalPolicy(), "approvalPolicy");
        if (environments
                        .findByOrganizationIdAndCode(command.organizationId(), code)
                        .isPresent()
                || environments
                        .findByOrganizationIdAndName(command.organizationId(), name)
                        .isPresent()) {
            throw environmentExists();
        }
        EnvironmentEntity environment =
                new EnvironmentEntity(command.organizationId(), code, name, position, channel, policy);
        try {
            environment = environments.saveAndFlush(environment);
        } catch (DataIntegrityViolationException e) {
            // Only the duplicate authorities translate (the release
            // module's discipline): anything else is a bug to surface.
            if (e.getMessage() != null
                    && (e.getMessage().contains("uq_environments_org_code")
                            || e.getMessage().contains("uq_environments_org_name"))) {
                throw environmentExists();
            }
            throw e;
        }
        trace.environment(environment.getId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("name", name);
        detail.put("position", String.valueOf(position));
        if (channel != null) {
            detail.put("distributionChannel", channel);
        }
        if (policy != null) {
            detail.put("approvalPolicy", policy);
        }
        publish(AuditEventType.ENVIRONMENT_CREATED, actorId, environment, detail);
        return viewOf(environment, 0L);
    }

    /** The listing (progression order): the organization's environments with their current config versions. */
    @Transactional(readOnly = true)
    public Page<EnvironmentView> list(UUID actorId, UUID organizationId, long offset, int limit) {
        mustExistOrganization(organizationId);
        Page<EnvironmentEntity> page =
                environments.findByOrganizationId(organizationId, new CursorPageable(offset, limit, PROGRESSION_ORDER));
        Map<UUID, Long> versions =
                currentVersionsOf(page.map(EnvironmentEntity::getId).toList());
        return page.map(environment -> viewOf(environment, versions.getOrDefault(environment.getId(), 0L)));
    }

    /** The environment's detail: the row's facts plus its current config version. */
    @Transactional(readOnly = true)
    public EnvironmentView get(UUID actorId, UUID environmentId) {
        EnvironmentEntity environment = mustFind(environmentId);
        return viewOf(
                environment, currentVersionsOf(List.of(environment.getId())).getOrDefault(environmentId, 0L));
    }

    /**
     * The PATCH update (AC1): name, position, and the two name-only
     * bindings may move; the CODE never does — it is the frozen
     * vocabulary the manifest deep-copies and the addressing identity.
     * A DISABLED environment accepts no mutations (reactivation is the
     * one crossing out). Audited per changed field; a PATCH that
     * changes nothing writes nothing and audits nothing.
     */
    @Transactional
    public EnvironmentView update(UUID actorId, UUID environmentId, UpdateCommand command) {
        EnvironmentEntity environment = mustFind(environmentId);
        requireActive(environment);
        String name = environment.getName();
        if (command.name() != null) {
            name = validatedName(command.name());
            if (!name.equals(environment.getName())) {
                environments
                        .findByOrganizationIdAndName(environment.getOrganizationId(), name)
                        .filter(other -> !other.getId().equals(environmentId))
                        .ifPresent(other -> {
                            throw environmentExists();
                        });
            }
        }
        int position = command.position() == null ? environment.getPosition() : validatedPosition(command.position());
        String channel = command.distributionChannel() == null
                ? environment.getDistributionChannel()
                : validatedBinding(command.distributionChannel(), "distributionChannel");
        String policy = command.approvalPolicy() == null
                ? environment.getApprovalPolicy()
                : validatedBinding(command.approvalPolicy(), "approvalPolicy");

        Map<String, String> changed = new LinkedHashMap<>();
        if (!name.equals(environment.getName())) {
            changed.put("name", name);
        }
        if (position != environment.getPosition()) {
            changed.put("position", String.valueOf(position));
        }
        if (!Objects.equals(channel, environment.getDistributionChannel())) {
            changed.put("distributionChannel", channel == null ? "-" : channel);
        }
        if (!Objects.equals(policy, environment.getApprovalPolicy())) {
            changed.put("approvalPolicy", policy == null ? "-" : policy);
        }
        if (!changed.isEmpty()) {
            environment.update(name, position, channel, policy);
            environments.saveAndFlush(environment);
            publish(AuditEventType.ENVIRONMENT_UPDATED, actorId, environment, changed);
        }
        return viewOf(environment, currentVersionsOf(List.of(environmentId)).getOrDefault(environmentId, 0L));
    }

    /**
     * The lifecycle crossing (AC1's deactivate; V1's two-value
     * vocabulary): ACTIVE and DISABLED, reversible both ways, an
     * optional reason (capped like every platform reason). The
     * self-crossing is the precise 409 (the
     * {@code RELEASE_TRANSITION_INVALID} shape with current and
     * attempted). A DISABLED environment refuses every other mutation
     * and may not be named by a new release.
     */
    @Transactional
    public EnvironmentView setStatus(UUID actorId, UUID environmentId, String status, String reason) {
        EnvironmentEntity.Status target = parseStatus(status);
        String validatedReason = validatedReason(reason);
        EnvironmentEntity environment = mustFind(environmentId);
        EnvironmentEntity.Status from = environment.getStatus();
        if (from == target) {
            throw new EnvironmentException(
                    EnvironmentException.Code.ENVIRONMENT_STATUS_INVALID,
                    "the environment is already " + target,
                    Map.of("current", from.name(), "attempted", target.name()));
        }
        environment.setStatus(target);
        environments.saveAndFlush(environment);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("from", from.name());
        detail.put("to", target.name());
        if (validatedReason != null) {
            detail.put("reason", validatedReason);
        }
        publish(AuditEventType.ENVIRONMENT_STATUS_CHANGED, actorId, environment, detail);
        return viewOf(environment, currentVersionsOf(List.of(environmentId)).getOrDefault(environmentId, 0L));
    }

    // -------------------------------------------------------------------------
    // Versioned configuration (AC2): writes append, reads resolve
    // -------------------------------------------------------------------------

    /**
     * One configuration write (AC2): the request carries the FULL
     * desired key-value set; the service appends it as the next
     * snapshot version of the ledger — the snapshot IS the value, so a
     * key disappears by its absence from the new set and history never
     * rewrites. Serialized by a write lock on the environment row, so
     * the next version is race-free; audited with the per-key movement
     * (added, removed, changed).
     */
    @Transactional
    public ConfigSnapshotView putConfig(UUID actorId, UUID environmentId, Map<String, String> entries) {
        EnvironmentEntity environment =
                environments.findWithWriteLockById(environmentId).orElseThrow(() -> notFound());
        requireActive(environment);
        Map<String, String> desired = validatedEntries(entries);
        Map<String, String> current = entriesOf(currentRowOf(environmentId));
        long version = configVersions.nextVersionOf(environmentId);
        configVersions.saveAndFlush(
                new EnvironmentConfigVersionEntity(environmentId, version, canonicalJson(desired), actorId));

        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("version", String.valueOf(version));
        detail.put("added", keysOnlyIn(desired, current).toString());
        detail.put("removed", keysOnlyIn(current, desired).toString());
        List<String> changedKeys = new ArrayList<>();
        for (String key : desired.keySet()) {
            if (current.containsKey(key) && !current.get(key).equals(desired.get(key))) {
                changedKeys.add(key);
            }
        }
        detail.put("changed", changedKeys.toString());
        publish(AuditEventType.ENVIRONMENT_CONFIG_UPDATED, actorId, environment, detail);
        return new ConfigSnapshotView(environmentId, version, desired, actorId, Instant.now(clock));
    }

    /**
     * The current snapshot (no {@code version} parameter) or the
     * snapshot AT a version (AC2's retrieval — the feed the S9-03
     * manifest consumes): the full resolved map, never a pointer. A
     * never-configured environment answers version 0 with the empty
     * map — the honest empty; an unknown version is the honest
     * state-dependent miss.
     */
    @Transactional(readOnly = true)
    public ConfigSnapshotView config(UUID actorId, UUID environmentId, Long version) {
        EnvironmentEntity environment = mustFind(environmentId);
        EnvironmentConfigVersionEntity row = version == null
                ? currentRowOf(environmentId)
                : configVersions
                        .findByEnvironmentIdAndVersion(environmentId, version)
                        .orElseThrow(() -> new EnvironmentException(
                                EnvironmentException.Code.ENVIRONMENT_CONFIG_VERSION_NOT_FOUND,
                                "no configuration snapshot exists at version " + version));
        if (row == null) {
            return new ConfigSnapshotView(environmentId, 0L, Map.of(), null, null);
        }
        return new ConfigSnapshotView(
                environmentId, row.getVersion(), entriesOf(row), row.getCreatedBy(), row.getCreatedAt());
    }

    /** The version ledger (chronological): what changed when, and who wrote it. */
    @Transactional(readOnly = true)
    public Page<ConfigVersionView> configVersions(UUID actorId, UUID environmentId, long offset, int limit) {
        mustFind(environmentId);
        return configVersions
                .findByEnvironmentIdOrderByVersionAsc(
                        environmentId,
                        new CursorPageable(offset, limit, Sort.by("version").ascending()))
                .map(row -> new ConfigVersionView(
                        row.getVersion(), entriesOf(row).size(), row.getCreatedBy(), row.getCreatedAt()));
    }

    // -------------------------------------------------------------------------
    // Tenant assignments (AC4): the release-target allow-list
    // -------------------------------------------------------------------------

    /**
     * The assignment write (AC4): the request carries the FULL desired
     * tenant set; every tenant must be a member of the environment's
     * organization (an unknown and a foreign tenant refuse with the
     * identical message — no existence confirmation), and every offense
     * accumulates into ONE 422 keyed by tenant id (the release target
     * validation's shape). The write replaces the set; the movement is
     * audited. Deny-by-default holds: an empty set admits no tenant.
     */
    @Transactional
    public TenantsView putTenants(UUID actorId, UUID environmentId, List<UUID> tenantIds) {
        EnvironmentEntity environment =
                environments.findWithWriteLockById(environmentId).orElseThrow(() -> notFound());
        requireActive(environment);
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID tenantId : tenantIds == null ? List.<UUID>of() : tenantIds) {
            if (tenantId == null) {
                malformed.putIfAbsent("tenantIds", "tenantId is required for every entry");
            } else if (!distinct.add(tenantId)) {
                malformed.putIfAbsent("tenantIds", "a tenant may appear at most once");
            }
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        Map<String, String> offenses = new LinkedHashMap<>();
        for (UUID tenantId : distinct) {
            UUID owning = tenants.owningOrganizationId(tenantId).orElse(null);
            if (owning == null || !owning.equals(environment.getOrganizationId())) {
                // Identical message for unknown and foreign: the refusal
                // never confirms existence (the release surface's rule).
                offenses.put(tenantId.toString(), "tenant is not a member of the environment's organization");
            }
        }
        if (!offenses.isEmpty()) {
            throw new EnvironmentException(
                    EnvironmentException.Code.ENVIRONMENT_TENANTS_INVALID, "invalid tenant assignments", offenses);
        }
        Set<UUID> current = assignedTenantIdsOf(environmentId);
        List<UUID> added =
                distinct.stream().filter(id -> !current.contains(id)).sorted().toList();
        List<UUID> removed =
                current.stream().filter(id -> !distinct.contains(id)).sorted().toList();
        if (!removed.isEmpty()) {
            assignments.deleteByEnvironmentIdAndTenantIdIn(environmentId, removed);
        }
        for (UUID tenantId : added) {
            assignments.save(new EnvironmentTenantEntity(environmentId, tenantId));
        }
        assignments.flush();
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("added", added.toString());
        detail.put("removed", removed.toString());
        publish(AuditEventType.ENVIRONMENT_TENANTS_ASSIGNED, actorId, environment, detail);
        return new TenantsView(environmentId, distinct.stream().sorted().toList());
    }

    /** The assigned tenants with their registry summaries (the dashboard's feed). */
    @Transactional(readOnly = true)
    public Page<TenantView> tenants(UUID actorId, UUID environmentId, long offset, int limit) {
        mustFind(environmentId);
        Page<EnvironmentTenantEntity> page = assignments.findByEnvironmentId(
                environmentId,
                new CursorPageable(offset, limit, Sort.by("tenantId").ascending()));
        Map<UUID, TenantAccess.TenantSummary> summaries = tenants.tenantSummariesOf(
                page.map(EnvironmentTenantEntity::getTenantId).toList());
        return page.map(row -> {
            TenantAccess.TenantSummary summary = summaries.get(row.getTenantId());
            return new TenantView(
                    row.getTenantId(),
                    summary == null ? null : summary.name(),
                    summary == null ? null : summary.status(),
                    row.getCreatedAt());
        });
    }

    // -------------------------------------------------------------------------
    // Credential bindings (AC3/AC5): references only, ever
    // -------------------------------------------------------------------------

    /**
     * The binding write (AC3): the request carries the FULL desired
     * binding set — label plus {@code secret_reference} ONLY; no
     * surface accepts material, so the database can never hold it (the
     * schema review's proof is structural). Same-label rows keep their
     * slot and re-point their reference (a rotation); removed labels
     * go; new labels arrive. Audited as names and references — the
     * references ARE the audit fact, the material never is.
     */
    @Transactional
    public CredentialsView putCredentials(UUID actorId, UUID environmentId, List<BindingCommand> bindings) {
        EnvironmentEntity environment =
                environments.findWithWriteLockById(environmentId).orElseThrow(() -> notFound());
        requireActive(environment);
        Map<String, String> desired = new LinkedHashMap<>();
        Map<String, String> malformed = new LinkedHashMap<>();
        for (BindingCommand binding : bindings == null ? List.<BindingCommand>of() : bindings) {
            if (binding == null || binding.name() == null || binding.name().isBlank()) {
                malformed.putIfAbsent("credentials", "name is required for every binding");
                continue;
            }
            String name = binding.name().trim().toLowerCase(Locale.ROOT);
            if (!CREDENTIAL_NAME_SHAPE.matcher(name).matches()) {
                malformed.put("credentials", "name must be lowercase kebab (letters, digits, hyphens)");
                continue;
            }
            if (desired.containsKey(name)) {
                malformed.put("credentials", "a binding name may appear at most once");
                continue;
            }
            String reference = binding.secretReference() == null
                    ? ""
                    : binding.secretReference().trim();
            if (!SECRET_REFERENCE_SHAPE.matcher(reference).matches()) {
                malformed.put("credentials", "secretReference is 1..500 printable characters without whitespace");
                continue;
            }
            desired.put(name, reference);
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        List<EnvironmentCredentialEntity> current = credentials.findByEnvironmentIdOrderByNameAsc(environmentId);
        Map<String, String> movement = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> repointed = new ArrayList<>();
        Map<String, EnvironmentCredentialEntity> byName = new LinkedHashMap<>();
        current.forEach(row -> byName.put(row.getName(), row));
        for (EnvironmentCredentialEntity row : current) {
            if (!desired.containsKey(row.getName())) {
                credentials.delete(row);
                removed.add(row.getName());
            }
        }
        for (Map.Entry<String, String> binding : desired.entrySet()) {
            EnvironmentCredentialEntity existing = byName.get(binding.getKey());
            if (existing == null) {
                credentials.save(new EnvironmentCredentialEntity(environmentId, binding.getKey(), binding.getValue()));
                added.add(binding.getKey());
            } else if (!existing.getSecretReference().equals(binding.getValue())) {
                existing.setSecretReference(binding.getValue());
                credentials.save(existing);
                repointed.add(binding.getKey());
            }
        }
        credentials.flush();
        movement.put("added", added.toString());
        movement.put("removed", removed.toString());
        movement.put("repointed", repointed.toString());
        publish(AuditEventType.ENVIRONMENT_CREDENTIALS_BOUND, actorId, environment, movement);
        return new CredentialsView(credentials.findByEnvironmentIdOrderByNameAsc(environmentId).stream()
                .map(row -> new CredentialView(
                        row.getId(), row.getName(), row.getSecretReference(), row.getCreatedAt(), row.getUpdatedAt()))
                .toList());
    }

    /** The bindings (references only — the view never carries material, because none exists to carry). */
    @Transactional(readOnly = true)
    public Page<CredentialView> credentials(UUID actorId, UUID environmentId, long offset, int limit) {
        mustFind(environmentId);
        return credentials
                .findByEnvironmentId(
                        environmentId,
                        new CursorPageable(offset, limit, Sort.by("name").ascending()))
                .map(row -> new CredentialView(
                        row.getId(), row.getName(), row.getSecretReference(), row.getCreatedAt(), row.getUpdatedAt()));
    }

    /**
     * The resolution probe (AC5): asks the {@link SecretReferenceResolver}
     * whether the binding's reference resolves — and answers ONLY that.
     * Different environments' bindings resolve independently because
     * they carry different references (Production is not QA); the probe
     * is the demonstrable integration with the secret-manager wiring,
     * audited because probing credentials is a security-relevant act.
     */
    @Transactional
    public VerifyView verifyCredential(UUID actorId, UUID environmentId, UUID credentialId) {
        EnvironmentEntity environment = mustFind(environmentId);
        EnvironmentCredentialEntity binding = credentials
                .findByIdAndEnvironmentId(credentialId, environmentId)
                .orElseThrow(() ->
                        new EnvironmentException(EnvironmentException.Code.NOT_FOUND, "credential binding not found"));
        boolean resolved = secrets.resolve(binding.getSecretReference()).isPresent();
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", binding.getName());
        detail.put("secretReference", binding.getSecretReference());
        detail.put("resolved", String.valueOf(resolved));
        publish(AuditEventType.ENVIRONMENT_CREDENTIAL_VERIFIED, actorId, environment, detail);
        return new VerifyView(
                binding.getId(), binding.getName(), binding.getSecretReference(), resolved, Instant.now(clock));
    }

    // -------------------------------------------------------------------------
    // EnvironmentProvisioning: the organization-creation callback
    // -------------------------------------------------------------------------

    /**
     * Seeds FR-025's five defaults under the freshly created
     * organization, inside the organization-creation transaction (the
     * S6-02 default-group precedent): a seeding failure fails the whole
     * creation. Idempotent by code — a default whose code is taken is
     * skipped, never duplicated. No audit event: the seeds are part of
     * the organization's creation, which audits itself.
     */
    @Override
    @Transactional
    public void seedDefaultEnvironments(UUID organizationId, UUID actorId) {
        for (Object[] seed : SEEDED_DEFAULTS) {
            String code = (String) seed[0];
            if (environments.existsByOrganizationIdAndCode(organizationId, code)) {
                continue;
            }
            try {
                environments.saveAndFlush(
                        new EnvironmentEntity(organizationId, code, (String) seed[1], (Integer) seed[2], null, null));
            } catch (DataIntegrityViolationException e) {
                // Only the duplicate authority translates (a concurrent
                // seeding of the same organization); anything else is a
                // provisioning failure that fails the creation.
                if (e.getMessage() == null || !e.getMessage().contains("uq_environments_org_code")) {
                    throw e;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // EnvironmentAccess: the public port the release surface and the
    // evaluator consume
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> owningOrganizationOf(UUID environmentId) {
        return environments.findById(environmentId).map(EnvironmentEntity::getOrganizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EnvironmentState> stateOf(UUID environmentId) {
        return environments
                .findById(environmentId)
                .map(environment -> new EnvironmentState(
                        environment.getId(),
                        environment.getOrganizationId(),
                        environment.getCode(),
                        environment.getName(),
                        environment.getPosition(),
                        environment.getStatus().name()));
    }

    @Override
    @Transactional(readOnly = true)
    public EnvironmentConfigSnapshot configSnapshotOf(UUID environmentId) {
        EnvironmentConfigVersionEntity row = currentRowOf(environmentId);
        return row == null
                ? new EnvironmentConfigSnapshot(0L, Map.of())
                : new EnvironmentConfigSnapshot(row.getVersion(), entriesOf(row));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> assignedTenantIdsOf(UUID environmentId) {
        return new LinkedHashSet<>(assignments.findByEnvironmentIdOrderByTenantIdAsc(environmentId).stream()
                .map(EnvironmentTenantEntity::getTenantId)
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> approvalPolicyNameOf(UUID environmentId) {
        return environments.findById(environmentId).map(EnvironmentEntity::getApprovalPolicy);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The environment row's facts plus its current config version. */
    public record EnvironmentView(
            UUID id,
            UUID organizationId,
            String code,
            String name,
            int position,
            String status,
            String distributionChannel,
            String approvalPolicy,
            long configVersion,
            Instant createdAt,
            Instant updatedAt) {}

    /** One configuration snapshot: the version, the full resolved map, and the write's actor and moment. */
    public record ConfigSnapshotView(
            UUID environmentId, long version, Map<String, String> entries, UUID createdBy, Instant createdAt) {}

    /** One ledger row: the version, its key count, and the write's actor and moment. */
    public record ConfigVersionView(long version, int keyCount, UUID createdBy, Instant createdAt) {}

    /** The assignment set: the environment and its admitted tenants. */
    public record TenantsView(UUID environmentId, List<UUID> tenantIds) {}

    /** The post-write binding set (the replace-set write's answer). */
    public record CredentialsView(List<CredentialView> credentials) {}

    /** One assigned tenant with its registry summary. */
    public record TenantView(UUID tenantId, String name, String status, Instant assignedAt) {}

    /** One credential binding: label and reference — never material, because none exists to show. */
    public record CredentialView(UUID id, String name, String secretReference, Instant createdAt, Instant updatedAt) {}

    /** The resolution probe's answer: whether the reference resolves, and nothing about what it holds. */
    public record VerifyView(
            UUID credentialId, String name, String secretReference, boolean resolved, Instant verifiedAt) {}

    /** The creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(
            UUID organizationId,
            String code,
            String name,
            Integer position,
            String distributionChannel,
            String approvalPolicy) {}

    /** The PATCH command: null fields stay unchanged (the registry's PATCH semantics). */
    public record UpdateCommand(String name, Integer position, String distributionChannel, String approvalPolicy) {}

    /** One credential binding of the replace-set write. */
    public record BindingCommand(String name, String secretReference) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, validation, serialization, audit
    // -------------------------------------------------------------------------

    /** The row's facts as the API answers them (the current config version rides beside). */
    private static EnvironmentView viewOf(EnvironmentEntity environment, long configVersion) {
        return new EnvironmentView(
                environment.getId(),
                environment.getOrganizationId(),
                environment.getCode(),
                environment.getName(),
                environment.getPosition(),
                environment.getStatus().name(),
                environment.getDistributionChannel(),
                environment.getApprovalPolicy(),
                configVersion,
                environment.getCreatedAt(),
                environment.getUpdatedAt());
    }

    /** The environment or the concealed miss (unknown ids only reach here past the evaluator's own miss). */
    private EnvironmentEntity mustFind(UUID environmentId) {
        EnvironmentEntity environment = environments.findById(environmentId).orElse(null);
        if (environment == null) {
            throw notFound();
        }
        trace.environment(environmentId);
        return environment;
    }

    /** A DISABLED environment refuses every mutation except reactivation (the park's contract). */
    private static void requireActive(EnvironmentEntity environment) {
        if (environment.getStatus() != EnvironmentEntity.Status.ACTIVE) {
            throw new EnvironmentException(
                    EnvironmentException.Code.ENVIRONMENT_DISABLED,
                    "the environment '" + environment.getCode() + "' is disabled");
        }
    }

    private void mustExistOrganization(UUID organizationId) {
        if (organizationId == null || !organizations.organizationExists(organizationId)) {
            throw new EnvironmentException(EnvironmentException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    private static EnvironmentException notFound() {
        return new EnvironmentException(EnvironmentException.Code.NOT_FOUND, "environment not found");
    }

    private static EnvironmentException environmentExists() {
        return new EnvironmentException(
                EnvironmentException.Code.ENVIRONMENT_ALREADY_EXISTS,
                "an environment already exists for this organization");
    }

    private static EnvironmentException invalid(Map<String, String> errors) {
        return new EnvironmentException(EnvironmentException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }

    private static String validatedCode(String code) {
        if (code == null || code.isBlank()) {
            throw invalid(Map.of("code", "code is required"));
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (!CODE_SHAPE.matcher(normalized).matches()) {
            throw invalid(
                    Map.of("code", "code must be lowercase kebab (letters, digits, hyphens; 50 characters at most)"));
        }
        return normalized;
    }

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid(Map.of("name", "name is required"));
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw invalid(Map.of("name", "name is at most " + MAX_NAME_LENGTH + " characters"));
        }
        return trimmed;
    }

    private static int validatedPosition(Integer position) {
        if (position == null) {
            throw invalid(Map.of("position", "position is required and must be a non-negative integer"));
        }
        if (position < 0) {
            throw invalid(Map.of("position", "position must be a non-negative integer"));
        }
        return position;
    }

    private static String validatedBinding(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_BINDING_LENGTH) {
            throw invalid(Map.of(field, field + " is at most " + MAX_BINDING_LENGTH + " characters"));
        }
        return trimmed;
    }

    /** The entries of one config write (AC2): shapes validated, count bounded, keys sorted for canonical storage. */
    private static Map<String, String> validatedEntries(Map<String, String> entries) {
        if (entries == null) {
            throw invalid(Map.of("entries", "entries is required (an object; empty clears the configuration)"));
        }
        if (entries.size() > MAX_CONFIG_ENTRIES) {
            throw invalid(Map.of("entries", "at most " + MAX_CONFIG_ENTRIES + " entries per snapshot"));
        }
        Map<String, String> validated = new TreeMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (entry.getKey() == null
                    || !CONFIG_KEY_SHAPE.matcher(entry.getKey()).matches()) {
                throw invalid(Map.of(
                        "entries",
                        "keys are lowercase (letters, digits, dot, underscore, hyphen; 100 characters at most)"));
            }
            if (entry.getValue() == null || entry.getValue().length() > MAX_CONFIG_VALUE_LENGTH) {
                throw invalid(Map.of("entries", "values are at most " + MAX_CONFIG_VALUE_LENGTH + " characters"));
            }
            validated.put(entry.getKey(), entry.getValue());
        }
        return validated;
    }

    private static EnvironmentEntity.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw invalid(Map.of("status", "status is required and must be ACTIVE or DISABLED"));
        }
        try {
            return EnvironmentEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(Map.of("status", "status must be ACTIVE or DISABLED"));
        }
    }

    /** Optional, trimmed, capped (the platform's standing reason rule). */
    private static String validatedReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw invalid(Map.of("reason", "reason is at most " + MAX_REASON_LENGTH + " characters"));
        }
        return trimmed;
    }

    /** The ledger's current row, or null when never configured (version 0). */
    private EnvironmentConfigVersionEntity currentRowOf(UUID environmentId) {
        return configVersions
                .findFirstByEnvironmentIdOrderByVersionDesc(environmentId)
                .orElse(null);
    }

    /** The stored jsonb document parsed back to its string map. */
    private static Map<String, String> entriesOf(EnvironmentConfigVersionEntity row) {
        if (row == null) {
            return Map.of();
        }
        JsonNode document = ENTRIES.readTree(row.getEntries());
        Map<String, String> entries = new TreeMap<>();
        document.properties()
                .forEach(entry -> entries.put(entry.getKey(), entry.getValue().asString()));
        return entries;
    }

    /** The canonical stored form: keys sorted (a TreeMap serialized), so snapshot equality is byte equality. */
    private static String canonicalJson(Map<String, String> entries) {
        return ENTRIES.writeValueAsString(new TreeMap<>(entries));
    }

    private static List<String> keysOnlyIn(Map<String, String> left, Map<String, String> right) {
        List<String> keys = new ArrayList<>();
        left.keySet().stream().filter(key -> !right.containsKey(key)).sorted().forEach(keys::add);
        return keys;
    }

    /** The current config versions of a page of environments: one grouped query per page (the listing's shape). */
    private Map<UUID, Long> currentVersionsOf(List<UUID> environmentIds) {
        if (environmentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> versions = new LinkedHashMap<>();
        for (Object[] row : environments.currentVersions(environmentIds)) {
            versions.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return versions;
    }

    private void publish(AuditEventType type, UUID actorId, EnvironmentEntity environment, Map<String, String> detail) {
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(environment.getOrganizationId())
                .success(true)
                .detail(detail)
                .build());
    }
}
