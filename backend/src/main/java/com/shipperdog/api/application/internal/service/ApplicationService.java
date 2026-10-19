package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.ApplicationException;
import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.entity.ApplicationEntity.OwnershipScope;
import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.application.internal.repository.ApplicationRegistryRepository;
import com.shipperdog.api.application.internal.repository.ApplicationScopedRepository;
import com.shipperdog.api.application.internal.repository.TenantApplicationAuthorizationRepository;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AuthorizationDecisionInvalidation;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
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
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application registry (issues #13 + #14, S3-01/S3-02, FR-003,
 * PRD 8.3/14): registration of applications with their platform set and
 * per-platform identifiers, metadata edits with audited identifier
 * changes, the ACTIVE/ARCHIVED lifecycle, and — since S3-02 — the
 * ownership scope with its audited transitions. Applications are born
 * ACTIVE; ARCHIVED is the soft retirement state — archived applications
 * stay readable with their audit history and accept no new
 * versions/artifacts (the version guard binds since E4, issue #17,
 * which emits the 409 APPLICATION_ARCHIVED reserved in issue #13; the
 * artifact guard binds with E5). The transition is reversible,
 * mirroring the tenant lifecycle precedent.
 *
 * <p>Ownership scope (issue #14, PRD 14, INV-002): an application is
 * TENANT-owned — it belongs to exactly one tenant of one organization,
 * the tenant derived single source of truth for the organization — or
 * ORGANIZATION-shared — owned by the organization itself with no tenant,
 * visible to organization roles org-wide, and distributed to tenants
 * only through authorization (S3-03, PRD 15: distribution decides which
 * tenants receive a shared application, never the other way around).
 * The pairing is pinned by the V10 database CHECK; the service enforces
 * the same rule at the door (a TENANT registration resolves its owning
 * tenant or fails the standard miss; an ORGANIZATION registration
 * carries no tenant and requires a valid owning organization).
 *
 * <p>Scope transitions (issue #14, AC3) are audited as
 * {@code APPLICATION_SCOPE_CHANGED} and run through the registry
 * guard's single sanctioned cross-scope write: a transition moves the
 * row between scopes by definition, so no scoped context can own both
 * sides (ADR-0002 third amendment). Preconditions: converting
 * ORGANIZATION to TENANT requires a valid, affiliated, active target
 * tenant (INV-002 service check) and zero ACTIVE tenant authorizations
 * (S3-03's mapping; de-authorize first — a converted application would
 * strand grants that reference a scope the row no longer has);
 * converting TENANT to ORGANIZATION requires zero distributions — none
 * can exist until M2's release targets, at the same declared point.
 *
 * <p>Authorization (evaluated at the controller): {@code app:} grants
 * resolve through the owning tenant/organization union — creation
 * checks {@code app:applications:write} at the future owner tenant
 * (TENANT registrations) or at the owning organization (ORGANIZATION
 * registrations), so every creator holds an organization-level role
 * (ORG_ADMIN or APP_OWNER); item routes check at the application
 * itself, organization-shared rows resolving at their organization.
 *
 * <p>Tenant isolation (INV-001, ADR-0002): row reads and writes run
 * through the seam — the route interceptor binds the request's context
 * for application-addressing routes (the owning tenant's for TENANT
 * rows, the owning organization's for ORGANIZATION rows since S3-02),
 * and creation establishes its scope explicitly through
 * {@link TenantScopeExecutor} because the owner arrives in the request
 * body. The organization-wide uniqueness guards (name, bundle id,
 * package name per organization — issue #13 AC2, now across both
 * ownership scopes) necessarily span the tenants of one organization
 * and live on the marked {@link ApplicationRegistryRepository}; they
 * answer existence booleans only, and the database indexes remain the
 * final authority, re-derived race-safe without driver-message parsing.
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
    private final TenantApplicationAuthorizationRepository authorizations;
    private final ApplicationConflictResolver conflicts;
    private final TenantAccess tenants;
    private final OrganizationAccess organizations;
    private final TenantScopeExecutor scope;
    private final AuthorizationDecisionInvalidation decisionCache;
    private final VersionAccess versions;
    private final ReleaseAccess releases;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    ApplicationService(
            ApplicationScopedRepository applications,
            ApplicationRegistryRepository registry,
            TenantApplicationAuthorizationRepository authorizations,
            ApplicationConflictResolver conflicts,
            TenantAccess tenants,
            OrganizationAccess organizations,
            TenantScopeExecutor scope,
            AuthorizationDecisionInvalidation decisionCache,
            VersionAccess versions,
            ReleaseAccess releases,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.applications = applications;
        this.registry = registry;
        this.authorizations = authorizations;
        this.conflicts = conflicts;
        this.tenants = tenants;
        this.organizations = organizations;
        this.scope = scope;
        this.decisionCache = decisionCache;
        this.versions = versions;
        this.releases = releases;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Registration (AC1) and uniqueness (AC2)
    // -------------------------------------------------------------------------

    /**
     * Registers an application (AC1): at least one platform, the chosen
     * platforms' identifiers required and reverse-DNS valid, uniqueness
     * guards pre-checked with precise 409 codes. TENANT scope (the
     * default) derives the owning organization from the future owner
     * tenant — the single source of truth, never accepted from the
     * caller; ORGANIZATION scope (issue #14, PRD 14) states the owning
     * organization directly and carries no tenant.
     */
    @Transactional
    public ApplicationView create(
            UUID actorId,
            String ownershipScope,
            UUID tenantId,
            UUID organizationId,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName) {
        OwnershipScope requestedScope = parseScope(ownershipScope);
        UUID ownerTenantId;
        UUID owningOrganizationId;
        if (requestedScope == OwnershipScope.ORGANIZATION) {
            if (tenantId != null) {
                throw invalid("tenantId is not valid for ORGANIZATION ownership");
            }
            if (organizationId == null) {
                throw invalid("organizationId is required for ORGANIZATION ownership");
            }
            if (!organizations.organizationExists(organizationId)) {
                // The permission check already demands an organization
                // role for non-platform callers; this keeps the platform
                // wildcard on the same standard miss instead of an FK 500.
                throw notFound("organization not found");
            }
            ownerTenantId = null;
            owningOrganizationId = organizationId;
        } else {
            if (organizationId != null) {
                throw invalid("organizationId is only valid for ORGANIZATION ownership");
            }
            if (tenantId == null) {
                // The permission check denies null targets before the
                // handler runs; this guard keeps the invariant local and
                // loud (INV-002: a tenant-scoped application must have its
                // owning tenant).
                throw invalid("tenantId is required");
            }
            owningOrganizationId =
                    tenants.owningOrganizationId(tenantId).orElseThrow(() -> notFound("tenant not found"));
            if (!tenants.acceptsNewAffiliations(tenantId)) {
                // Deactivation blocks NEW affiliations; an application is
                // one (the registry analog of the membership rule, issue
                // #9/#11).
                throw new ApplicationException(ApplicationException.Code.TENANT_DEACTIVATED, "tenant is deactivated");
            }
            ownerTenantId = tenantId;
        }
        String validatedName = requireName(name);
        String validatedDescription = optionalDescription(description);
        List<String> validatedPlatforms = requirePlatforms(platforms);
        String validatedBundle =
                platformIdentifier(bundleId, validatedPlatforms, ApplicationEntity.Platform.IOS, "bundleId");
        String validatedPackage =
                platformIdentifier(packageName, validatedPlatforms, ApplicationEntity.Platform.ANDROID, "packageName");
        if (registry.existsByOrganizationIdAndName(owningOrganizationId, validatedName)) {
            throw nameTaken();
        }
        if (validatedBundle != null
                && registry.existsByOrganizationIdAndBundleId(owningOrganizationId, validatedBundle)) {
            throw bundleTaken();
        }
        if (validatedPackage != null
                && registry.existsByOrganizationIdAndPackageName(owningOrganizationId, validatedPackage)) {
            throw packageTaken();
        }
        ApplicationEntity entity = new ApplicationEntity(
                owningOrganizationId,
                requestedScope,
                ownerTenantId,
                validatedName,
                validatedDescription,
                validatedPlatforms,
                validatedBundle,
                validatedPackage);
        // The scope executor establishes the owning dimension at the point
        // of use: the future owner tenant for TENANT registrations, the
        // owning organization for ORGANIZATION registrations (issue #14).
        ApplicationEntity saved;
        try {
            if (requestedScope == OwnershipScope.ORGANIZATION) {
                saved = scope.inOrganizationScope(owningOrganizationId, actorId, () -> {
                    ApplicationEntity inserted = applications.saveScoped(entity);
                    applications.flush();
                    return inserted;
                });
            } else {
                saved = scope.inScope(ownerTenantId, actorId, () -> {
                    ApplicationEntity inserted = applications.saveScoped(entity);
                    // Push the insert to the database inside the guard so
                    // a lost uniqueness race translates here, not at
                    // commit.
                    applications.flush();
                    return inserted;
                });
            }
        } catch (DataIntegrityViolationException e) {
            // Fresh-transaction re-derivation: the racing transaction is
            // aborted and cannot answer probes (PostgreSQL).
            throw conflicts.insertConflict(owningOrganizationId, validatedName, validatedBundle, validatedPackage);
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
        created.put("ownershipScope", saved.getOwnershipScope().name());
        audit(
                AuditEventType.APPLICATION_CREATED,
                actorId,
                owningOrganizationId,
                ownerTenantId,
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

    /**
     * The organization-wide registry listing (issue #16, S3-04): every
     * application of the organization in both ownership scopes — the
     * administration surface the console renders. It exists because a
     * shared application with zero tenant authorizations is invisible to
     * every effective-visibility listing by construction (PRD 15:
     * distribution decides), so the registry must offer organization
     * roles their own reachability into it. Caller permission
     * (app:applications:read at the organization) is enforced at the
     * controller; the declared guard query (ADR-0002 fifth amendment)
     * is organization-addressed, so the seam cannot serve it in either
     * mode.
     */
    @Transactional(readOnly = true)
    public Page<ApplicationView> listByOrganization(UUID organizationId, long offset, int limit) {
        return registry.findByOrganizationIdOrderByCreatedAtAscIdAsc(organizationId, new CursorPageable(offset, limit))
                .map(ApplicationView::from);
    }

    // -------------------------------------------------------------------------
    // Metadata edits and lifecycle (AC3, AC4)
    // -------------------------------------------------------------------------

    /**
     * Partial update (AC3 + issue #14): null fields are left unchanged;
     * an empty description clears it. Identifier and platform edits
     * revalidate the per-platform coupling — removing a platform retires
     * its identifier — and every changed field, identifiers included, is
     * audited with before/after pairs (identifier changes affect
     * artifact matching, S5-05). The status field carries the reversible
     * ACTIVE/ARCHIVED transition; V1's DISABLED label stays
     * reserved-unused. Since S16-15 the same PATCH carries the OTA
     * opt-in toggle (V55's {@code ota_enabled}): a present boolean
     * flips the gate the otabundle module's authoring guard and the
     * device-check resolution read through the profile tuple — audited
     * with before/after pairs exactly like every sibling field. A
     * changed {@code ownershipScope} carries the
     * audited scope transition (see {@link #transitionOf}), executed
     * after the metadata edit inside the same request.
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
            String status,
            Boolean otaEnabled,
            String ownershipScope,
            UUID targetTenantId) {
        ApplicationEntity application = load(applicationId);
        UUID organizationId = application.getOrganizationId();
        ScopeTransition transition = transitionOf(application, ownershipScope, targetTenantId);
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
        if (otaEnabled != null && otaEnabled != application.isOtaEnabled()) {
            putChange(changes, "otaEnabled", String.valueOf(application.isOtaEnabled()), String.valueOf(otaEnabled));
            application.setOtaEnabled(otaEnabled);
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
        if (!changes.isEmpty()) {
            audit(
                    AuditEventType.APPLICATION_UPDATED,
                    actorId,
                    organizationId,
                    application.getTenantId(),
                    applicationId,
                    Map.copyOf(changes));
        }
        if (transition == null) {
            return ApplicationView.from(application);
        }
        return transitionScope(actorId, application, transition);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /**
     * The registry view (PRD 8.3 properties minus versions, which are E4).
     * Since S16-15 it carries the OTA opt-in flag (V55's
     * {@code ota_enabled}) beside the lifecycle status — the console's
     * toggle renders from it and PATCHes it back.
     */
    public record ApplicationView(
            UUID id,
            UUID organizationId,
            UUID tenantId,
            String ownershipScope,
            String name,
            String description,
            List<String> platforms,
            String bundleId,
            String packageName,
            String status,
            boolean otaEnabled,
            Instant createdAt,
            Instant updatedAt) {

        static ApplicationView from(ApplicationEntity application) {
            return new ApplicationView(
                    application.getId(),
                    application.getOrganizationId(),
                    application.getTenantId(),
                    application.getOwnershipScope().name(),
                    application.getName(),
                    application.getDescription(),
                    application.getPlatforms(),
                    application.getBundleId(),
                    application.getPackageName(),
                    application.getStatus().name(),
                    application.isOtaEnabled(),
                    application.getCreatedAt(),
                    application.getUpdatedAt());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: loading, coupling, validation, conflicts
    // -------------------------------------------------------------------------

    /**
     * Loads through the seam inside the route's bound context: the route
     * interceptor resolves the application's ownership and binds the
     * owning tenant's context for TENANT rows or the owning
     * organization's for ORGANIZATION rows (issue #14), and the seam
     * composes the matching predicate. An application outside the bound
     * scope — another tenant's, another organization's shared row, or an
     * unknown id — is indistinguishable from a miss: the standard
     * concealed miss (PRD 12).
     */
    private ApplicationEntity load(UUID applicationId) {
        // The platform-administrator wildcard passes the route's
        // permission check for an UNKNOWN application before the
        // application interceptor has anything to bind, and the seam is
        // fail-closed — the empty context is the concealed miss, never
        // a 500 (the tester-groups mustFindGroup precedent, S7-05
        // issue #34 folding the #27 candidate).
        if (TenantContext.current().isEmpty()) {
            throw new ApplicationException(ApplicationException.Code.NOT_FOUND, "application not found");
        }
        return applications
                .findScoped(applicationId)
                .orElseThrow(
                        () -> new ApplicationException(ApplicationException.Code.NOT_FOUND, "application not found"));
    }

    /**
     * The requested ownership-scope transition of a PATCH (issue #14,
     * AC3), or null when the request changes nothing about ownership:
     * an absent {@code ownershipScope} with an absent {@code tenantId}
     * is a metadata-only edit, and a restated current scope is a no-op.
     * The rules:
     *
     * <ul>
     *   <li>{@code tenantId} without a scope change is rejected —
     *       re-owning between tenants is not a registry operation
     *       (distribution, PRD 15, decides reachability, never the
     *       other way around).</li>
     *   <li>ORGANIZATION to TENANT requires the new owner tenant and
     *       enforces INV-002's service check: the tenant must exist,
     *       belong to the application's organization (a foreign
     *       organization's tenant is the same miss as an unknown id),
     *       and accept new affiliations. Precondition: blocked while
     *       any tenant holds an ACTIVE authorization on the shared
     *       application (S3-03's mapping — {@code 409
     *       APPLICATION_AUTHORIZED_TENANTS_PRESENT}); revoked history
     *       grants nothing and does not block.</li>
     *   <li>TENANT to ORGANIZATION carries no tenant. Precondition:
     *       zero distributions — none can exist until M2's release
     *       targets; the same declared point.</li>
     * </ul>
     */
    private ScopeTransition transitionOf(ApplicationEntity application, String ownershipScope, UUID targetTenantId) {
        if (ownershipScope == null && targetTenantId == null) {
            return null;
        }
        OwnershipScope requested = parseScope(ownershipScope);
        if (requested == application.getOwnershipScope()) {
            if (targetTenantId != null) {
                throw invalid("tenantId is only valid when converting an ORGANIZATION application to TENANT");
            }
            return null;
        }
        if (requested == OwnershipScope.TENANT) {
            if (targetTenantId == null) {
                throw invalid("tenantId is required when converting to TENANT ownership");
            }
            // INV-002 service check: the future owner must exist inside
            // the application's organization and accept affiliations.
            UUID ownerOrganization =
                    tenants.owningOrganizationId(targetTenantId).orElseThrow(() -> notFound("tenant not found"));
            if (!ownerOrganization.equals(application.getOrganizationId())) {
                throw notFound("tenant not found");
            }
            if (!tenants.acceptsNewAffiliations(targetTenantId)) {
                throw new ApplicationException(ApplicationException.Code.TENANT_DEACTIVATED, "tenant is deactivated");
            }
            // Precondition (issue #14 AC3, bound since S3-03): no tenant
            // may hold an ACTIVE authorization on the shared
            // application — a converted application would strand grants
            // that reference a scope the row no longer has. Revoked
            // history does not block (it grants nothing).
            if (authorizations.existsByApplicationIdAndStatus(
                    application.getId(), TenantApplicationAuthorizationEntity.Status.ACTIVE)) {
                throw new ApplicationException(
                        ApplicationException.Code.AUTHORIZED_TENANTS_PRESENT,
                        "the application has authorized tenants; de-authorize them before converting ownership");
            }
            // Precondition (issue #14 AC3's zero-distributions rule,
            // binding since S9-01/issue #55): releases of the shared
            // application targeted its authorized tenants — a conversion
            // would strand the target rows in a scope the seam no longer
            // binds for the application's routes. Any release blocks,
            // in any lifecycle state: releases are distribution history
            // and are never deleted.
            if (releases.hasReleases(application.getId())) {
                throw new ApplicationException(
                        ApplicationException.Code.RELEASES_PRESENT,
                        "the application has releases; ownership cannot be converted while any exist");
            }
            return new ScopeTransition(OwnershipScope.TENANT, targetTenantId);
        }
        if (targetTenantId != null) {
            throw invalid("tenantId is not valid when converting to ORGANIZATION ownership");
        }
        // Precondition (issue #14 AC3): zero distributions may anchor the
        // application to its tenant — binding since S9-01 (issue #55):
        // a release of a tenant-owned application targeted its owning
        // tenant's scope, and a conversion would strand the release rows
        // outside the seam the application's routes bind. Any release
        // blocks, in any lifecycle state (releases are never deleted).
        if (releases.hasReleases(application.getId())) {
            throw new ApplicationException(
                    ApplicationException.Code.RELEASES_PRESENT,
                    "the application has releases; ownership cannot be converted while any exist");
        }
        return new ScopeTransition(OwnershipScope.ORGANIZATION, null);
    }

    /**
     * Executes the sanctioned transition (ADR-0002 third amendment) and
     * audits it as {@code APPLICATION_SCOPE_CHANGED} with before/after
     * pairs (and, since issue #17, the count of versions carried along).
     * The write itself is the registry guard's single declared
     * cross-scope update: the row moves between scopes, so the request's
     * bound scope (either mode) cannot own both sides, and the service
     * has already run every precondition. Since versions exist (E4,
     * issue #17), the application's version rows carry the same
     * owning-dimension snapshot and move in the same transaction through
     * the version module's sanctioned carry-along — a version left in
     * the previous scope would strand outside the seam the routes bind.
     */
    private ApplicationView transitionScope(UUID actorId, ApplicationEntity application, ScopeTransition transition) {
        OwnershipScope before = application.getOwnershipScope();
        UUID beforeTenantId = application.getTenantId();
        int moved = registry.transitionScope(
                application.getId(), transition.to(), transition.tenantId(), Instant.now(clock));
        if (moved != 1) {
            throw notFound("application not found");
        }
        // Write-through (S7-03, AC2): a scope transition rewrites who
        // can see the application by ownership — the OLD owner loses
        // by-ownership visibility (TENANT -> ORGANIZATION) and a NEW
        // owner gains it (ORGANIZATION -> TENANT). Both cached
        // visibility flags drop inside this transaction so the next
        // eligibility read reflects the new scope, not a stale flag.
        if (beforeTenantId != null) {
            decisionCache.invalidateApplicationVisibility(application.getId(), beforeTenantId);
        }
        if (transition.tenantId() != null) {
            decisionCache.invalidateApplicationVisibility(application.getId(), transition.tenantId());
        }
        // The application's versions carry the same owning-dimension
        // snapshot (issue #17): they must move in lockstep with the row,
        // or they strand in a scope the seam no longer serves for the
        // application's routes — invisible to every later request. The
        // version module's sanctioned carry-along update (the same
        // cross-scope-write pattern as the transition itself) runs in
        // this transaction; E5's artifacts anchor to versions, not to
        // the application, so nothing else follows.
        int versionsCarried =
                versions.reassignOwnership(application.getId(), transition.tenantId(), Instant.now(clock));
        Map<String, String> detail = new LinkedHashMap<>();
        putChange(detail, "ownershipScope", before.name(), transition.to().name());
        putChange(detail, "tenantId", beforeTenantId, transition.tenantId());
        detail.put("versionsCarried", String.valueOf(versionsCarried));
        audit(
                AuditEventType.APPLICATION_SCOPE_CHANGED,
                actorId,
                application.getOrganizationId(),
                transition.tenantId(),
                application.getId(),
                Map.copyOf(detail));
        // The bulk update bypasses the persistence context; the view
        // reflects the transitioned row without mutating the managed
        // entity (an unrequested dirty write would re-cross the scopes
        // the seam just refused).
        return new ApplicationView(
                application.getId(),
                application.getOrganizationId(),
                transition.tenantId(),
                transition.to().name(),
                application.getName(),
                application.getDescription(),
                application.getPlatforms(),
                application.getBundleId(),
                application.getPackageName(),
                application.getStatus().name(),
                application.isOtaEnabled(),
                application.getCreatedAt(),
                Instant.now(clock));
    }

    /** The parsed transition request: the target scope and owner tenant. */
    private record ScopeTransition(OwnershipScope to, UUID tenantId) {}

    /** Ownership scope of a request; absent means TENANT (S3-01 clients). */
    private static OwnershipScope parseScope(String ownershipScope) {
        if (ownershipScope == null || ownershipScope.isBlank()) {
            return OwnershipScope.TENANT;
        }
        String normalized = ownershipScope.trim().toUpperCase(Locale.ROOT);
        if ("TENANT".equals(normalized) || "ORGANIZATION".equals(normalized)) {
            return OwnershipScope.valueOf(normalized);
        }
        throw invalid("ownershipScope must be TENANT or ORGANIZATION");
    }

    private static ApplicationException notFound(String message) {
        return new ApplicationException(ApplicationException.Code.NOT_FOUND, message);
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

    /** Before/after pair for id-valued audit detail; empty means unset. */
    private static void putChange(Map<String, String> changes, String field, UUID before, UUID after) {
        putChange(changes, field, before == null ? null : before.toString(), after == null ? null : after.toString());
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
