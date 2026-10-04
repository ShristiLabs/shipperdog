package com.shipperdog.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.audit.internal.entity.AuditEventEntity;
import com.shipperdog.api.audit.internal.repository.AuditEventRepository;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

/**
 * Append-only enforcement of the audit store (issue #6, acceptance
 * criterion 2): the repository type exposes no mutating Spring Data
 * surface (it is a plain {@link Repository}, not a {@link JpaRepository},
 * and no declared method updates or deletes), the entity carries no
 * setters, and its JPA lifecycle guards reject update and removal at
 * runtime. Database-level REVOKE is the deployment follow-up documented
 * in V5.
 */
class AppendOnlyAuditStoreTest {

    @Test
    void repositoryDeclaresOnlyInsertAndQueryMethods() {
        assertThat(noMutatingRepositorySurface())
                .as("the audit store must not inherit a delete/flush-capable repository surface")
                .isTrue();

        for (Method method : AuditEventRepository.class.getMethods()) {
            String name = method.getName().toLowerCase();
            assertThat(name)
                    .as("method '%s' mutates audit history", method.getName())
                    .doesNotContain("delete")
                    .doesNotContain("update")
                    .doesNotContain("remove")
                    .doesNotContain("flush");
        }
    }

    private static boolean noMutatingRepositorySurface() {
        return !org.springframework.data.jpa.repository.JpaRepository.class.isAssignableFrom(AuditEventRepository.class)
                && !CrudRepository.class.isAssignableFrom(AuditEventRepository.class);
    }

    @Test
    void entityExposesNoStateMutators() {
        for (Method method : AuditEventEntity.class.getMethods()) {
            assertThat(method.getName().startsWith("set"))
                    .as("audit rows are immutable; '%s' must not exist", method.getName())
                    .isFalse();
        }
    }

    @Test
    void lifecycleGuardsRejectUpdateAndRemove() {
        AuditEventEntity entity = AuditEventEntity.from(AuditEvent.builder(AuditEventType.ACCESS_DENIED)
                .success(false)
                .detail(java.util.Map.of("uri", "/api/v1/audit"))
                .build());

        Method onUpdate = lifecycleMethod(PreUpdate.class);
        Method onRemove = lifecycleMethod(PreRemove.class);
        onUpdate.setAccessible(true);
        onRemove.setAccessible(true);

        assertThatThrownBy(() -> onUpdate.invoke(entity))
                .as("JPA update callback must reject mutation")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> onRemove.invoke(entity))
                .as("JPA remove callback must reject deletion")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static Method lifecycleMethod(Class<? extends java.lang.annotation.Annotation> annotation) {
        return Arrays.stream(AuditEventEntity.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(annotation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + annotation.getSimpleName() + " guard"));
    }
}
