package com.shipperdog.api.identity.internal.saml;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The deployment's optional SAML SP signing credentials (S14-02):
 * a PKCS#8 private key plus its X.509 certificate, both supplied as
 * PEM through the {@code shipperdog.sso.saml-sp-signing-key} /
 * {@code shipperdog.sso.saml-sp-signing-cert} configuration
 * (environment; never committed -- the repo's standing posture).
 *
 * <p>When configured: the SP metadata publishes the certificate as a
 * signing KeyDescriptor and the AuthnRequest Redirect binding carries
 * a {@code SigAlg/Signature} pair (RSA-SHA256 over the canonical
 * SAMLRequest/RelayState/SigAlg query string). When absent: unsigned
 * AuthnRequests and no signing descriptor -- Entra, Okta, and Google
 * Workspace all accept that posture, and the configuration flag
 * WantAuthnRequestsSigned stays the IdP's explicit decision.
 *
 * <p>Fail-fast at construction: an unparseable pair, a non-RSA key,
 * or a certificate that does not match the key refuses startup rather
 * than surfacing as a runtime signature failure mid-login.
 */
public final class SamlSpCredentials {

    public static final String SIGNATURE_ALGORITHM_URI = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final String RSA = "RSA";

    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private SamlSpCredentials(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /** Parses the PEM pair; empty/blank inputs yield {@code null} (the unsigned posture). */
    public static SamlSpCredentials parse(String privateKeyPem, String certificatePem) {
        if ((privateKeyPem == null || privateKeyPem.isBlank())
                && (certificatePem == null || certificatePem.isBlank())) {
            return null;
        }
        if (privateKeyPem == null || privateKeyPem.isBlank() || certificatePem == null || certificatePem.isBlank()) {
            throw new IllegalStateException("the SAML SP signing credentials need both key and certificate");
        }
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        X509Certificate certificate = parseCertificate(certificatePem);
        if (!RSA.equals(privateKey.getAlgorithm())) {
            throw new IllegalStateException("the SAML SP signing key must be RSA");
        }
        if (!certificate.getPublicKey().equals(privateKey)) {
            PublicKey publicPart =
                    privateKey instanceof java.security.interfaces.RSAPrivateCrtKey crt ? keyFromCrt(crt) : null;
            if (publicPart == null || !certificate.getPublicKey().equals(publicPart)) {
                throw new IllegalStateException("the SAML SP certificate does not match the signing key");
            }
        }
        return new SamlSpCredentials(privateKey, certificate);
    }

    /** RSAPrivateCrtKey -> PublicKey (PKCS#8 "BEGIN PRIVATE KEY" carries no public part). */
    private static PublicKey keyFromCrt(java.security.interfaces.RSAPrivateCrtKey crt) {
        try {
            var spec = new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return KeyFactory.getInstance(RSA).generatePublic(spec);
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getMimeDecoder().decode(base64);
            return KeyFactory.getInstance(RSA).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the SAML SP signing key is not a parseable PKCS#8 PEM", e);
        }
    }

    private static X509Certificate parseCertificate(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the SAML SP certificate is not a parseable PEM", e);
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /**
     * Signs the Redirect-binding query payload: RSA-SHA256 over the
     * exact {@code SAMLRequest=..&RelayState=..&SigAlg=..} octets in
     * order (the binding's canonical construction).
     */
    public byte[] signRedirectBinding(String samlRequestQueryValue, String relayStateQueryValue) {
        String signedContent = "SAMLRequest=" + samlRequestQueryValue
                + "&RelayState=" + relayStateQueryValue
                + "&SigAlg=" + urlEncode(SIGNATURE_ALGORITHM_URI);
        try {
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(signedContent.getBytes(StandardCharsets.US_ASCII));
            return signer.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("the SAML SP redirect signature could not be computed", e);
        }
    }

    static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
