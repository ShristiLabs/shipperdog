package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.gate.GateDefinitionAccess;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.RingException;
import com.shipperdog.api.release.RingProvisioning;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TesterGroupAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ring surface's core (issue #43, S10-04, FR-017, PRD 8.10/33):
 * the organization-grain CRUD over the V24 rings table the V32
 * migration just gave behavior to, the seeded defaults every
 * organization owns from its creation, and the validation rules that
 * keep the progressive order honest — Production last, always.
 *
 * <p>The model (the story's substrate decisions, preserved): the V24
 * system defaults (organization NULL, fixed ids) stay read-only
 * platform vocabulary; V32 materialized FR-017's five defaults
 * organization-grain for every existing organization, and {@link
 * #seedDefaultRings} seeds every new one inside its creation
 * transaction (the EnvironmentProvisioning precedent). An
 * organization's EFFECTIVE vocabulary is its own rings plus every
 * system ring it has not redefined under the same code, ordered by
 * position with the organization's ring first at a tie — {@code
 * resolveByCode}'s organization-preferred contract, the same order the
 * promotion engine walks (position+1 through THIS order; S10-05).
 *
 * <p>The write rules (the acceptance criteria):
 * <ul>
 *   <li>One code per scope (V24's {@code uq_rings_scope_code}); an
 *       organization ring under a SYSTEM code is the documented shadow
 *       that redefines the code's meaning for the organization.</li>
 *   <li>Production last: a non-production ring never takes a position
 *       at or past the effective production ring's, and the production
 *       ring never leaves the last position (the order an Organization
 *       Admin reorders within, never past).</li>
 *   <li>The production ring carries governance or refuses: an
 *       approval-policy reference AND at least one required gate
 *       definition (ring-attached or organization-wide) — the widest
 *       audience is never open by omission.</li>
 *   <li>Every eligibility id is one of the organization's tenants or
 *       tenant-groups at write time (one 422 carrying every
 *       offense).</li>
 *   <li>Every edit audits — ring edits change who can receive what
 *       (the story's fifth criterion).</li>
 * </ul>
 *
 * <p>Name-only references (approval policy, distribution channel)
 * follow the V27 environments precedent: validated shape, never a
 * foreign key — renaming a workflow must not strand ring rows; an
 * unresolved policy name fails closed at resolution (the S10-02
 * decision, honored for rings since the approval engine's ring leg).
 */
@Service
public class RingService implements RingProvisioning {

    /** FR-017's seeded defaults, in PRD 8.10's progression order (code, name, position). */
    static final List<Object[]> SEEDED_DEFAULTS = List.of(
            new Object[] {"engineering", "Engineering", 0},
            new Object[] {"qa", "QA", 1},
            new Object[] {"tenant-pilot", "Tenant Pilot", 2},
            new Object[] {"uat", "UAT", 3},
            new Object[] {"production", "Production", 4});

    /** The ring code shape (V24's database check, answered before any write): lowercase kebab. */
    private static final String CODE_PATTERN = "^[a-z][a-z0-9-]{0,49}$";

    /** The name-only reference cap (V32's database checks pin the same width, the V27 precedent). */
    private static final int MAX_BINDING_LENGTH = 64;

    private static final int MAX_NAME_LENGTH = 100;

    private static final int MAX_ELIGIBILITY_IDS = 200;

    /** The terminal code: the ring that must always sort last (FR-017's vocabulary anchor). */
    private static final String PRODUCTION = "production";

    private final RingRepository rings;
    private final OrganizationAccess organizations;
    private final TenantAccess tenants;
    private final TesterGroupAccess testerGroups;
    private final GateDefinitionAccess gateDefinitions;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    RingService(
            RingRepository rings,
            OrganizationAccess organizations,
            TenantAccess tenants,
            TesterGroupAccess testerGroups,
            GateDefinitionAccess gateDefinitions,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.rings = rings;
        this.organizations = organizations;
        this.tenants = tenants;
        this.testerGroups = testerGroups;
        this.gateDefinitions = gateDefinitions;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // The effective vocabulary (AC1): the listing and the detail
    // -------------------------------------------------------------------------

    /**
     * The organization's effective ring vocabulary (AC1): its own rings
     * plus every system default it has not redefined under the same
     * code, in progressive order — position ascending, then code
     * ascending, a TOTAL order even when two rings share a position
     * (the seeded five occupy 0-4 densely, so a custom ring INSERTED
     * between stages ties its lower neighbor and sorts after it by
     * code; the promotion engine walks this order, never raw
     * integers). The console renders it as the rollout ladder; only
     * the Production-last boundary is enforced on writes.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RingView> list(
            UUID actorId, UUID organizationId, long offset, int limit) {
        requireOrganization(organizationId);
        List<RingEntity> effective = effectiveVocabularyOf(organizationId);
        GateSets gateSets = gateSetsOf(organizationId);
        List<RingView> all =
                effective.stream().map(ring -> viewOf(ring, gateSets.of(ring))).toList();
        int from = (int) Math.min(offset, all.size());
        int to = (int) Math.min(offset + limit, all.size());
        return new org.springframework.data.domain.PageImpl<>(
                all.subList(from, to), new com.shipperdog.api.config.CursorPageable(offset, limit), all.size());
    }

    /**
     * One ring of the organization's effective vocabulary: its own
     * ring or an unshadowed system default, read back with its gate
     * set. A ring of another organization conceals exactly like an
     * unknown id.
     */
    @Transactional(readOnly = true)
    public RingView get(UUID actorId, UUID organizationId, UUID ringId) {
        requireOrganization(organizationId);
        RingEntity ring = visibleRingOf(organizationId, ringId);
        return viewOf(ring, gateSetsOf(organizationId).of(ring));
    }

    // -------------------------------------------------------------------------
    // CRUD (AC1): create, edit, delete — the organization's rings only
    // -------------------------------------------------------------------------

    /**
     * Creates an organization ring (AC1): a new code of the
     * organization's own, or a SYSTEM code — the shadow that redefines
     * the code's meaning for the organization (its own name, order, and
     * rules; resolution prefers it ever after). Validations refuse
     * before any write: shape, the progressive order (Production
     * last), and — for the production code — the governance pair (the
     * approval-policy reference and the required gate set). Audited.
     * Honors {@code Idempotency-Key}.
     */
    @Transactional
    public RingView create(UUID actorId, CreateCommand command) {
        requireOrganization(command.organizationId());
        String code = validatedCode(command.code());
        String name = validatedName(command.name());
        int position = validatedPosition(command.position());
        Map<String, Object> eligibility = validatedEligibility(command.organizationId(), command.eligibility());
        String approvalPolicy = validatedBinding(command.approvalPolicy(), "approvalPolicy");
        String promotionPolicy = validatedPromotionPolicy(command.promotionPolicy());
        String distributionChannel = validatedBinding(command.distributionChannel(), "distributionChannel");

        List<RingEntity> effective = effectiveVocabularyOf(command.organizationId());
        if (effective.stream()
                .anyMatch(ring -> ring.getOrganizationId() != null
                        && command.organizationId().equals(ring.getOrganizationId())
                        && ring.getCode().equals(code))) {
            throw ringExists();
        }
        requireProductionStanding(command.organizationId(), code, null, approvalPolicy, position, effective);

        RingEntity ring = new RingEntity(
                null,
                command.organizationId(),
                code,
                name,
                position,
                eligibility,
                approvalPolicy,
                promotionPolicy,
                distributionChannel);
        RingEntity saved;
        try {
            saved = rings.saveAndFlush(ring);
        } catch (DataIntegrityViolationException e) {
            // Only the duplicate authority translates (the environment precedent).
            if (e.getMessage() != null && e.getMessage().contains("uq_rings_scope_code")) {
                throw ringExists();
            }
            throw e;
        }

        trace.organization(command.organizationId());
        audit.publish(AuditEvent.builder(AuditEventType.RING_CREATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(command.organizationId())
                .success(true)
                .detail(ringDetail(
                        code, name, position, eligibility, approvalPolicy, promotionPolicy, distributionChannel))
                .build());
        return viewOf(saved, gateSetsOf(command.organizationId()).of(saved));
    }

    /**
     * Edits an organization ring (AC1: rename, reorder, re-rule): the
     * display name, the position, the eligibility rules (absent keeps
     * them; an empty object CLEARS them — the documented reset), and
     * the three references. The CODE never moves — it is the identity
     * releases, targets, gate definitions, and approval workflows key
     * on. System rings refuse (RING_SYSTEM_IMMUTABLE): the shared
     * vocabulary is customized by shadowing, never rewritten. The
     * progressive order and the production governance pair hold on
     * every write. Audited with the changed facts.
     */
    @Transactional
    public RingView update(UUID actorId, UUID organizationId, UUID ringId, UpdateCommand command) {
        requireOrganization(organizationId);
        RingEntity addressed = rings.findById(ringId).orElseThrow(RingService::notFound);
        if (addressed.getOrganizationId() == null) {
            // A system ring addressed for edit: visible, but never
            // writable — the precise refusal, not a miss.
            throw new RingException(
                    RingException.Code.RING_SYSTEM_IMMUTABLE,
                    "system rings cannot be edited; define an organization ring with the same code '"
                            + addressed.getCode() + "' to customize it");
        }
        if (!addressed.getOrganizationId().equals(organizationId)) {
            throw notFound();
        }
        RingEntity ring = addressed;
        String name = command.name() == null ? ring.getName() : validatedName(command.name());
        int position = command.position() == null ? ring.getPosition() : validatedPosition(command.position());
        Map<String, Object> eligibility = command.eligibility() == null
                ? ring.getEligibility()
                : validatedEligibility(organizationId, command.eligibility());
        String approvalPolicy = command.approvalPolicy() == null
                ? ring.getApprovalPolicy()
                : validatedBinding(command.approvalPolicy(), "approvalPolicy");
        String promotionPolicy = command.promotionPolicy() == null
                ? ring.getPromotionPolicy()
                : validatedPromotionPolicy(command.promotionPolicy());
        String distributionChannel = command.distributionChannel() == null
                ? ring.getDistributionChannel()
                : validatedBinding(command.distributionChannel(), "distributionChannel");

        List<RingEntity> effective = effectiveVocabularyOf(organizationId);
        requireProductionStanding(organizationId, ring.getCode(), ring.getId(), approvalPolicy, position, effective);

        Map<String, String> changed = new LinkedHashMap<>();
        if (!name.equals(ring.getName())) {
            changed.put("name", ring.getName() + " -> " + name);
        }
        if (position != ring.getPosition()) {
            changed.put("position", ring.getPosition() + " -> " + position);
        }
        if (!java.util.Objects.equals(eligibility, ring.getEligibility())) {
            changed.put("eligibility", eligibilitySummary(eligibility));
        }
        if (!java.util.Objects.equals(approvalPolicy, ring.getApprovalPolicy())) {
            changed.put("approvalPolicy", (approvalPolicy == null ? "-" : approvalPolicy));
        }
        if (!promotionPolicy.equals(ring.getPromotionPolicy())) {
            changed.put("promotionPolicy", ring.getPromotionPolicy() + " -> " + promotionPolicy);
        }
        if (!java.util.Objects.equals(distributionChannel, ring.getDistributionChannel())) {
            changed.put("distributionChannel", (distributionChannel == null ? "-" : distributionChannel));
        }
        ring.edit(name, position, eligibility, approvalPolicy, promotionPolicy, distributionChannel);
        rings.flush();

        trace.organization(organizationId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("code", ring.getCode());
        detail.putAll(changed);
        audit.publish(AuditEvent.builder(AuditEventType.RING_UPDATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(detail)
                .build());
        return viewOf(ring, gateSetsOf(organizationId).of(ring));
    }

    /**
     * Deletes an organization ring (AC1): reference data that changes
     * who can receive what is never silently unlinked — a ring
     * referenced by releases, targets, gate definitions, or approval
     * workflows refuses with {@code RING_IN_USE} (the database's
     * foreign keys are the authority; this is their translation).
     * System rings refuse ({@code RING_SYSTEM_IMMUTABLE}). The code's
     * effective meaning falls back to the system default when one
     * exists. Audited with the full deleted facts.
     */
    @Transactional
    public void delete(UUID actorId, UUID organizationId, UUID ringId) {
        requireOrganization(organizationId);
        RingEntity ring = rings.findById(ringId).orElseThrow(() -> notFound());
        if (ring.getOrganizationId() == null) {
            throw new RingException(RingException.Code.RING_SYSTEM_IMMUTABLE, "system rings cannot be deleted");
        }
        if (!ring.getOrganizationId().equals(organizationId)) {
            throw notFound();
        }
        try {
            rings.delete(ring);
            rings.flush();
        } catch (DataIntegrityViolationException e) {
            throw new RingException(
                    RingException.Code.RING_IN_USE,
                    "the ring is referenced by releases, targets, gate definitions, or approval workflows");
        }

        trace.organization(organizationId);
        audit.publish(AuditEvent.builder(AuditEventType.RING_DELETED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .success(true)
                .detail(ringDetail(
                        ring.getCode(),
                        ring.getName(),
                        ring.getPosition(),
                        ring.getEligibility(),
                        ring.getApprovalPolicy(),
                        ring.getPromotionPolicy(),
                        ring.getDistributionChannel()))
                .build());
    }

    // -------------------------------------------------------------------------
    // RingProvisioning: the organization-creation hook (AC1)
    // -------------------------------------------------------------------------

    /**
     * Seeds FR-017's five defaults under the freshly created
     * organization (AC1: the seeded rings exist on org creation), the
     * EnvironmentProvisioning precedent: idempotent per code (an
     * organization that already defined one keeps its own), no audit
     * event (the seeds are part of the creation, not edits of it), and
     * a seeding failure fails the whole creation.
     */
    @Override
    @Transactional
    public void seedDefaultRings(UUID organizationId, UUID actorId) {
        for (Object[] seed : SEEDED_DEFAULTS) {
            String code = (String) seed[0];
            if (rings.resolveByCode(code, organizationId).stream()
                    .anyMatch(ring -> organizationId.equals(ring.getOrganizationId()))) {
                continue;
            }
            try {
                rings.saveAndFlush(new RingEntity(
                        null, organizationId, code, (String) seed[1], (Integer) seed[2], null, null, "MANUAL", null));
            } catch (DataIntegrityViolationException e) {
                // Only a lost seeding race translates to a skip; a
                // different violation is a bug to surface.
                if (e.getMessage() != null && e.getMessage().contains("uq_rings_scope_code")) {
                    continue;
                }
                throw e;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Views and commands
    // -------------------------------------------------------------------------

    /**
     * The ring view: the identity and order, whether the row is the
     * organization's own or a system default, the four PRD 8.10
     * properties, and the ring's gate set — the required-gate
     * definitions attached to it plus the organization-wide standing
     * (S10-03's catalog), the reference the production rule validates.
     */
    public record RingView(
            UUID id,
            String code,
            String name,
            int position,
            boolean organizationScoped,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel,
            List<GateSetEntry> gateSet) {}

    /** One gate of the ring's set: the type and its required standing. */
    public record GateSetEntry(String gateType, boolean required) {}

    /** The creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(
            UUID organizationId,
            String code,
            String name,
            Integer position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {}

    /**
     * The edit command: absent fields keep their standing; an empty
     * {@code eligibility} object CLEARS the rules (the documented
     * reset — name-only references follow the environments precedent
     * and clear by recreation, not by patch).
     */
    public record UpdateCommand(
            String name,
            Integer position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, validation, order, audit
    // -------------------------------------------------------------------------

    /** The organization must exist (the permission check conceals first; the service keeps one vocabulary). */
    private void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw invalid(Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(organizationId)) {
            throw new RingException(RingException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    /**
     * The effective vocabulary: the organization's rings plus every
     * system default whose code the organization has not redefined,
     * ordered (position, organization ring first at a tie, code) —
     * the order {@code resolveByCode} prefers and the promotion engine
     * walks. Public since S10-06: the policy engine's dry-run resolves
     * the next ring the same way (the module's own internal surface).
     */
    public List<RingEntity> effectiveVocabularyOf(UUID organizationId) {
        List<RingEntity> own = rings.findByOrganizationIdOrderByPositionAscCodeAsc(organizationId);
        Set<String> ownCodes = new LinkedHashSet<>();
        own.forEach(ring -> ownCodes.add(ring.getCode()));
        List<RingEntity> effective = new ArrayList<>(own);
        for (RingEntity system : rings.findByOrganizationIdIsNullOrderByPositionAscCodeAsc()) {
            if (!ownCodes.contains(system.getCode())) {
                effective.add(system);
            }
        }
        effective.sort(Comparator.comparingInt(RingEntity::getPosition)
                .thenComparing(ring -> ring.getOrganizationId() == null ? 1 : 0)
                .thenComparing(RingEntity::getCode));
        return effective;
    }

    /** The ring as the organization sees it: its own, or the unshadowed system default; anything else conceals. */
    private RingEntity visibleRingOf(UUID organizationId, UUID ringId) {
        return effectiveVocabularyOf(organizationId).stream()
                .filter(ring -> ring.getId().equals(ringId))
                .findFirst()
                .orElseThrow(RingService::notFound);
    }

    /**
     * The gate sets of the vocabulary at once: the catalog grouped by
     * ring attachment — each organization ring's attached definitions
     * plus the organization-wide standing (null attachment), and the
     * standing alone for the system defaults (their attachments land
     * on the organization-preferred ring of the code, the shadow's
     * perspective; a system default read through the fallback carries
     * exactly the organization-wide gates that apply to it).
     */
    private GateSets gateSetsOf(UUID organizationId) {
        Map<UUID, List<GateSetEntry>> byRing = new LinkedHashMap<>();
        List<GateSetEntry> standing = new ArrayList<>();
        for (GateDefinitionAccess.DefinitionFact definition : gateDefinitions.definitionsOf(organizationId)) {
            GateSetEntry entry = new GateSetEntry(definition.gateType(), definition.required());
            if (definition.ringId() == null) {
                standing.add(entry);
            } else {
                byRing.computeIfAbsent(definition.ringId(), ring -> new ArrayList<>())
                        .add(entry);
            }
        }
        byRing.replaceAll((ring, entries) -> {
            List<GateSetEntry> composed = new ArrayList<>(entries);
            composed.addAll(standing);
            return List.copyOf(composed);
        });
        return new GateSets(byRing, List.copyOf(standing));
    }

    /** The composed sets: per organization ring, and the organization-wide standing. */
    private record GateSets(Map<UUID, List<GateSetEntry>> byRing, List<GateSetEntry> standing) {

        /** The set one ring renders: its attachments plus the standing, or the standing alone for a system default. */
        private List<GateSetEntry> of(RingEntity ring) {
            if (ring.getOrganizationId() == null) {
                return standing;
            }
            return byRing.getOrDefault(ring.getId(), standing);
        }
    }

    /** The canonical ring code: normalized and shape-checked (V24's database check answered before any write). */
    private static String validatedCode(String code) {
        if (code == null || code.isBlank()) {
            throw invalid(Map.of("code", "code is required"));
        }
        String normalized = code.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches(CODE_PATTERN)) {
            throw invalid(Map.of(
                    "code",
                    "code is lowercase kebab (a letter, then letters, digits, or dashes; at most 50 characters)"));
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

    private static String validatedPromotionPolicy(String promotionPolicy) {
        if (promotionPolicy == null || promotionPolicy.isBlank()) {
            return "MANUAL";
        }
        String normalized = promotionPolicy.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"MANUAL".equals(normalized) && !"AUTOMATIC".equals(normalized)) {
            throw invalid(Map.of("promotionPolicy", "promotionPolicy is MANUAL or AUTOMATIC"));
        }
        return normalized;
    }

    /** The name-only reference validation (the V27 environments precedent). */
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

    /**
     * The eligibility rules (AC2's vocabulary): {@code null}
     * unconstrained; otherwise an object whose ONLY keys are {@code
     * tenants} and {@code groups}, each an absent-or-non-empty array of
     * ids, every tenant one of the organization's and every group one
     * of its tenants' — one 422 keyed by the offending member so an
     * Organization Admin fixes the whole rule in one pass.
     */
    private Map<String, Object> validatedEligibility(UUID organizationId, Map<String, Object> eligibility) {
        if (eligibility == null) {
            return null;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> allowed = Set.of("tenants", "groups");
        for (String key : eligibility.keySet()) {
            if (!allowed.contains(key)) {
                errors.put("eligibility." + key, "unknown eligibility axis; only 'tenants' and 'groups' are defined");
            }
        }
        if (!errors.isEmpty()) {
            throw eligibilityInvalid(errors);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        Set<UUID> organizationTenants = null;
        for (String axis : List.of("tenants", "groups")) {
            Object value = eligibility.get(axis);
            if (value == null) {
                continue;
            }
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                errors.put("eligibility." + axis, axis + " is an array of ids; omit the axis or list at least one");
                continue;
            }
            if (list.size() > MAX_ELIGIBILITY_IDS) {
                errors.put("eligibility." + axis, axis + " lists at most " + MAX_ELIGIBILITY_IDS + " ids");
                continue;
            }
            List<String> ids = new ArrayList<>(list.size());
            for (Object element : list) {
                String id = element == null ? null : element.toString().trim();
                UUID parsed;
                try {
                    parsed = id == null ? null : UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                    parsed = null;
                }
                if (parsed == null) {
                    errors.putIfAbsent("eligibility." + axis, "every " + axis + " entry is an id");
                    continue;
                }
                if ("tenants".equals(axis)) {
                    if (organizationTenants == null) {
                        organizationTenants = new LinkedHashSet<>(tenants.tenantIdsOfOrganization(organizationId));
                    }
                    if (!organizationTenants.contains(parsed)) {
                        errors.put(
                                "eligibility.tenants[" + parsed + "]",
                                "tenant is not one of the organization's tenants");
                        continue;
                    }
                } else {
                    Optional<UUID> owning = testerGroups.owningTenantOf(parsed);
                    if (owning.isEmpty()) {
                        errors.put("eligibility.groups[" + parsed + "]", "tester group does not exist");
                        continue;
                    }
                    if (organizationTenants == null) {
                        organizationTenants = new LinkedHashSet<>(tenants.tenantIdsOfOrganization(organizationId));
                    }
                    if (!organizationTenants.contains(owning.get())) {
                        errors.put(
                                "eligibility.groups[" + parsed + "]",
                                "tester group does not belong to one of the organization's tenants");
                        continue;
                    }
                }
                ids.add(parsed.toString());
            }
            if (!ids.isEmpty()) {
                normalized.put(axis, ids);
            }
        }
        if (!errors.isEmpty()) {
            throw eligibilityInvalid(errors);
        }
        return normalized.isEmpty() ? Map.of() : normalized;
    }

    /**
     * The distinct-position rule: the organization's own rings hold
     * distinct positions among themselves (the promotion ladder is
     * unambiguous); a tie with a SYSTEM ring is the documented
     * interleave — the organization's ring sorts first at the same
     * position, ahead of the system default it narrows.
     */
    private void requireDistinctOrganizationPositions(
            UUID organizationId, UUID writtenRingId, int position, List<RingEntity> effective) {
        for (RingEntity ring : effective) {
            if (ring.getOrganizationId() == null
                    || !organizationId.equals(ring.getOrganizationId())
                    || ring.getId().equals(writtenRingId)) {
                continue;
            }
            if (ring.getPosition() == position) {
                throw new RingException(
                        RingException.Code.RING_POSITION_INVALID,
                        "the ring position breaks the progression; Production stays last",
                        Map.of(
                                "position",
                                "another organization ring already holds position " + position + " ('" + ring.getCode()
                                        + "')"));
            }
        }
    }

    /**
     * The progressive order and the production governance (AC1/AC3):
     * Production — the ring of code {@code production} — always sorts
     * last, and when the ORGANIZATION writes that ring (shadow or
     * edit) it must carry the governance pair: a non-null
     * approval-policy reference and at least one required gate
     * definition applying to it (ring-attached or organization-wide).
     * The seeded production default stands permissive until the
     * organization first governs it: the validation fires on WRITE,
     * and an organization that never edits the default keeps the M2
     * no-workflow standing (the S10-01 default posture).
     */
    private void requireProductionStanding(
            UUID organizationId,
            String code,
            UUID writtenRingId,
            String approvalPolicy,
            int position,
            List<RingEntity> effective) {
        RingEntity production = effective.stream()
                .filter(ring -> PRODUCTION.equals(ring.getCode()))
                .findFirst()
                .orElse(null);
        boolean writingProduction = PRODUCTION.equals(code);
        if (writingProduction) {
            // The production ring must remain after every other ring.
            for (RingEntity ring : effective) {
                if (writtenRingId != null && ring.getId().equals(writtenRingId)) {
                    continue;
                }
                if (ring.getPosition() >= position) {
                    throw new RingException(
                            RingException.Code.RING_POSITION_INVALID,
                            "the ring position breaks the progression; Production stays last",
                            Map.of(
                                    "position",
                                    "production must sort after '" + ring.getCode() + "' (position "
                                            + ring.getPosition() + ")"));
                }
            }
            if (approvalPolicy == null) {
                throw new RingException(
                        RingException.Code.RING_PRODUCTION_POLICY_REQUIRED,
                        "the production ring must reference an approval policy",
                        Map.of(
                                "approvalPolicy",
                                "the production ring carries an approval-policy reference (a workflow name)"));
            }
            boolean governed = gateDefinitions.definitionsOf(organizationId).stream()
                    .anyMatch(definition -> definition.required()
                            && (definition.ringId() == null
                                    || (writtenRingId != null
                                            && definition.ringId().equals(writtenRingId))));
            if (!governed) {
                throw new RingException(
                        RingException.Code.RING_PRODUCTION_GATES_REQUIRED,
                        "the production ring must reference at least one required gate definition",
                        Map.of(
                                "gateSet",
                                "define a required gate definition (attached to the production ring or organization-wide) before governing the ring"));
            }
            return;
        }
        // A non-production ring never passes the production ring.
        RingEntity terminal = production;
        if (terminal == null) {
            // No production code in the vocabulary — not reachable
            // through the API (the system default is always visible and
            // undeletable); the defensive posture is the last effective
            // ring.
            terminal = effective.get(effective.size() - 1);
        }
        if (position >= terminal.getPosition()) {
            throw new RingException(
                    RingException.Code.RING_POSITION_INVALID,
                    "the ring position breaks the progression; Production stays last",
                    Map.of(
                            "position",
                            "position must stay before the production ring ('" + terminal.getCode() + "' at position "
                                    + terminal.getPosition() + ")"));
        }
    }

    private static RingException notFound() {
        return new RingException(RingException.Code.NOT_FOUND, "ring not found");
    }

    private static RingException ringExists() {
        return new RingException(RingException.Code.RING_ALREADY_EXISTS, "a ring with this code already exists");
    }

    private static RingException eligibilityInvalid(Map<String, String> errors) {
        return new RingException(
                RingException.Code.RING_ELIGIBILITY_INVALID, "the ring eligibility rules are invalid", errors);
    }

    private static RingException invalid(Map<String, String> errors) {
        return new RingException(RingException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }

    private RingView viewOf(RingEntity ring, List<GateSetEntry> gateSet) {
        return new RingView(
                ring.getId(),
                ring.getCode(),
                ring.getName(),
                ring.getPosition(),
                ring.getOrganizationId() != null,
                ring.getEligibility() == null ? Map.of() : ring.getEligibility(),
                ring.getApprovalPolicy(),
                ring.getPromotionPolicy(),
                ring.getDistributionChannel(),
                gateSet);
    }

    /** The audit summary of one ring's standing (the created and deleted facts; edits carry only the changes). */
    private static Map<String, String> ringDetail(
            String code,
            String name,
            int position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("name", name);
        detail.put("position", String.valueOf(position));
        detail.put("eligibility", eligibilitySummary(eligibility));
        detail.put("approvalPolicy", approvalPolicy == null ? "-" : approvalPolicy);
        detail.put("promotionPolicy", promotionPolicy);
        detail.put("distributionChannel", distributionChannel == null ? "-" : distributionChannel);
        return detail;
    }

    /** The eligibility rules as one quotable line: {@code tenants=2,groups=3} or {@code -}. */
    private static String eligibilitySummary(Map<String, Object> eligibility) {
        if (eligibility == null || eligibility.isEmpty()) {
            return "-";
        }
        StringBuilder summary = new StringBuilder();
        for (String axis : List.of("tenants", "groups")) {
            Object value = eligibility.get(axis);
            if (value instanceof List<?> list) {
                if (!summary.isEmpty()) {
                    summary.append(',');
                }
                summary.append(axis).append('=').append(list.size());
            }
        }
        return summary.isEmpty() ? "-" : summary.toString();
    }
}
