package com.shipperdog.api.authorization.internal.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.AuthorizationGrantException;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The deterministic mapping proof of the grant-create triple race
 * (issue #34, folding the #33 review finding): when the loser of the
 * unique-identity race re-derives the winner and finds NO active row —
 * because the winner was revoked between the constraint firing and the
 * recovery lookup (the V22 partial index scopes identity to ACTIVE
 * rows) — the answer is the STABLE 409 AUTHORIZATION_GRANT_CONFLICT,
 * never a raw DataIntegrityViolationException (a 500). The e2e
 * probabilistic race (two creates racing one revoke) lives in the
 * isolation suite; this unit pins the mapping deterministically.
 */
@Tag("isolation-suite")
class VersionAccessGrantServiceCreateRaceTest {

    @Test
    void aConstraintRecoveryThatFindsNoWinnerAnswersConflictNotARaw500() {
        UUID tenantId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID testerId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        VersionAccessGrantScopedRepository grants = mock(VersionAccessGrantScopedRepository.class);
        VersionAccessGrantCatalog catalog = mock(VersionAccessGrantCatalog.class);
        TenantAccess tenants = mock(TenantAccess.class);
        TesterAccess testers = mock(TesterAccess.class);
        TenantTesterMembershipAccess memberships = mock(TenantTesterMembershipAccess.class);
        ApplicationAccess applications = mock(ApplicationAccess.class);
        VersionAccess versions = mock(VersionAccess.class);

        when(tenants.owningOrganizationId(tenantId)).thenReturn(Optional.of(organizationId));
        when(tenants.acceptsNewAffiliations(tenantId)).thenReturn(true);
        when(testers.owningOrganizationOf(testerId)).thenReturn(Optional.of(organizationId));
        when(memberships.membershipExists(tenantId, testerId)).thenReturn(true);
        when(applications.visibleToTenant(applicationId, tenantId)).thenReturn(true);
        when(versions.stateOf(versionId))
                .thenReturn(Optional.of(new VersionState(versionId, applicationId, organizationId, tenantId, "DRAFT")));
        // The pre-check and the recovery both find nothing ACTIVE: the
        // winner existed at insert time (the constraint fired) and was
        // revoked before the recovery looked.
        when(catalog.findByTenantIdAndTesterIdAndApplicationId(tenantId, testerId, applicationId))
                .thenReturn(List.of());
        when(grants.saveScoped(any(VersionAccessGrantEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_version_access_grant"));

        VersionAccessGrantService service = new VersionAccessGrantService(
                grants,
                catalog,
                tenants,
                testers,
                memberships,
                applications,
                versions,
                mock(AuthorizationDecisionCache.class),
                mock(AuditLogger.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.create(
                        UUID.randomUUID(),
                        tenantId,
                        new VersionAccessGrantService.CreateCommand(
                                testerId,
                                applicationId,
                                "EXPLICIT_VERSION",
                                versionId,
                                null,
                                null,
                                Instant.parse("2027-01-01T00:00:00Z"),
                                "race probe")))
                .isInstanceOf(AuthorizationGrantException.class)
                .isNotInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(
                                ((AuthorizationGrantException) e).problem().getStatus())
                        .isEqualTo(409))
                .hasMessageContaining("concurrent");
    }
}
