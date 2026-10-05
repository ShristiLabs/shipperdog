package com.shipperdog.api.tenant.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantIsolationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
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
 * Route coverage and lifecycle of the tenant-context binding (issue #10):
 * exactly the tenant-addressing routes get a context, every authenticated
 * principal form resolves its actor, the platform-admin override comes
 * only from the platform-admin flag (organization roles can never raise
 * it or its audit), and the context is cleared after completion even
 * when the handler threw — so a reused worker thread never serves the
 * next request with a stale scope.
 */
class TenantContextInterceptorTest {

    private static final UUID TENANT = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("40000000-0000-4000-8000-000000000002");

    private final List<AuditEvent> published = new ArrayList<>();
    private TenantContextInterceptor interceptor;

    /** Whether the stub platform-admin check reports the actor as an admin. */
    private boolean actorIsPlatformAdmin;

    @BeforeEach
    void setUp() {
        published.clear();
        actorIsPlatformAdmin = false;
        PlatformAdmins platformAdmins = userId -> actorIsPlatformAdmin;
        AuditLogger audit = new AuditLogger(published::add, Clock.systemUTC());
        interceptor = new TenantContextInterceptor(platformAdmins, audit);
    }

    @AfterEach
    void tearDown() {
        TenantContext.end();
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindsTheRouteTenantAndActorForTenantAddressedRoutes() {
        for (String path : new String[] {
            "/api/v1/tenants/" + TENANT,
            "/api/v1/tenants/" + TENANT + "/members",
            "/api/v1/tenants/" + TENANT + "/members/" + ACTOR + "/role",
            "/api/v1/tenants/" + TENANT.toString().toUpperCase() + "/applications"
        }) {
            TenantContext.end();
            assertThat(interceptor.preHandle(get(path), new MockHttpServletResponse(), new Object()))
                    .as("tenant-addressed route proceeds: %s", path)
                    .isTrue();
            TenantContext context = TenantContext.require();
            assertThat(context.tenantId()).as("route tenant bound for %s", path).isEqualTo(TENANT);
            assertThat(context.actorId()).isEqualTo(ACTOR);
            assertThat(context.crossTenantOverride()).isFalse();
        }
    }

    @Test
    void doesNotBindOutsideTenantAddressedRoutes() {
        for (String path : new String[] {
            "/api/v1/tenants",
            "/api/v1/tenants/not-a-uuid",
            "/api/v1/tenants/" + TENANT + "x/members",
            "/api/v1/organizations/" + TENANT,
            "/api/v1/users/me",
            "/actuator/health"
        }) {
            assertThat(interceptor.preHandle(get(path), new MockHttpServletResponse(), new Object()))
                    .as("non-tenant route proceeds unbound: %s", path)
                    .isTrue();
            assertThat(TenantContext.current())
                    .as("no context may be bound for %s", path)
                    .isEmpty();
        }
    }

    @Test
    void contextPathIsStrippedBeforeRouteMatching() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwtOf(ACTOR)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/srv/api/v1/tenants/" + TENANT);
        request.setContextPath("/srv");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.require().tenantId()).isEqualTo(TENANT);
    }

    @Test
    void resolvesTheActorFromPatPrincipalsToo() {
        SecurityContextHolder.getContext().setAuthentication(authenticated(new AuthenticatedPrincipal(ACTOR, null)));
        assertThat(interceptor.preHandle(get("/api/v1/tenants/" + TENANT), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.require().actorId())
                .as("personal-access-token principals resolve their user")
                .isEqualTo(ACTOR);
    }

    @Test
    void anonymousRequestsBindNothingAndStayFailClosed() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tenants/" + TENANT);
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void platformAdminBindsTheOverrideAndAuditsOneCrossTenantEvent() {
        actorIsPlatformAdmin = true;
        MockHttpServletRequest request = get("/api/v1/tenants/" + TENANT + "/members");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isTrue();

        TenantContext context = TenantContext.require();
        assertThat(context.crossTenantOverride())
                .as("the override is set only from the platform-admin flag")
                .isTrue();
        assertThat(context.actorId()).isEqualTo(ACTOR);

        assertThat(published).as("exactly one override audit per admission").hasSize(1);
        AuditEvent event = published.get(0);
        assertThat(event.type()).isEqualTo(AuditEventType.CROSS_TENANT_ACCESS);
        assertThat(event.userId()).isEqualTo(ACTOR);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.success()).isTrue();
        assertThat(event.detail()).containsEntry("method", "GET");
        assertThat(event.detail()).containsEntry("uri", "/api/v1/tenants/" + TENANT + "/members");
    }

    @Test
    void organizationActorsNeverObtainTheOverrideOrItsAudit() {
        // The interceptor consults only PlatformAdmins (users.platform_admin);
        // an ORG_ADMIN actor resolves to false there by construction.
        actorIsPlatformAdmin = false;
        assertThat(interceptor.preHandle(get("/api/v1/tenants/" + TENANT), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.require().crossTenantOverride()).isFalse();
        assertThat(published)
                .as("no override audit without the platform-admin flag")
                .isEmpty();
    }

    @Test
    void afterCompletionClearsTheContextEvenWhenTheHandlerThrew() {
        interceptor.preHandle(get("/api/v1/tenants/" + TENANT), new MockHttpServletResponse(), new Object());
        TenantContext.require();

        interceptor.afterCompletion(
                get("/api/v1/tenants/" + TENANT),
                new MockHttpServletResponse(),
                new Object(),
                new IllegalStateException("handler blew up mid-query"));

        assertThat(TenantContext.current())
                .as("the exception path must not leak the scope past the request")
                .isEmpty();
    }

    @Test
    void reusedThreadsNeverServeTheNextRequestWithAStaleContext() {
        // Request one: tenant-addressed, handler throws, completion clears.
        HttpServletRequest failing = get("/api/v1/tenants/" + TENANT);
        interceptor.preHandle(failing, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(failing, new MockHttpServletResponse(), new Object(), new RuntimeException());

        // Request two on the same thread: non-tenant route binds nothing,
        // so the seam refuses instead of reusing request one's tenant.
        assertThat(interceptor.preHandle(get("/api/v1/tenants"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require)
                .as("a stale scope would be a cross-tenant leak; the seam must refuse")
                .isInstanceOf(TenantIsolationException.class);
    }

    /** An authenticated JWT request for the given path, as the security chain leaves it. */
    private static MockHttpServletRequest get(String uri) {
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwtOf(ACTOR)));
        return new MockHttpServletRequest("GET", uri);
    }

    private static org.springframework.security.authentication.TestingAuthenticationToken authenticated(
            Object principal) {
        org.springframework.security.authentication.TestingAuthenticationToken token =
                new org.springframework.security.authentication.TestingAuthenticationToken(principal, "n/a");
        token.setAuthenticated(true);
        return token;
    }

    private static Jwt jwtOf(UUID subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject.toString())
                .build();
    }
}
