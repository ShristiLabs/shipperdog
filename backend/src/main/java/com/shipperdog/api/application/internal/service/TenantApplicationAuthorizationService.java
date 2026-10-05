package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.ApplicationException;
import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.entity.ApplicationEntity.OwnershipScope;
import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.application.internal.repository.ApplicationScopedRepository;
import com.shipperdog.api.application.internal.repository.TenantApplicationAuthorizationRepository;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.tenant.TenantAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared-application tenant authorization mapping (issue #15, S3-03,
 * PRD 15, INV-005/INV-007): which tenants an organization-shared
 * application is authorized for — the {@code Banking 7.12.0 -> Acme YES,
 * Contoso YES, Delta NO} list. Grants and revocations are explicit,
 * audited with the request's reason (AC5), and modeled as one retained
 * row per application x tenant pair whose status flips (never a
 * deletion); the immutable audit trail keeps the full history with actor
 * and reason, the row keeps the current state.
 *
 * <p>Visibility is opt-in by construction: nothing changes for a tenant
 * until an organization administrator or application owner explicitly
 * authorizes it (INV-005 explicit authorization; the mapping is one
 * input layer of the E7 chain, PRD 11/12 — never duplicated here). The
 * effect of the status is resolved in exactly one place, the
 * effective-visibility query of {@link TenantApplicationService} (AC4);
 * de-authorization therefore hides the application and everything under
 * it from that tenant immediately — the same transaction flips the row
 * the resolution reads (AC3; downloads, versions, and artifacts bind
 * their own guards to the same mapping when they arrive, E4/E5/E7).
 *
 * <p>Tenant isolation (INV-001, ADR-0002): the application is loaded
 * through the seam inside the route's bound scope (the interceptor binds
 * the owning organization's scope for shared applications), so another
 * organization's application is indistinguishable from an unknown id —
 * the standard concealed miss (PRD 12), the same shape an unauthorized
 * caller sees. The mapping rows themselves are cross-tenant
 * authorization data served by the marked repository (ADR-0002 fourth
 * amendment); the tenant checks (existence, affiliation to the
 * application's organization, deactivation) run before any write,
 * mirroring the registry's INV-002 service checks.
 */
@Service
public class TenantApplicationAuthorizationService {

    private static final int REASON_MAX = 500;

    private final ApplicationScopedRepository applications;
    private final TenantApplicationAuthorizationRepository authorizations;
    private final TenantAccess tenants;
    private final AuditEventPublisher audit;
    private final Clock clock;

    TenantApplicationAuthorizationService(
            ApplicationScopedRepository applications,
            TenantApplicationAuthorizationRepository authorizations,
            TenantAccess tenants,
            AuditEventPublisher audit,
            Clock clock) {
        this.applications = applications;
        this.authorizations = authorizations;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // AC1: authorize (POST) and de-authorize (DELETE, soft)
    // -------------------------------------------------------------------------

    /**
     * Authorizes a tenant for a shared application (AC1). Idempotent per
     * the membership precedent: authorizing an already-authorized tenant
     * returns the existing grant unchanged — nothing is written or
     * audited (no change happened). A revoked pair is re-granted: the
     * conditional state transition flips the row back to ACTIVE with
     * the new grant stamped, audited as a fresh grant — and exactly one
     * concurrent re-grant wins the flip; the rest resolve as idempotent
     * successes against the winner's row. The first grant creates the
     * row. The mapping applies to ORGANIZATION-shared applications only
     * — a TENANT-owned application is visible to its tenant by ownership
     * (PRD 14), so authorizing another tenant for it is an invalid
     * request, not a miss. The tenant must exist inside the
     * application's organization (a foreign organization's tenant is
     * the same miss as an unknown id) and accept new affiliations (a
     * deactivation-era grant is the registry's affiliation rule carried
     * over).
     */
    @Transactional
    public AuthorizationGrant authorize(UUID actorId, UUID applicationId, UUID tenantId, String reason) {
        ApplicationEntity application = load(applicationId);
        requireShared(application);
        requireAuthorizableTenant(application, tenantId);
        Instant now = Instant.now(clock);
        TenantApplicationAuthorizationEntity existing = authorizations
                .findByApplicationIdAndTenantId(applicationId, tenantId)
                .orElse(null);
        if (existing != null) {
            if (existing.getStatus() == TenantApplicationAuthorizationEntity.Status.ACTIVE) {
                // Idempotent duplicate: the state already holds.
                return new AuthorizationGrant(AuthorizationView.from(existing), false);
            }
            int moved = authorizations.regrantIfRevoked(
                    existing.getId(),
                    actorId,
                    now,
                    TenantApplicationAuthorizationEntity.Status.ACTIVE,
                    TenantApplicationAuthorizationEntity.Status.REVOKED);
            if (moved == 1) {
                // The flip won: audited as a re-grant; the view carries
                // the transition's known post-state (the bulk update
                // bypasses the persistence context).
                AuthorizationView view =
                        new AuthorizationView(applicationId, tenantId, "ACTIVE", actorId, now, null, null);
                auditAuthorization(
                        AuditEventType.APPLICATION_TENANT_AUTHORIZED, actorId, application, view, reason, true);
                return new AuthorizationGrant(view, false);
            }
            // A concurrent re-grant won the flip: the row now reads
            // ACTIVE — an idempotent success, nothing audited here. The
            // conditional update cleared the persistence context, so the
            // re-read is the committed row (truthful stamps, not a
            // projection of the caller's request).
            TenantApplicationAuthorizationEntity winnerRow = authorizations
                    .findByApplicationIdAndTenantId(applicationId, tenantId)
                    .orElseThrow(() ->
                            new ApplicationException(ApplicationException.Code.NOT_FOUND, "authorization not found"));
            return new AuthorizationGrant(AuthorizationView.from(winnerRow), false);
        }
        TenantApplicationAuthorizationEntity grant =
                new TenantApplicationAuthorizationEntity(applicationId, tenantId, actorId, now);
        TenantApplicationAuthorizationEntity saved;
        try {
            saved = authorizations.save(grant);
            authorizations.flush();
        } catch (DataIntegrityViolationException e) {
            // Lost the race on the pair's unique row: the winner's row
            // decides the outcome — idempotent success when already
            // granted, otherwise the re-grant flip.
            TenantApplicationAuthorizationEntity winner = authorizations
                    .findByApplicationIdAndTenantId(applicationId, tenantId)
                    .orElse(null);
            if (winner == null || winner.getStatus() != TenantApplicationAuthorizationEntity.Status.ACTIVE) {
                throw e;
            }
            return new AuthorizationGrant(AuthorizationView.from(winner), false);
        }
        auditAuthorization(
                AuditEventType.APPLICATION_TENANT_AUTHORIZED,
                actorId,
                application,
                AuthorizationView.from(saved),
                reason,
                false);
        return new AuthorizationGrant(AuthorizationView.from(saved), true);
    }

    /**
     * De-authorizes a tenant (AC1's DELETE): the soft flip — the row is
     * retained with its revocation stamped, never deleted, and audited
     * with the request's reason (AC5). The flip is a conditional state
     * transition (ACTIVE to REVOKED): exactly one concurrent revocation
     * wins and is audited; an absent, already-revoked, or
     * concurrently-revoked authorization is the standard concealed miss
     * — one event per real transition. The effect is immediate (AC3):
     * the effective-visibility resolution reads the status in the same
     * transaction, so the application — and with it every version and
     * artifact under it, once those surfaces exist — disappears from the
     * tenant's listing and blocks new downloads from this point on;
     * grants already delivered stay delivered (the INV-006 analog).
     */
    @Transactional
    public void deauthorize(UUID actorId, UUID applicationId, UUID tenantId, String reason) {
        ApplicationEntity application = load(applicationId);
        TenantApplicationAuthorizationEntity grant = authorizations
                .findByApplicationIdAndTenantId(applicationId, tenantId)
                .orElseThrow(
                        () -> new ApplicationException(ApplicationException.Code.NOT_FOUND, "authorization not found"));
        if (grant.getStatus() != TenantApplicationAuthorizationEntity.Status.ACTIVE) {
            throw new ApplicationException(ApplicationException.Code.NOT_FOUND, "authorization not found");
        }
        int moved = authorizations.revokeIfActive(
                grant.getId(),
                actorId,
                Instant.now(clock),
                TenantApplicationAuthorizationEntity.Status.ACTIVE,
                TenantApplicationAuthorizationEntity.Status.REVOKED);
        if (moved != 1) {
            // A concurrent revocation won the flip: the same miss the
            // already-revoked state produces.
            throw new ApplicationException(ApplicationException.Code.NOT_FOUND, "authorization not found");
        }
        auditAuthorization(
                AuditEventType.APPLICATION_TENANT_DEAUTHORIZED,
                actorId,
                application,
                new AuthorizationView(
                        applicationId,
                        tenantId,
                        "REVOKED",
                        grant.getGrantedBy(),
                        grant.getGrantedAt(),
                        actorId,
                        Instant.now(clock)),
                reason,
                false);
    }

    // -------------------------------------------------------------------------
    // The authorized-tenant list (PRD 15's view)
    // -------------------------------------------------------------------------

    /**
     * Lists the tenants currently authorized for the application
     * (PRD 15's list): ACTIVE grants in the platform listing order,
     * paged. Revoked grants are historical state and never listed —
     * their history lives in the audit trail.
     */
    @Transactional(readOnly = true)
    public Page<AuthorizationView> list(UUID applicationId, long offset, int limit) {
        load(applicationId);
        return authorizations
                .findByApplicationIdAndStatus(
                        applicationId, TenantApplicationAuthorizationEntity.Status.ACTIVE, listPageable(offset, limit))
                .map(AuthorizationView::from);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The mapping view: the pair, its current state, and its stamps. */
    public record AuthorizationView(
            UUID applicationId,
            UUID tenantId,
            String status,
            UUID grantedBy,
            Instant grantedAt,
            UUID revokedBy,
            Instant revokedAt) {

        static AuthorizationView from(TenantApplicationAuthorizationEntity grant) {
            return new AuthorizationView(
                    grant.getApplicationId(),
                    grant.getTenantId(),
                    grant.getStatus().name(),
                    grant.getGrantedBy(),
                    grant.getGrantedAt(),
                    grant.getRevokedBy(),
                    grant.getRevokedAt());
        }
    }

    /** The authorize outcome: the grant's view and whether this call created it. */
    public record AuthorizationGrant(AuthorizationView view, boolean created) {}

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Loads through the seam inside the route's bound scope: a shared
     * application's route binds the owning organization's scope, so
     * another organization's application — and an unknown id — are the
     * same concealed miss (PRD 12).
     */
    private ApplicationEntity load(UUID applicationId) {
        return applications
                .findScoped(applicationId)
                .orElseThrow(
                        () -> new ApplicationException(ApplicationException.Code.NOT_FOUND, "application not found"));
    }

    /** The mapping applies to shared applications only (PRD 14/15). */
    private static void requireShared(ApplicationEntity application) {
        if (application.getOwnershipScope() != OwnershipScope.ORGANIZATION) {
            throw new ApplicationException(
                    ApplicationException.Code.INVALID_REQUEST,
                    "tenant authorization applies to ORGANIZATION-shared applications only");
        }
    }

    /**
     * The tenant-side checks, mirroring the registry's INV-002 service
     * checks: existence and affiliation to the application's
     * organization (a foreign organization's tenant is the same miss as
     * an unknown id), then the affiliation rule — a deactivated tenant
     * accepts no new grants.
     */
    private void requireAuthorizableTenant(ApplicationEntity application, UUID tenantId) {
        UUID ownerOrganization = tenants.owningOrganizationId(tenantId).orElseThrow(() -> notFound("tenant not found"));
        if (!ownerOrganization.equals(application.getOrganizationId())) {
            throw notFound("tenant not found");
        }
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            throw new ApplicationException(ApplicationException.Code.TENANT_DEACTIVATED, "tenant is deactivated");
        }
    }

    /** Optional, bounded, trimmed; an absent or blank reason stays null. */
    public static String optionalReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > REASON_MAX) {
            throw new ApplicationException(
                    ApplicationException.Code.INVALID_REQUEST, "reason must be at most " + REASON_MAX + " characters");
        }
        return trimmed;
    }

    private static Pageable listPageable(long offset, int limit) {
        return new CursorPageable(
                offset,
                limit,
                Sort.by("grantedAt").ascending().and(Sort.by("id").ascending()));
    }

    private static ApplicationException notFound(String message) {
        return new ApplicationException(ApplicationException.Code.NOT_FOUND, message);
    }

    /**
     * Audits a grant or revocation (AC5): the event carries the
     * application, the owning organization, the authorized tenant, the
     * pair's resulting state, and the request's reason on the event's
     * reason field — the compliance-readable motivation (FR-024).
     */
    private void auditAuthorization(
            AuditEventType type,
            UUID actorId,
            ApplicationEntity application,
            AuthorizationView grant,
            String reason,
            boolean regrant) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("tenantId", grant.tenantId().toString());
        detail.put("status", grant.status());
        if (regrant) {
            detail.put("regrant", "true");
        }
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(application.getOrganizationId())
                .tenantId(grant.tenantId())
                .applicationId(application.getId())
                .success(true)
                .detail(detail)
                .reason(reason)
                .build());
    }
}
