package com.shipperdog.api.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.authorization.Permission;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CI permission checklist (issue #5, acceptance criterion 2): every
 * {@code /api/v1} handler method must declare its security posture with
 * {@code @PreAuthorize} — a permission for admin endpoints,
 * {@code isAuthenticated()} for self-scoped ones — unless it is one of
 * the explicitly allowlisted anonymous auth endpoints (they are open by
 * the identity security chain's permitAll). Additionally, every
 * permission code referenced by a {@code hasPermission(...)} expression
 * must exist in the {@link Permission} catalog, so annotations cannot
 * drift from the catalog.
 */
class EndpointSecurityAnnotationTest {

    /**
     * Anonymous endpoints: matched by IdentitySecurityConfig's permitAll
     * (login, registration, refresh, logout, password reset). These are
     * the ONLY endpoints allowed to carry no method-security annotation.
     */
    private static final Set<String> ANONYMOUS_ENDPOINTS = Set.of(
            "com.shipperdog.api.identity.AuthController.login",
            "com.shipperdog.api.identity.AuthController.register",
            "com.shipperdog.api.identity.AuthController.refresh",
            "com.shipperdog.api.identity.AuthController.logout",
            "com.shipperdog.api.identity.AuthController.requestPasswordReset",
            "com.shipperdog.api.identity.AuthController.confirmPasswordReset",
            // The OIDC SSO flow's anonymous legs (S14-01): the probe,
            // the authorization redirect, the provider callback, the
            // one-time session-code exchange, and the Back-Channel
            // Logout receiver. The flow's one-time state and its
            // cryptographic verification are the authority.
            "com.shipperdog.api.identity.SsoController.options",
            "com.shipperdog.api.identity.SsoController.authorize",
            "com.shipperdog.api.identity.SsoController.callback",
            "com.shipperdog.api.identity.SsoController.exchange",
            "com.shipperdog.api.identity.SsoController.backChannelLogout",
            // The SAML SP's anonymous legs (S14-02): the public SP
            // metadata document and the POST-binding ACS both flows
            // land on -- one-time request state and the pinned-metadata
            // XML-DSig verification are the authority.
            "com.shipperdog.api.identity.SamlController.metadata",
            "com.shipperdog.api.identity.SamlController.acs",
            // The SCIM 2.0 provisioning surface (S14-03, issue #69):
            // /scim/v2/Users and /scim/v2/Groups sit outside /api/v1
            // (the baseline chain's permitAll) -- the per-organization
            // connector bearer token IS the authentication, verified
            // by the identity module; every authenticity failure
            // answers the same uniform 401 SCIM error. The discovery
            // documents are anonymous RFC 7643 capability metadata.
            "com.shipperdog.api.identity.ScimController.serviceProviderConfig",
            "com.shipperdog.api.identity.ScimController.resourceTypes",
            "com.shipperdog.api.identity.ScimController.schemas",
            "com.shipperdog.api.identity.ScimController.listUsers",
            "com.shipperdog.api.identity.ScimController.createUser",
            "com.shipperdog.api.identity.ScimController.getUser",
            "com.shipperdog.api.identity.ScimController.replaceUser",
            "com.shipperdog.api.identity.ScimController.patchUser",
            "com.shipperdog.api.identity.ScimController.deleteUser",
            "com.shipperdog.api.identity.ScimController.listGroups",
            "com.shipperdog.api.identity.ScimController.createGroup",
            "com.shipperdog.api.identity.ScimController.getGroup",
            "com.shipperdog.api.identity.ScimController.replaceGroup",
            "com.shipperdog.api.identity.ScimController.patchGroup",
            "com.shipperdog.api.identity.ScimController.deleteGroup");

    private static final Pattern MAPPING_ANNOTATION =
            Pattern.compile("org\\.springframework\\.web\\.bind\\.annotation\\.[A-Za-z]+Mapping");

    private static final Pattern PERMISSION_CODE = Pattern.compile("'([a-z]+:[a-z-]+:[a-z-]+)'");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.shipperdog.api");

    @Test
    void everyApiEndpointDeclaresItsSecurityPosture() {
        List<String> unannotated = new ArrayList<>();
        for (JavaClass javaClass : CLASSES) {
            if (!javaClass.isAnnotatedWith(RestController.class)) {
                continue;
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (isEndpoint(method) && !isAllowlisted(javaClass, method) && !isAnnotated(method)) {
                    unannotated.add(javaClass.getName() + "." + method.getName());
                }
            }
        }
        assertThat(unannotated)
                .as("every /api/v1 handler must carry @PreAuthorize (or be an allowlisted anonymous endpoint)")
                .isEmpty();
    }

    @Test
    void annotationPermissionCodesExistInTheCatalog() {
        List<String> unknown = new ArrayList<>();
        for (Class<?> controller : restControllers().toList()) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
                if (annotation == null) {
                    continue;
                }
                Matcher matcher = PERMISSION_CODE.matcher(annotation.value());
                while (matcher.find()) {
                    if (Permission.fromCode(matcher.group(1)).isEmpty()) {
                        unknown.add(controller.getName() + "." + method.getName() + " -> " + matcher.group(1));
                    }
                }
            }
        }
        assertThat(unknown)
                .as("every annotation permission code must exist in the Permission catalog")
                .isEmpty();
    }

    private static boolean isEndpoint(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> MAPPING_ANNOTATION
                        .matcher(annotation.getType().getName())
                        .matches());
    }

    private static boolean isAllowlisted(JavaClass javaClass, JavaMethod method) {
        return ANONYMOUS_ENDPOINTS.contains(javaClass.getName() + "." + method.getName());
    }

    private static boolean isAnnotated(JavaMethod method) {
        return method.isAnnotatedWith(PreAuthorize.class);
    }

    private static Stream<Class<?>> restControllers() {
        return CLASSES.stream()
                .filter(javaClass -> javaClass.isAnnotatedWith(RestController.class))
                .map(JavaClass::reflect);
    }
}
