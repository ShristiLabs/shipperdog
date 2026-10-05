package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.ApplicationException;
import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.repository.ApplicationRegistryRepository;
import com.shipperdog.api.application.internal.repository.ApplicationScopedRepository;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application registry (issue #13, S3-01, FR-003, PRD 8.3):
 * registration of tenant-owned applications with their platform set and
 * per-platform identifiers, metadata edits with audited identifier
 * changes, and the ACTIVE/ARCHIVED lifecycle. Applications are born
 * ACTIVE; ARCHIVED is the soft retirement state — archived applications
 * stay readable with their audit history and accept no new
 * versions/artifacts (that guard binds in E4/E5, which create versions
 * and artifacts; the reserved 409 APPLICATION_ARCHIVED is documented in
 * the error-code registry for them). The transition is reversible,
 * mirroring the tenant lifecycle precedent.
 *
 * <p>Authorization (evaluated at the controller): {@code app:} grants
 * resolve through the owning tenant/organization union — creation
 * checks {@code app:applications:write} at the future owner tenant, so
 * every creator holds an organization-level role (ORG_ADMIN or
 * APP_OWNER); item routes check at the application itself.
 *
 * <p>Tenant isolation (INV-001, ADR-0002): row reads and writes run
 * through the seam — the route interceptor binds the request's tenant
 * context for application-addressing routes, creation establishes its
 * scope explicitly through {@link TenantScopeExecutor} because the
 * owner tenant arrives in the request body. The organization-wide
 * uniqueness guards (name, bundle id, package name per organization —
 * AC2) necessarily span the tenants of one
 * organization and live on the marked {@link ApplicationRegistryRepository};
 * they answer existence booleans only, and the database indexes remain
 * the final authority, re-derived race-safe without driver-message
 * parsing.
 *
 * <p>Ownership scope (tenant-owned vs organization/shared) is S3-02's
 * discriminator; this story writes tenant-owned rows only, and
 * organization-owned rows (null owner tenant) render as the standard
 * miss until that story lands.
 */
@Service
public class ApplicationService {

    /**
     * Reverse-DNS identifier format (issue technical notes): dot-separated
     * labels, each starting with a letter (Java package convention), at
     * least two labels. Bad identifiers surface later as artifact
     * validation mismatches (S5-05), so they are rejected at the door.
     */
    private static final Pattern REVERSE_DNS = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    private static final int NAME_MAX = 200;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int IDENTIFIER_MAX = 255;

    private final ApplicationScopedRepository applications;
    private final ApplicationRegistryRepository registry;
    private final ApplicationConflictResolver conflicts;
    private final TenantAccess tenants;
    private final TenantScopeExecutor scope;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    ApplicationService(
            ApplicationScopedRepository applications,
            ApplicationRegistryRepository registry,
            ApplicationConflictResolver conflicts,
            TenantAccess tenants,
            TenantScopeExecutor scope,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.applications = applications;
        this.registry = registry;
        this.conflicts = conflicts;
        this.tenants = tenants;
        this.scope = scope;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Registration (AC1) and uniqueness (AC2)
    // -------------------------------------------------------------------------

    /**
     * Registers a tenant-owned application (AC1): at least one platform,
     * the chosen platforms' identifiers required and reverse-DNS valid,
     * uniqueness guards pre-checked with precise 409 codes. The owning
     * organization is derived from the tenant — the single source of
     * truth — never accepted from the caller.
     */
    @Transactional
    public ApplicationView create(
            UUID actorId,
            UUID tenantId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {
        if (tenantId == null) {
            // The permission check denies null targets before the handler
            // runs; this guard keeps the invariant local and loud.
            throw invalid("tenantId is required");
        }
        UUID organizationId = tenants.owningOrganizationId(tenantId)
                .orElseThrow(() -> new ApplicationException(ApplicationException.Code.NOT_FOUND, "tenant not found"));
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            // Deactivation blocks NEW affiliations; an application is one
            // (the registry analog of the membership rule, issue #9/#11).
            throw new ApplicationException(ApplicationException.Code.TENANT_DEACTIVATED, "tenant is deactivated");
        }
        String validatedName = requireName(name);
        String validatedDescription = optionalDescription(description);
        List<String> validatedPlatforms = requirePlatforms(platforms);
        String validatedBundle =
                platformIdentifier(bundleId, validatedPlatforms, ApplicationEntity.Platform.IOS, "bundleId");
        String validatedPackage =
                platformIdentifier(packageName, validatedPlatforms, ApplicationEntity.Platform.ANDROID, "packageName");
        if (registry.existsByOrganizationIdAndName(organizationId, validatedName)) {
            throw nameTaken();
        }
        if (validatedBundle != null && registry.existsByOrganizationIdAndBundleId(organizationId, validatedBundle)) {
            throw bundleTaken();
        }
        if (validatedPackage != null
                && registry.existsByOrganizationIdAndPackageName(organizationId, validatedPackage)) {
            throw packageTaken();
        }
        ApplicationEntity entity = new ApplicationEntity(
                organizationId,
                tenantId,
                validatedName,
                validatedDescription,
                validatedPlatforms,
                validatedBundle,
                validatedPackage);
        ApplicationEntity saved;
        try {
            saved = scope.inScope(tenantId, actorId, () -> {
                ApplicationEntity inserted = applications.saveScoped(entity);
                // Push the insert to the database inside the guard so
                // a lost uniqueness race translates here, not at commit.
                applications.flush();
                return inserted;
            });
        } catch (DataIntegrityViolationException e) {
            // Fresh-transaction re-derivation: the racing transaction is
            // aborted and cannot answer probes (PostgreSQL).
            throw conflicts.insertConflict(organizationId, validatedName, validatedBundle, validatedPackage);
        }
        // The created id is only known after the insert (issue #7 convention).
        trace.application(saved.getId());
        Map<String, String> created = new LinkedHashMap<>();
        created.put("name", validatedName);
        created.put("platforms", String.join(",", validatedPlatforms));
        if (validatedDescription != null) {
            created.put("description", validatedDescription);
        }
        if (validatedBundle != null) {
            created.put("bundleId", validatedBundle);
        }
        if (validatedPackage != null) {
            created.put("packageName", validatedPackage);
        }
        created.put("status", saved.getStatus().name());
        audit(
                AuditEventType.APPLICATION_CREATED,
                actorId,
                organizationId,
                tenantId,
                saved.getId(),
                Map.copyOf(created));
        return ApplicationView.from(saved);
    }

    // -------------------------------------------------------------------------
    // Reads (AC4: archived applications remain readable with history)
    // -------------------------------------------------------------------------

    /** Read is permission-gated at the controller (app:applications:read). */
    @Transactional(readOnly = true)
    public ApplicationView get(UUID applicationId) {
        return ApplicationView.from(load(applicationId));
    }

    // -------------------------------------------------------------------------
    // Metadata edits and lifecycle (AC3, AC4)
    // -------------------------------------------------------------------------

    /**
     * Partial update (AC3): null fields are left unchanged; an empty
     * description clears it. Identifier and platform edits revalidate
     * the per-platform coupling — removing a platform retires its
     * identifier — and every changed field, identifiers included, is
     * audited with before/after pairs (identifier changes affect
     * artifact matching, S5-05). The status field carries the
     * reversible ACTIVE/ARCHIVED transition; V1's DISABLED label stays
     * reserved-unused.
     */
    @Transactional
    public ApplicationView update(
            UUID actorId,
            UUID applicationId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status) {
        ApplicationEntity application = load(applicationId);
        UUID organizationId = application.getOrganizationId();
        Map<String, String> changes = new LinkedHashMap<>();

        String effectiveName = application.getName();
        if (name != null) {
            String validated = requireName(name);
            if (!validated.equals(effectiveName)
                    && registry.existsByOrganizationIdAndNameAndIdNot(organizationId, validated, applicationId)) {
                throw nameTaken();
            }
            putChange(changes, "name", effectiveName, validated);
            effectiveName = validated;
            application.setName(validated);
        }
        if (description != null) {
            String validated = optionalDescription(description);
            if (!Objects.equals(validated, application.getDescription())) {
                putChange(changes, "description", application.getDescription(), validated);
                application.setDescription(validated);
            }
        }
        List<String> effectivePlatforms = application.getPlatforms();
        if (platforms != null) {
            List<String> validated = requirePlatforms(platforms);
            if (!validated.equals(effectivePlatforms)) {
                putChange(changes, "platforms", String.join(",", effectivePlatforms), String.join(",", validated));
                effectivePlatforms = validated;
                application.setPlatforms(validated);
            }
        }
        boolean ios = effectivePlatforms.contains(ApplicationEntity.Platform.IOS.name());
        String effectiveBundle =
                coupleIdentifier(application, changes, ios, bundleId, "bundleId", application.getBundleId());
        boolean android = effectivePlatforms.contains(ApplicationEntity.Platform.ANDROID.name());
        String effectivePackage = coupleIdentifier(
                application, changes, android, packageName, "packageName", application.getPackageName());
        if (status != null) {
            ApplicationEntity.Status parsed = parseStatus(status);
            if (parsed != application.getStatus()) {
                putChange(changes, "status", application.getStatus().name(), parsed.name());
                application.setStatus(parsed);
            }
        }
        try {
            applications.saveScoped(application);
            // Same race discipline as creation: the constraints speak at
            // the database, translated here for precise codes, and the
            // re-derivation runs on a fresh transaction.
            applications.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflicts.updateConflict(
                    organizationId, applicationId, effectiveName, effectiveBundle, effectivePackage);
        }
        audit(
                AuditEventType.APPLICATION_UPDATED,
                actorId,
                organizationId,
                application.getTenantId(),
                applicationId,
                Map.copyOf(changes));
        return ApplicationView.from(application);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The registry view (PRD 8.3 properties minus versions, which are E4). */
    public record ApplicationView(
            UUID id,
            UUID organizationId,
            UUID tenantId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        static ApplicationView from(ApplicationEntity application) {
            return new ApplicationView(
                    application.getId(),
                    application.getOrganizationId(),
                    application.getTenantId(),
                    application.getName(),
                    application.getDescription(),
                    application.getPlatforms(),
                    application.getBundleId(),
                    application.getPackageName(),
                    application.getStatus().name(),
                    application.getCreatedAt(),
                    application.getUpdatedAt());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: loading, coupling, validation, conflicts
    // -------------------------------------------------------------------------

    /**
     * Loads through the seam inside the route's bound context: the seam
     * composes {@code tenantId = context} into the query, so an
     * application of another tenant is indistinguishable from an unknown
     * id — the standard concealed miss (PRD 12). The ownership resolve
     * first keeps unknown ids and the not-yet-existing organization-owned
     * mode on the same miss instead of an unscoped-seam refusal.
     */
    private ApplicationEntity load(UUID applicationId) {
        return registry.ownershipOf(applicationId)
                .filter(ownership -> ownership.tenantId() != null)
                .flatMap(ownership -> applications.findScoped(applicationId))
                .orElseThrow(
                        () -> new ApplicationException(ApplicationException.Code.NOT_FOUND, "application not found"));
    }

    /**
     * Applies one identifier's platform-coupling rule and audits the
     * change: the identifier is required exactly when its platform is
     * chosen, provided values must be reverse-DNS, and removing the
     * platform retires the stored identifier (the caller cannot supply
     * one the platform set no longer wants).
     */
    private static String coupleIdentifier(
            ApplicationEntity application,
            Map<String, String> changes,
            boolean platformChosen,
            String requested,
            String field,
            String current) {
        if (!platformChosen) {
            if (requested != null) {
                throw invalid(field + " is set but its platform is not chosen");
            }
            if (current != null) {
                putChange(changes, field, current, null);
                if ("bundleId".equals(field)) {
                    application.setBundleId(null);
                } else {
                    application.setPackageName(null);
                }
            }
            return null;
        }
        if (requested != null) {
            String validated = requireIdentifier(requested, field);
            if (!validated.equals(current)) {
                putChange(changes, field, current, validated);
                if ("bundleId".equals(field)) {
                    application.setBundleId(validated);
                } else {
                    application.setPackageName(validated);
                }
            }
            return validated;
        }
        if (current == null) {
            throw invalid(field + " is required when its platform is chosen");
        }
        return current;
    }

    /** Creation-side coupling: required when chosen, forbidden otherwise. */
    private static String platformIdentifier(
            String value, List<String> platforms, ApplicationEntity.Platform platform, String field) {
        boolean chosen = platforms.contains(platform.name());
        if (!chosen) {
            if (value != null) {
                throw invalid(field + " is set but its platform is not chosen");
            }
            return null;
        }
        return requireIdentifier(value, field);
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required when its platform is chosen");
        }
        String trimmed = value.trim();
        if (trimmed.length() > IDENTIFIER_MAX || !REVERSE_DNS.matcher(trimmed).matches()) {
            throw invalid(field + " must be a reverse-DNS identifier like com.example.app");
        }
        return trimmed;
    }

    /** At least one platform (AC1), deduplicated in canonical order. */
    private static List<String> requirePlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            throw invalid("at least one platform is required (IOS, ANDROID)");
        }
        Set<String> canonical = new LinkedHashSet<>();
        for (String platform : platforms) {
            if (platform == null) {
                throw invalid("platform values must be IOS or ANDROID");
            }
            try {
                canonical.add(ApplicationEntity.Platform.valueOf(platform.trim().toUpperCase(Locale.ROOT))
                        .name());
            } catch (IllegalArgumentException e) {
                throw invalid("platform values must be IOS or ANDROID: " + platform.trim());
            }
        }
        return canonical.stream().toList();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > NAME_MAX) {
            throw invalid("name is required (max " + NAME_MAX + " characters)");
        }
        return name.trim();
    }

    private static String optionalDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.length() > DESCRIPTION_MAX) {
            throw invalid("description must be at most " + DESCRIPTION_MAX + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Only the S3-01 lifecycle values; DISABLED stays reserved-unused. */
    private static ApplicationEntity.Status parseStatus(String status) {
        if ("ACTIVE".equals(status) || "ARCHIVED".equals(status)) {
            return ApplicationEntity.Status.valueOf(status);
        }
        throw invalid("status must be ACTIVE or ARCHIVED");
    }

    private static ApplicationException nameTaken() {
        return new ApplicationException(
                ApplicationException.Code.NAME_TAKEN, "application name is already in use in the organization");
    }

    private static ApplicationException bundleTaken() {
        return new ApplicationException(
                ApplicationException.Code.BUNDLE_TAKEN, "bundle id is already in use in the organization");
    }

    private static ApplicationException packageTaken() {
        return new ApplicationException(
                ApplicationException.Code.PACKAGE_TAKEN, "package name is already in use in the organization");
    }

    private static ApplicationException invalid(String message) {
        return new ApplicationException(ApplicationException.Code.INVALID_REQUEST, message);
    }

    /** Before/after pair for audit detail; an empty string means unset. */
    private static void putChange(Map<String, String> changes, String field, String before, String after) {
        changes.put(field + ".before", before == null ? "" : before);
        changes.put(field + ".after", after == null ? "" : after);
    }

    private void audit(
            AuditEventType type,
            UUID actorId,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            Map<String, String> detail) {
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .tenantId(tenantId)
                .applicationId(applicationId)
                .success(true)
                .detail(detail)
                .build());
    }
}
