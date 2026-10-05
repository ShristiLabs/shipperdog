package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Lifecycle of the request's tenant context (issue #10, acceptance
 * criterion 1): binding, reading, and the fail-closed refusal that
 * keeps an unbound thread from ever reaching tenant-scoped data.
 */
class TenantContextTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        TenantContext.end();
    }

    @Test
    void beginBindsAReadableImmutableScope() {
        TenantContext.begin(tenantId, actorId, false);
        TenantContext context = TenantContext.require();
        assertThat(context.tenantId()).isEqualTo(tenantId);
        assertThat(context.actorId()).isEqualTo(actorId);
        assertThat(context.crossTenantOverride()).isFalse();
        assertThat(TenantContext.current()).contains(context);
    }

    @Test
    void requireWithoutContextFailsClosed() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(TenantIsolationException.class)
                .hasMessageContaining("no tenant context");
    }

    @Test
    void endClearsTheScopeAndIsIdempotent() {
        TenantContext.begin(tenantId, actorId, true);
        TenantContext.end();
        assertThat(TenantContext.current()).isEmpty();
        assertThatCode(TenantContext::end).doesNotThrowAnyException();
        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void incompleteScopesAreRejectedAtBegin() {
        assertThatThrownBy(() -> TenantContext.begin(null, actorId, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantContext.begin(tenantId, null, false)).isInstanceOf(NullPointerException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void platformOverrideFlagIsCarriedExplicitly() {
        TenantContext.begin(tenantId, actorId, true);
        assertThat(TenantContext.require().crossTenantOverride()).isTrue();
    }
}
