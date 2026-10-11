package com.shipperdog.api.identity.internal.saml;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The parsed IdP metadata (S14-02): everything the SP flow pins
 * against, extracted once at configuration time from the uploaded
 * EntityDescriptor XML. The raw XML is retained verbatim in the
 * configuration row (the audit source of truth); this record is the
 * validated projection the flow trusts.
 *
 * @param entityId           the IdP's entityID -- the value the
 *                           Response/Assertion Issuer must equal
 * @param ssoRedirectLocation the HTTP-Redirect binding SingleSignOnService
 *                           location the AuthnRequest redirect targets
 * @param signingCertificates the pinned signing trust anchors: X.509
 *                           certificates from {@code use="signing"}
 *                           (or unspecified-use) KeyDescriptors.
 *                           Signature verification accepts ANY of
 *                           them (rotation overlap) and NEVER a
 *                           certificate embedded in the assertion
 * @param nameIdFormats      the NameID formats the IdP declares
 *                           supporting (informational; validation
 *                           accepts whatever the IdP asserts)
 */
public record SamlIdpMetadata(
        String entityId,
        String ssoRedirectLocation,
        List<X509Certificate> signingCertificates,
        List<String> nameIdFormats) {

    /** The SHA-256 fingerprint of every pinned certificate, hex (log/audit friendly). */
    public List<String> certificateFingerprints() {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return signingCertificates.stream()
                .map(cert -> {
                    try {
                        return java.util.HexFormat.of().formatHex(digest.digest(cert.getEncoded()));
                    } catch (java.security.cert.CertificateEncodingException e) {
                        return "unencodable";
                    }
                })
                .toList();
    }
}
