package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.identity.internal.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The JWT signing secret has no committed default; the identity module
 * must refuse to start without a usable one (issue #3: no secret material
 * in files, fail fast on misconfiguration).
 */
class AuthPropertiesTest {

    @Test
    void rejectsMissingSecret() {
        assertThatThrownBy(() -> new AuthProperties(null, null, null, null, 0, null, null, null).requireValidSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() ->
                        new AuthProperties("too-short", null, null, null, 0, null, null, null).requireValidSecret())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }

    @Test
    void acceptsSecretOfAtLeast32Bytes() {
        assertThatCode(() -> new AuthProperties(
                                "0123456789abcdef0123456789abcdef", null, null, null, 0, null, null, null)
                        .requireValidSecret())
                .doesNotThrowAnyException();
    }

    @Test
    void appliesDocumentedDefaults() {
        AuthProperties properties =
                new AuthProperties("0123456789abcdef0123456789abcdef", null, null, null, 0, null, null, null);

        assertThat(properties.issuer()).isEqualTo("shipperdog-api");
        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.lockoutThreshold()).isEqualTo(5);
        assertThat(properties.lockoutDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.passwordResetTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.invitationTtl()).isEqualTo(Duration.ofDays(3));
    }
}
