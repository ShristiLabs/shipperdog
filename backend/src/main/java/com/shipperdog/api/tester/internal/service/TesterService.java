package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AuthorizationDecisionInvalidation;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.identity.SessionRevocation;
import com.shipperdog.api.identity.TesterInvitations;
import com.shipperdog.api.notification.EmailSender;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.tester.TesterAccounts;
import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.TesterOnboarding;
import com.shipperdog.api.tester.internal.config.TesterProperties;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.repository.TesterRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tester onboarding and lifecycle (issue #26, S6-01). The tester record
 * is the organization-scoped person record; invitations reuse the
 * identity machinery through {@link TesterInvitations}, acceptance
 * reuses {@code POST /api/v1/auth/register} (identity calls back
 * through {@link TesterOnboarding}), and the invitation email goes out
 * through the notification seam ({@link EmailSender}) with the raw
 * accept link also returned to the inviting admin exactly once — the
 * S1-03 dev-mode delivery decision applied to tester onboarding.
 *
 * <p>Status model: INVITED (pre-account, created here, activated only
 * by the accept flow), ACTIVE (account linked), SUSPENDED (reversible
 * access park; blocks login and refresh), DISABLED (S6-04 deactivation
 * — the revocation posture: irreversible-by-default offboarding that
 * cascades membership suspension, kills refresh families, and requires
 * a mandatory audited reason). Transitions are enforced in
 * {@link #changeStatus} and audited; INVITED -> ACTIVE belongs to the
 * accept flow alone.
 */
@Service
public class TesterService implements TesterOnboarding, TesterAccounts, TesterAccess {

    private final TesterRepository testers;
    private final TesterInvitations invitations;
    private final OrganizationAccess organizations;
    private final TesterInvitationMailer mailer;
    private final TesterProperties properties;
    private final Clock clock;
    private final AuditEventPublisher audit;
    private final TesterConflictResolver conflicts;
    private final TesterCreationAttempt creation;
    private final AuthorizationDecisionInvalidation decisionCache;

    /**
     * Both resolved lazily like {@code OrganizationService}'s
     * SessionRevocation binding (the demotion precedent): TokenService
     * constructor-injects this service's {@link TesterAccounts} seam,
     * and the membership service's TenantAccess chain reaches back
     * around to identity, so direct constructor injections here would
     * close cycles Spring cannot satisfy.
     */
    private final ObjectProvider<TenantTesterMembershipService> memberships;

    private final ObjectProvider<SessionRevocation> sessions;

    TesterService(
            TesterRepository testers,
            TesterInvitations invitations,
            OrganizationAccess organizations,
            TesterInvitationMailer mailer,
            TesterProperties properties,
            Clock clock,
            AuditEventPublisher audit,
            TesterConflictResolver conflicts,
            TesterCreationAttempt creation,
            AuthorizationDecisionInvalidation decisionCache,
            ObjectProvider<TenantTesterMembershipService> memberships,
            ObjectProvider<SessionRevocation> sessions) {
        this.testers = testers;
        this.invitations = invitations;
        this.organizations = organizations;
        this.mailer = mailer;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.conflicts = conflicts;
        this.creation = creation;
        this.decisionCache = decisionCache;
        this.memberships = memberships;
        this.sessions = sessions;
    }

    /**
     * Idempotent tester creation (acceptance criterion 1): a fresh
     * address creates the INVITED tester record plus a single-use
     * invitation; a duplicate returns the existing tester (and its live
     * invitation state, never a second token) with {@code created=false}.
     *
     * <p>Deliberately NOT transactional: the write runs inside
     * {@link TesterCreationAttempt}'s own transaction, so a lost
     * uniqueness race rolls THAT back and is answered here with the
     * concurrent winner — a catch inside one wrapping transaction
     * would commit a PostgreSQL-aborted transaction and 500.
     */
    public CreatedTester create(UUID invitedBy, UUID organizationId, String rawEmail, String fullName) {
        requireOrganization(organizationId);
        String email = normalizeEmail(rawEmail);
        requireValidEmail(email);
        String name = requireFullName(fullName);
        Optional<TesterEntity> existing = testers.findByOrganizationIdAndEmail(organizationId, email);
        if (existing.isPresent()) {
            TesterEntity tester = existing.get();
            Map<String, Object> invitationState = invitations
                    .pendingForTester(tester.getId())
                    .map(pending -> Map.<String, Object>of(
                            "status",
                            "PENDING",
                            "expiresAt",
                            pending.expiresAt().toString()))
                    .orElse(null);
            return new CreatedTester(tester, false, invitationState);
        }
        try {
            return creation.attempt(invitedBy, organizationId, email, name);
        } catch (DataIntegrityViolationException e) {
            // A concurrent creation of the same address won the
            // uq_testers_organization_email guard; creation is idempotent,
            // so the winner IS the answer, re-derived on a fresh
            // transaction (this thread holds none open).
            return conflicts
                    .winnerOf(organizationId, email)
                    .map(this::existingResult)
                    .orElseThrow(() -> conflicts.unexpected(organizationId));
        }
    }

    /** The duplicate-invite answer: the existing tester and its live invitation state, never a token. */
    private CreatedTester existingResult(TesterConflictResolver.Existing winner) {
        Map<String, Object> invitationState = winner.pendingExpiresAt() == null
                ? null
                : Map.of(
                        "status",
                        "PENDING",
                        "expiresAt",
                        winner.pendingExpiresAt().toString());
        return new CreatedTester(winner.tester(), false, invitationState);
    }

    /**
     * Re-invite: revokes the tester's live invitation (if any) and mints
     * a fresh one. Only INVITED testers — an ACTIVE tester already owns
     * its account, and a SUSPENDED one must be reactivated instead.
     */
    @Transactional
    public InvitationView reinvite(UUID invitedBy, UUID testerId) {
        TesterEntity tester = requireTester(testerId);
        if (tester.getStatus() == TesterEntity.Status.ACTIVE) {
            throw new TesterException(TesterException.Code.ALREADY_ACTIVE, "tester has already accepted onboarding");
        }
        if (tester.getStatus() != TesterEntity.Status.INVITED) {
            throw new TesterException(
                    TesterException.Code.INVALID_TRANSITION,
                    "only INVITED testers can be re-invited (current: " + tester.getStatus() + ")");
        }
        TesterInvitations.Invitation invitation;
        try {
            invitation = invitations.mintForTester(
                    tester.getOrganizationId(),
                    tester.getId(),
                    tester.getEmail(),
                    invitedBy,
                    properties.invitationTtl());
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent re-invite on uq_user_invitations_pending_tester.
            // The catch rethrows (never returns normally: PostgreSQL has
            // aborted this transaction), so the rollback is clean and the
            // client gets a retryable 409 instead of a commit-time 500.
            throw new TesterException(
                    TesterException.Code.INVITATION_IN_FLIGHT,
                    "a concurrent invitation for this tester is in flight; retry");
        }
        mailer.sendInvitation(tester.getEmail(), tester.getFullName(), invitation.rawToken(), invitation.expiresAt());
        audit(
                AuditEventType.TESTER_INVITED,
                invitedBy,
                tester.getOrganizationId(),
                tester.getId(),
                true,
                detail(tester, true));
        return new InvitationView(
                "PENDING", invitation.rawToken(), mailer.acceptUrl(invitation.rawToken()), invitation.expiresAt());
    }

    /**
     * Status lifecycle (issue #26 criterion 4; issue #29 revocation):
     * ACTIVE <-> SUSPENDED is the reversible park switch; any of
     * ACTIVE/SUSPENDED/INVITED -> DISABLED is DEACTIVATION — the
     * revocation posture (FR-010, INV-007), which demands a reason,
     * cascades every ACTIVE membership to SUSPENDED (retained, never
     * deleted), and revokes the linked account's refresh families so
     * no existing session outlives the decision. DISABLED -> ACTIVE is
     * the explicit re-enable: only for testers that own an account, and
     * memberships stay SUSPENDED — restoration is a manual per-tenant
     * re-enable, never silent. INVITED -> ACTIVE belongs to the accept
     * flow alone.
     */
    @Transactional
    public TesterEntity changeStatus(UUID actor, UUID testerId, TesterEntity.Status target, String reason) {
        TesterEntity tester = requireTester(testerId);
        TesterEntity.Status before = tester.getStatus();
        boolean allowed = (before == TesterEntity.Status.ACTIVE && target == TesterEntity.Status.SUSPENDED)
                || (before == TesterEntity.Status.SUSPENDED && target == TesterEntity.Status.ACTIVE)
                || target == TesterEntity.Status.DISABLED
                || (before == TesterEntity.Status.DISABLED
                        && target == TesterEntity.Status.ACTIVE
                        && tester.getUserId() != null);
        if (!allowed) {
            throw new TesterException(
                    TesterException.Code.INVALID_TRANSITION,
                    "transition " + before + " -> " + target + " is not allowed"
                            + (before == TesterEntity.Status.DISABLED && target == TesterEntity.Status.ACTIVE
                                    ? " (a tester that never accepted onboarding cannot be reactivated)"
                                    : ""));
        }
        String cleanReason = normalizeReason(reason);
        if (target == TesterEntity.Status.DISABLED && cleanReason == null) {
            // FR-024: deactivation is the emergency/offboarding gesture;
            // the audit trail must say why.
            throw new TesterException(
                    TesterException.Code.INVALID_REQUEST,
                    "a reason (1-500 characters) is required to deactivate a tester");
        }
        if (cleanReason != null && cleanReason.length() > 500) {
            throw new TesterException(
                    TesterException.Code.INVALID_REQUEST, "the lifecycle reason exceeds 500 characters");
        }
        tester.setStatus(target);
        TesterEntity saved = testers.save(tester);
        // Write-through (S7-03, AC2): the person switch flips the
        // two-switches rule for every retained membership row, and the
        // DISABLED cascade below suspends the ACTIVE ones besides —
        // drop the membership flags for every linked tenant before
        // this transaction's state becomes visible, so the next
        // decision after the commit sees the new person state.
        decisionCache.invalidateMembershipsOfTester(
                testerId, memberships.getObject().tenantIdsOfTester(testerId));
        Map<String, String> change = new LinkedHashMap<>();
        change.put("from", before.name());
        change.put("to", target.name());
        if (cleanReason != null) {
            change.put("reason", cleanReason);
        }
        AuditEventType type = AuditEventType.TESTER_STATUS_CHANGED;
        if (target == TesterEntity.Status.DISABLED) {
            type = AuditEventType.TESTER_DEACTIVATED;
            int suspended = memberships.getObject().suspendForTesterDeactivation(actor, testerId, cleanReason);
            change.put("suspendedMemberships", Integer.toString(suspended));
            if (tester.getUserId() != null) {
                // The demotion/SessionRevocation discipline (S1-05):
                // deactivation must not be outlived by sessions issued
                // before it.
                sessions.getObject().revokeRefreshTokens(tester.getUserId(), "tester_deactivated");
                change.put("refreshFamiliesRevoked", "true");
            }
        } else if (before == TesterEntity.Status.DISABLED) {
            type = AuditEventType.TESTER_REACTIVATED;
        }
        audit.publish(AuditEvent.builder(type)
                .userId(actor)
                .orgId(tester.getOrganizationId())
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(change)
                .after("status=" + target.name())
                .build());
        return saved;
    }

    /** Single tester read; misses render as the concealed 404. */
    @Transactional(readOnly = true)
    public TesterEntity get(UUID testerId) {
        return requireTester(testerId);
    }

    /** Organization-scoped listing (filterable by status). */
    @Transactional(readOnly = true)
    public Page<TesterEntity> list(UUID organizationId, String status, long offset, int limit) {
        requireOrganization(organizationId);
        Pageable pageable = new CursorPageable((int) offset, limit);
        if (status == null || status.isBlank()) {
            return testers.findByOrganizationIdOrderByEmailAsc(organizationId, pageable);
        }
        return testers.findByOrganizationIdAndStatusOrderByEmailAsc(organizationId, parseStatus(status), pageable);
    }

    /** Accept-flow callback from identity (see {@link TesterOnboarding}). */
    @Override
    @Transactional
    public void onInvitationAccepted(UUID testerId, UUID userId, UUID invitedBy) {
        TesterEntity tester = testers.findById(testerId).orElse(null);
        if (tester == null || tester.getStatus() != TesterEntity.Status.INVITED || tester.getUserId() != null) {
            throw new NotOnboardingException("tester record is not awaiting onboarding");
        }
        tester.setUserId(userId);
        tester.setStatus(TesterEntity.Status.ACTIVE);
        testers.save(tester);
        audit(
                AuditEventType.TESTER_ACTIVATED,
                invitedBy,
                tester.getOrganizationId(),
                tester.getId(),
                true,
                detail(tester, false));
    }

    /**
     * Suspended and deactivated testers block login, refresh, and PAT
     * use (uniform 401 / null authentication upstream); the block kind
     * distinguishes the audit reason only.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<TesterAccounts.Block> loginBlockFor(UUID userId) {
        return testers.findByUserId(userId).flatMap(tester -> switch (tester.getStatus()) {
            case SUSPENDED -> Optional.of(TesterAccounts.Block.SUSPENDED);
            case DISABLED -> Optional.of(TesterAccounts.Block.DEACTIVATED);
            default -> Optional.empty();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> owningOrganizationOf(UUID testerId) {
        return testers.findById(testerId).map(TesterEntity::getOrganizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> testerIdOfUser(UUID userId) {
        return testers.findByUserId(userId).map(TesterEntity::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TesterAccess.TesterRecord> recordsOf(Collection<UUID> testerIds) {
        if (testerIds.isEmpty()) {
            return Map.of();
        }
        return testers.findAllById(testerIds).stream()
                .collect(Collectors.toMap(
                        TesterEntity::getId,
                        tester -> new TesterAccess.TesterRecord(
                                tester.getId(),
                                tester.getEmail(),
                                tester.getFullName(),
                                tester.getStatus().name())));
    }

    private TesterEntity requireTester(UUID testerId) {
        return testers.findById(testerId)
                .orElseThrow(() -> new TesterException(TesterException.Code.NOT_FOUND, "tester not found"));
    }

    private void requireOrganization(UUID organizationId) {
        if (organizationId == null || !organizations.organizationExists(organizationId)) {
            throw new TesterException(TesterException.Code.NOT_FOUND, "organization not found");
        }
    }

    private static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireValidEmail(String email) {
        if (email.isEmpty() || email.length() > 255 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "a valid email address is required");
        }
    }

    private static String requireFullName(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        if (name.isEmpty() || name.length() > 200) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "fullName is required (max 200 chars)");
        }
        return name;
    }

    /** Audit-grade reason: trimmed; null when absent or blank. Length is capped at 500 for every transition. */
    private static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String clean = reason.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static TesterEntity.Status parseStatus(String status) {
        try {
            return TesterEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "unknown status: " + status.trim());
        }
    }

    private void audit(
            AuditEventType type,
            UUID actor,
            UUID organizationId,
            UUID testerId,
            boolean success,
            Map<String, String> detail) {
        Map<String, String> enriched = new LinkedHashMap<>(detail);
        enriched.put("testerId", testerId.toString());
        audit.publish(AuditEvent.builder(type)
                .userId(actor)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(success)
                .detail(enriched)
                .build());
    }

    private static Map<String, String> detail(TesterEntity tester, boolean reinvited) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("email", tester.getEmail());
        if (reinvited) {
            detail.put("reinvited", "true");
        }
        return detail;
    }

    /** Create result; the invitation block carries the once-shown token. */
    public record CreatedTester(TesterEntity tester, boolean created, Map<String, Object> invitation) {}

    /** Re-invite result; {@code invitationToken} is shown exactly once. */
    public record InvitationView(String status, String invitationToken, String acceptUrl, Instant expiresAt) {}
}
