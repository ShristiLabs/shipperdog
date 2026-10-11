package com.shipperdog.api.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.junit.jupiter.api.Test;

/**
 * The optional SP signing pair's own gates (S14-02): the unsigned
 * posture on blank configuration, fail-fast on every malformed or
 * mismatched pair, and the Redirect-binding signature's determinism.
 */
class SamlSpCredentialsTest {

    @Test
    void blankConfigurationYieldsTheUnsignedPosture() {
        assertThat(SamlSpCredentials.parse(null, null)).isNull();
        assertThat(SamlSpCredentials.parse("", "  ")).isNull();
    }

    @Test
    void aHalfPairRefusesStartup() {
        KeyPair pair = rsa();

        assertThatThrownBy(() -> SamlSpCredentials.parse(keyPem(pair.getPrivate()), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SamlSpCredentials.parse(null, certPem(selfSigned(pair))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnparseableKeyRefusesStartup() {
        assertThatThrownBy(() -> SamlSpCredentials.parse("not a pem at all", certPem(selfSigned(rsa()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void aNonRsaKeyRefusesStartup() {
        assertThatThrownBy(() -> SamlSpCredentials.parse(ecKeyPem(), certPem(selfSigned(rsa()))))
                .as("the SP Redirect-binding signature is RSA-SHA256; other key kinds refuse startup")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMismatchedPairRefusesStartup() {
        KeyPair signing = rsa();
        X509Certificate otherCertificate = selfSigned(rsa());

        assertThatThrownBy(() -> SamlSpCredentials.parse(keyPem(signing.getPrivate()), certPem(otherCertificate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void aMatchingPairSignsTheRedirectBindingDeterministically() {
        KeyPair pair = rsa();
        SamlSpCredentials credentials = SamlSpCredentials.parse(keyPem(pair.getPrivate()), certPem(selfSigned(pair)));

        assertThat(credentials).isNotNull();
        byte[] first = credentials.signRedirectBinding("saml-request-value", "relay-state-value");
        byte[] second = credentials.signRedirectBinding("saml-request-value", "relay-state-value");
        assertThat(first.length).as("an RSA signature over the query octets").isGreaterThanOrEqualTo(256);
        assertThat(first).isEqualTo(second);
    }

    // ------------------------------------------------------------------
    // Key material helpers (BC, test scope)
    // ------------------------------------------------------------------

    private static KeyPair rsa() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate an RSA pair", e);
        }
    }

    private static String ecKeyPem() {
        try {
            PrivateKey key =
                    KeyPairGenerator.getInstance("EC").generateKeyPair().getPrivate();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(key.getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate an EC pair", e);
        }
    }

    private static String keyPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static String certPem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode the certificate", e);
        }
    }

    private static X509Certificate selfSigned(KeyPair pair) {
        try {
            org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name("CN=sp-test");
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo publicKeyInfo =
                    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
                            pair.getPublic().getEncoded());
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    subject,
                    BigInteger.probablePrime(128, new SecureRandom()),
                    java.util.Date.from(Instant.now().minusSeconds(3600)),
                    java.util.Date.from(Instant.now().plusSeconds(3600)),
                    subject,
                    publicKeyInfo);
            byte[] encoded = builder.build(sha256Signer(pair.getPrivate())).getEncoded();
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the test certificate", e);
        }
    }

    private static ContentSigner sha256Signer(PrivateKey privateKey) {
        return new ContentSigner() {
            private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

            @Override
            public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
                return new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256);
            }

            @Override
            public java.io.OutputStream getOutputStream() {
                return output;
            }

            @Override
            public byte[] getSignature() {
                try {
                    java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
                    signature.initSign(privateKey);
                    signature.update(output.toByteArray());
                    return signature.sign();
                } catch (Exception e) {
                    throw new IllegalStateException("cannot sign the test certificate", e);
                }
            }
        };
    }
}
