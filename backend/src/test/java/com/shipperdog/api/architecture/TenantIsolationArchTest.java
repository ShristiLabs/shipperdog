package com.shipperdog.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tenant.TenantScope;
import com.shipperdog.api.tenant.TenantScopedRepository;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

/**
 * The architectural guarantees of tenant isolation (INV-001, issue #10):
 * the seam is the only path to tenant-scoped data, so isolation cannot
 * be forgotten by a future module.
 *
 * <ul>
 *   <li>Raw {@link EntityManager}/{@link Session} queries are banned
 *       outside repository packages — with one recorded exception
 *       predating this story (the audit module's criteria builder over
 *       platform audit rows, which are not tenant-scoped).</li>
 *   <li>Every repository over a {@link TenantScope} entity extends
 *       {@link TenantScopedRepository}, or is explicitly marked
 *       {@link CrossTenantByDesign} as authorization-engine surface.</li>
 *   <li>Seam repositories declare no derived query methods of their own:
 *       all access composes through the context-filtered seam
 *       operations.</li>
 *   <li>Seam repositories do not extend {@code JpaSpecificationExecutor}
 *       or {@code RevisionRepository} — either would surface stock
 *       Spring Data query methods that run without the tenant
 *       predicate while declaring nothing detectable by the
 *       derived-query rule.</li>
 *   <li>The tenant context is bound and cleared only by the tenant
 *       module's web layer (or explicit job scopes added there later);
 *       services cannot re-scope mid-request.</li>
 * </ul>
 */
class TenantIsolationArchTest {

    /** Pre-existing raw-EntityManager user: audit criteria over platform rows (issue #6). */
    private static final String RAW_QUERY_ALLOWLIST = "com.shipperdog.api.audit.internal.service.AuditQueryService";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.shipperdog.api");

    @Test
    void rawPersistenceQueriesLiveOnlyInRepositoryPackages() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..internal.repository..")
                .and()
                .doNotHaveFullyQualifiedName(RAW_QUERY_ALLOWLIST)
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(EntityManager.class, Session.class)
                .because("hand-written persistence queries bypass the tenant-scoped seam (issue #10); "
                        + "queries belong to repository packages")
                .check(CLASSES);
    }

    @Test
    void tenantScopedRepositoriesMustUseTheSeam() {
        List<String> violations = new ArrayList<>();
        for (Class<?> repository : repositoryInterfaces()) {
            Class<?> domainType = domainTypeOf(repository);
            if (!TenantScope.class.isAssignableFrom(domainType)) {
                continue;
            }
            boolean onSeam = TenantScopedRepository.class.isAssignableFrom(repository);
            boolean engine = repository.isAnnotationPresent(CrossTenantByDesign.class);
            if (!onSeam && !engine) {
                violations.add(repository.getName() + " (domain " + domainType.getName() + ")");
            }
            if (onSeam && engine) {
                violations.add(repository.getName() + " cannot be both seam and cross-tenant-by-design");
            }
        }
        assertThat(violations)
                .as("repositories over TenantScope entities must extend TenantScopedRepository "
                        + "(or be @CrossTenantByDesign authorization surface)")
                .isEmpty();
    }

    @Test
    void seamRepositoriesDeclareNoDerivedQueries() {
        List<String> violators = new ArrayList<>();
        for (Class<?> repository : repositoryInterfaces()) {
            if (TenantScopedRepository.class.isAssignableFrom(repository)
                    && !repository.getName().equals(TenantScopedRepository.class.getName())
                    && repository.getDeclaredMethods().length > 0) {
                violators.add(repository.getName());
            }
        }
        assertThat(violators)
                .as("seam repositories must not declare query methods; compose via TenantScopedAccess")
                .isEmpty();
    }

    @Test
    void seamRepositoriesExposeNoUnscopedSpringDataQueryInterfaces() {
        List<String> violations = new ArrayList<>();
        for (Class<?> repository : repositoryInterfaces()) {
            if (!TenantScopedRepository.class.isAssignableFrom(repository)) {
                continue;
            }
            if (JpaSpecificationExecutor.class.isAssignableFrom(repository)) {
                violations.add(repository.getName() + " extends JpaSpecificationExecutor");
            }
            if (RevisionRepository.class.isAssignableFrom(repository)) {
                violations.add(repository.getName() + " extends RevisionRepository");
            }
        }
        assertThat(violations)
                .as("seam repositories must not extend JpaSpecificationExecutor or RevisionRepository: "
                        + "both add Spring Data query methods that declare nothing on the subinterface "
                        + "yet run without the seam's tenant predicate (issue #10); compose Specifications "
                        + "through TenantScopedAccess instead")
                .isEmpty();
    }

    @Test
    void tenantContextIsBoundOnlyByTheTenantModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage("com.shipperdog.api.tenant..")
                .should()
                .callMethod(TenantContext.class, "begin", java.util.UUID.class, java.util.UUID.class, boolean.class)
                .orShould()
                .callMethod(TenantContext.class, "end")
                .because("re-scoping the tenant context mid-request would defeat the seam (issue #10); "
                        + "context population belongs to the tenant module")
                .check(CLASSES);
    }

    private static List<Class<?>> repositoryInterfaces() {
        List<Class<?>> repositories = new ArrayList<>();
        for (JavaClass javaClass : CLASSES) {
            if (!javaClass.isInterface()) {
                continue;
            }
            Class<?> candidate = javaClass.reflect();
            if (JpaRepository.class.isAssignableFrom(candidate)) {
                repositories.add(candidate);
            }
        }
        return repositories;
    }

    /** Unresolvable generics are a violation of themselves: fail closed. */
    private static Class<?> domainTypeOf(Class<?> repository) {
        Class<?> domain = ResolvableType.forClass(repository)
                .as(JpaRepository.class)
                .getGeneric(0)
                .resolve();
        if (domain == null) {
            throw new AssertionError("cannot resolve the domain type of repository " + repository.getName()
                    + "; raw generics are not allowed");
        }
        return domain;
    }
}
