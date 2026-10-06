package com.shipperdog.api.tenant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tester.TesterGroupAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Route coverage and lifecycle of the tester-group context binding
 * (issue #27): exactly the group-addressing routes resolve their
 * group's tenant, unknown groups bind nothing (the permission check
 * conceal-denies them), the platform-admin override comes only from
 * the platform-admin flag and is audited as CROSS_TENANT_ACCESS, and
 * the context is cleared after completion even when the handler threw
 * — so a reused worker thread never serves the next request with a
 * stale scope.
 */
class TesterGroupTenantContextInterceptorTest {

    private static final UUID GROUP = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID TENANT = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR = UUID.fromString("50000000-0000-4000-8000-000000000003");

    private final List<AuditEvent> published = new ArrayList<>();
    private TesterGroupTenantContextInterceptor interceptor;

    /** Whether the stub platform-admin check reports the actor as an admin. */
    private boolean actorIsPlatformAdmin;

    /** Whether the stub group registry resolves {@link #GROUP}. */
    private boolean groupExists = true;

    @BeforeEach
    void setUp() {
        published.clear();
        actorIsPlatformAdmin = false;
        groupExists = true;
        PlatformAdmins platformAdmins = userId -> actorIsPlatformAdmin;
        AuditLogger audit = new AuditLogger(published::add, Clock.systemUTC());
        TesterGroupAccess testerGroups =
                groupId -> groupExists && GROUP.equals(groupId) ? Optional.of(TENANT) : Optional.empty();
        interceptor = new TesterGroupTenantContextInterceptor(testerGroups, platformAdmins, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.end();
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindsTheGroupTenantForGroupAddressedRoutes() {
        for (String path : new String[] {
            "/api/v1/tester-groups/" + GROUP,
            "/api/v1/tester-groups/" + GROUP + "/members",
            "/api/v1/tester-groups/" + GROUP + "/members/" + ACTOR,
            "/api/v1/tester-groups/" + GROUP.toString().toUpperCase() + "/members"
        }) {
            TenantContext.end();
            assertThat(interceptor.preHandle(get(path), new MockHttpServletResponse(), new Object()))
                    .as("group-addressed route proceeds: %s", path)
                    .isTrue();
            TenantContext context = TenantContext.require();
            assertThat(context.tenantId())
                    .as("the group's tenant is bound for %s", path)
                    .isEqualTo(TENANT);
            assertThat(context.actorId()).isEqualTo(ACTOR);
            assertThat(context.crossTenantOverride()).isFalse();
        }
    }

    @Test
    void bindsNothingForCollectionRoutesAndForeignPaths() {
        for (String path : new String[] {
            "/api/v1/tester-groups",
            "/api/v1/tester-groups?tenantId=" + TENANT,
            "/api/v1/tester-groups?testerId=" + ACTOR,
            "/api/v1/tenants/" + TENANT + "/members",
            "/api/v1/testers/" + ACTOR
        }) {
            TenantContext.end();
            assertThat(interceptor.preHandle(get(path), new MockHttpServletResponse(), new Object()))
                    .as("non-group route proceeds unscoped: %s", path)
                    .isTrue();
            assertThat(TenantContext.current())
                    .as("no context is bound for %s (the service establishes it)", path)
                    .isEmpty();
        }
    }

    @Test
    void unknownGroupsBindNothing() {
        groupExists = false;
        assertThat(interceptor.preHandle(
                        get("/api/v1/tester-groups/" + GROUP), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.current())
                .as("an unknown group has no scope; the permission check conceal-denies it")
                .isEmpty();
    }

    @Test
    void platformAdminAdmissionsAreAuditedAsCrossTenantAccess() {
        actorIsPlatformAdmin = true;
        assertThat(interceptor.preHandle(
                        get("/api/v1/tester-groups/" + GROUP), new MockHttpServletResponse(), new Object()))
                .isTrue();
        TenantContext context = TenantContext.require();
        assertThat(context.crossTenantOverride())
                .as("the override derives from the platform-admin flag")
                .isTrue();
        assertThat(published).as("the admission is audited").hasSize(1);
        AuditEvent event = published.getFirst();
        assertThat(event.type()).isEqualTo(AuditEventType.CROSS_TENANT_ACCESS);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.detail()).containsEntry("uri", "/api/v1/tester-groups/" + GROUP);
    }

    @Test
    void clearsTheContextAfterCompletionOnEveryOutcome() {
        interceptor.preHandle(get("/api/v1/tester-groups/" + GROUP), new MockHttpServletResponse(), new Object());
        assertThat(TenantContext.current()).isPresent();
        interceptor.afterCompletion(
                get("/api/v1/tester-groups/" + GROUP),
                new MockHttpServletResponse(),
                new Object(),
                new IllegalStateException("handler threw"));
        assertThat(TenantContext.current())
                .as("the thread-local never outlives the request, even on failure")
                .isEmpty();
    }

    @Test
    void anonymousTrafficBindsNothing() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        // Built by hand: the get() helper would reset the security
        // context to the JWT principal before the interceptor runs.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tester-groups/" + GROUP);
        request.setRemoteAddr("127.0.0.1");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.current()).isEmpty();
    }

    private static HttpServletRequest get(String uri) {
        SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        Jwt.withTokenValue("test-token")
                                .header("alg", "none")
                                .subject(ACTOR.toString())
                                .build(),
                        null,
                        List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
