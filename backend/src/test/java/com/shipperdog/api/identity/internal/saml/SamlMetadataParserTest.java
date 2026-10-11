package com.shipperdog.api.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.ssofixture.MockSamlIdp;
import org.junit.jupiter.api.Test;

/**
 * The metadata parser's own gates (S14-02, issue #68, ADR-0009):
 * everything the ingestion must refuse before a configuration row can
 * pin against it, and the SP metadata document's shape -- unit-level
 * so the rejections are pinned to their exact cause rather than the
 * single 400 the configuration surface answers.
 */
class SamlMetadataParserTest {

    private static final String REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
    private static final String POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    @Test
    void parseIdpMetadataAcceptsAWellFormedDescriptor() {
        SamlIdpMetadata metadata = SamlMetadataParser.parseIdpMetadata(metadata(
                "https://idp.example/entity", REDIRECT, "https://idp.example/sso", "signing", certificatePem()));

        assertThat(metadata.entityId()).isEqualTo("https://idp.example/entity");
        assertThat(metadata.ssoRedirectLocation()).isEqualTo("https://idp.example/sso");
        assertThat(metadata.signingCertificates()).hasSize(1);
        assertThat(metadata.nameIdFormats()).isNotEmpty();
        assertThat(metadata.certificateFingerprints().getFirst()).matches("[0-9a-f]{64}");
    }

    @Test
    void unspecifiedUseKeyDescriptorsCountAsSigning() {
        SamlIdpMetadata metadata = SamlMetadataParser.parseIdpMetadata(
                metadata("https://idp.example/entity", REDIRECT, "https://idp.example/sso", "", certificatePem()));

        assertThat(metadata.signingCertificates())
                .as("several providers omit the use attribute; the descriptor still pins")
                .hasSize(1);
    }

    @Test
    void multipleSigningCertificatesAreKeptForRotationOverlap() {
        String withTwo = metadata(
                        "https://idp.example/entity", REDIRECT, "https://idp.example/sso", "signing", certificatePem())
                .replace(
                        "</md:KeyDescriptor>",
                        "</md:KeyDescriptor>\n    " + keyDescriptor("signing", secondCertificatePem()));

        assertThat(SamlMetadataParser.parseIdpMetadata(withTwo).signingCertificates())
                .as("rotation overlap: both the old and the new certificate stay pinned")
                .hasSize(2);
    }

    @Test
    void encryptionOnlyKeyDescriptorsDoNotPin() {
        assertThatThrownBy(() -> SamlMetadataParser.parseIdpMetadata(metadata(
                        "https://idp.example/entity",
                        REDIRECT,
                        "https://idp.example/sso",
                        "encryption",
                        certificatePem())))
                .as("a metadata whose only certificate is for encryption leaves nothing to verify against")
                .isInstanceOfSatisfying(
                        SamlValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo("saml_metadata"));
    }

    @Test
    void malformedDocumentsAreRefused() {
        String certificate = certificatePem();
        String good =
                metadata("https://idp.example/entity", REDIRECT, "https://idp.example/sso", "signing", certificate);

        assertMetadataRefused("<not-saml/>");
        assertMetadataRefused(good.replace("https://idp.example/entity", " "));
        assertMetadataRefused(good.replaceAll("(?s)<md:IDPSSODescriptor.*?</md:IDPSSODescriptor>", ""));
        assertMetadataRefused(good.replace(REDIRECT, POST));
        assertMetadataRefused(good.replace("https://idp.example/sso", "http://idp.example/sso"));
        assertMetadataRefused(good.replaceAll("(?s)<md:KeyDescriptor.*?</md:KeyDescriptor>", ""));
        assertMetadataRefused(good.replace(certificate, "not-base64!!!"));
    }

    @Test
    void spMetadataCarriesTheEntityIdAndAcsAndOnlyAnOptionalSigningDescriptor() {
        String unsigned =
                SamlMetadataParser.spMetadataXml("https://sp.example/metadata", "https://sp.example/acs", null);
        assertThat(unsigned)
                .contains("entityID=\"https://sp.example/metadata\"")
                .contains("Location=\"https://sp.example/acs\"")
                .contains(POST)
                .doesNotContain("KeyDescriptor");

        String signed = SamlMetadataParser.spMetadataXml(
                "https://sp.example/metadata", "https://sp.example/acs", new MockSamlIdp().pinnedCertificate());
        assertThat(signed).contains("<md:KeyDescriptor use=\"signing\">").contains("<ds:X509Certificate>");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertMetadataRefused(String metadataXml) {
        assertThatThrownBy(() -> SamlMetadataParser.parseIdpMetadata(metadataXml))
                .isInstanceOfSatisfying(
                        SamlValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo("saml_metadata"));
    }

    /** A fresh mock IdP's certificate PEM (distinct per call). */
    private static String certificatePem() {
        return new MockSamlIdp().pinnedCertificatePem();
    }

    /** A second, distinct certificate PEM (rotation-overlap fixtures). */
    private static String secondCertificatePem() {
        return new MockSamlIdp().pinnedCertificatePem();
    }

    private static String keyDescriptor(String use, String certificatePem) {
        return "<md:KeyDescriptor use=\""
                + use
                + "\">\n"
                + "      <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n"
                + "        <ds:X509Data><ds:X509Certificate>"
                + certificatePem
                + "</ds:X509Certificate></ds:X509Data>\n"
                + "      </ds:KeyInfo>\n"
                + "    </md:KeyDescriptor>";
    }

    private static String metadata(
            String entityId, String binding, String location, String keyUse, String certificatePem) {
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + entityId
                + "\">\n"
                + "  <md:IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">\n"
                + "    "
                + keyDescriptor(keyUse, certificatePem)
                + "\n"
                + "    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>\n"
                + "    <md:SingleSignOnService Binding=\""
                + binding
                + "\" Location=\""
                + location
                + "\"/>\n"
                + "  </md:IDPSSODescriptor>\n"
                + "</md:EntityDescriptor>\n";
    }
}
