package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantScopedRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;

/**
 * The completeness guard of the tenant-isolation seam (ADR-0002
 * amendment, issue #10 fix): the seam inherited an unfiltered CRUD
 * surface from {@link JpaRepository} once, and this test makes sure it
 * can never happen again. It enumerates the <em>entire</em> inherited
 * surface reflectively — including operations a future Spring Data
 * version might add — and requires each one to be either overridden by
 * {@link TenantScopedRepositoryImpl} (with a meaningfully tenant-safe
 * behavior) or on an explicit, justified allowlist. A version bump that
 * introduces an unaccounted operation fails this test loudly instead of
 * silently reopening the seam.
 */
class TenantScopedSurfaceCompletenessTest {

    /**
     * The only inherited operation signature the seam does not override:
     * {@code flush()} pushes already-guarded pending work to the
     * database and issues no query of its own, so it can neither leak
     * foreign rows nor destroy them. Everything else must be ours. Kept
     * signature-exact so a future parameterized {@code flush(...)}
     * overload cannot ride the allowlist.
     */
    private static final Set<String> NO_TENANT_SEMANTICS = Set.of("flush()");

    private TenantScopedRepositoryImpl<TenantMemberEntity, UUID> seam;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        JpaEntityInformation<TenantMemberEntity, UUID> information = mock(JpaEntityInformation.class);
        when(information.getJavaType()).thenReturn(TenantMemberEntity.class);
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.getEntityManagerFactory()).thenReturn(mock(EntityManagerFactory.class));
        when(entityManager.getMetamodel()).thenReturn(mock(Metamodel.class));
        seam = new TenantScopedRepositoryImpl<>(information, entityManager);
        TenantContext.end();
    }

    @AfterEach
    void cleanUp() {
        TenantContext.end();
    }

    @Test
    void everyInheritedOperationIsOverriddenOrExplicitlyAllowed() {
        List<String> uncovered = new ArrayList<>();
        for (Method inherited : inheritedSurface()) {
            if (NO_TENANT_SEMANTICS.contains(signatureOf(inherited))) {
                continue;
            }
            if (!overriddenBySeam(inherited)) {
                uncovered.add(signatureOf(inherited));
            }
        }
        assertThat(uncovered)
                .as("every JpaRepository operation a seam repository inherits must be overridden by "
                        + "TenantScopedRepositoryImpl (or explicitly allowlisted in NO_TENANT_SEMANTICS); "
                        + "uncovered operations run without the tenant predicate")
                .isEmpty();
    }

    @Test
    void everyInheritedOperationFailsClosedWithoutATenantContext() {
        List<String> leaks = new ArrayList<>();
        for (Method inherited : inheritedSurface()) {
            if (NO_TENANT_SEMANTICS.contains(signatureOf(inherited))) {
                continue;
            }
            Object[] arguments = new Object[inherited.getParameterCount()];
            try {
                inherited.invoke(seam, arguments);
                leaks.add(signatureOf(inherited) + " ran without a tenant context");
            } catch (InvocationTargetException thrown) {
                if (!(thrown.getCause() instanceof TenantIsolationException)) {
                    leaks.add(signatureOf(inherited) + " failed with "
                            + thrown.getCause().getClass().getSimpleName() + " instead of refusing");
                }
            } catch (ReflectiveOperationException | IllegalArgumentException thrown) {
                leaks.add(signatureOf(inherited) + " could not be probed: " + thrown);
            }
        }
        assertThat(leaks)
                .as("with no tenant context bound, every inherited operation must refuse with "
                        + "TenantIsolationException; anything else is a fail-open bypass")
                .isEmpty();
    }

    @Test
    void theAllowlistedPassThroughCannotTouchTenantData() {
        assertThatCode(() -> seam.flush())
                .as("flush() only pushes already-guarded pending work; it must pass through")
                .doesNotThrowAnyException();
    }

    /** The full public JpaRepository surface, defaults and shims included. */
    private static List<Method> inheritedSurface() {
        return Arrays.stream(JpaRepository.class.getMethods())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() != Object.class)
                .toList();
    }

    /**
     * Whether the seam declares an override matching name and parameter
     * types. Bridge methods count: the compiler emits them with the
     * interface's erased descriptors (for example {@code findById(Object)}
     * delegating to the {@code ID}-typed override), so matching against
     * them is exact — and an override that disappears takes its bridge
     * with it.
     */
    private static boolean overriddenBySeam(Method inherited) {
        for (Method declared : TenantScopedRepositoryImpl.class.getDeclaredMethods()) {
            if (declared.getName().equals(inherited.getName())
                    && Arrays.equals(declared.getParameterTypes(), inherited.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static String signatureOf(Method method) {
        return method.getName()
                + "("
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
                + ")";
    }
}
