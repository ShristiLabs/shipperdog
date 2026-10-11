package com.shipperdog.api.identity.internal.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Beans for the identity module. The HMAC key is derived once from
 * {@link AuthProperties#jwtSecret()} and shared by encoder and decoder;
 * startup fails fast when the secret is absent or under-sized.
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, SsoProperties.class})
public class IdentityConfig {

    /** Central clock so time-dependent logic (TTLs, lockouts) is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Argon2id with OWASP-recommended defaults: 16-byte salt, 32-byte
     * hash, 1 lane, 19 MiB memory, 2 iterations. The encoder parses cost
     * parameters from the stored hash, so they may be raised later
     * without invalidating existing hashes. Requires Bouncy Castle on the
     * runtime classpath.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        properties.requireValidSecret();
        JWKSource<SecurityContext> jwkSource =
                new ImmutableJWKSet<>(new JWKSet(new OctetSequenceKey.Builder(signingKey(properties))
                        .algorithm(JWSAlgorithm.HS256)
                        .build()));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        properties.requireValidSecret();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static SecretKey signingKey(AuthProperties properties) {
        byte[] bytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
