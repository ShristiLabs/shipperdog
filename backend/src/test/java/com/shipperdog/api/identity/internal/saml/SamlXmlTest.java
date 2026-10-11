package com.shipperdog.api.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * The hardened parse seam's own gates (S14-02, ADR-0009): size cap,
 * DTD and external-entity refusal (the XXE family), and the
 * document-wide duplicate-ID rejection the wrap defense begins with.
 */
class SamlXmlTest {

    private static final String RESPONSE_OPEN = "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" ";

    @Test
    void emptyNullAndOversizedPayloadsAreRefused() {
        assertRefused(null);
        assertRefused(new byte[0]);
        assertRefused(("x".repeat(SamlXml.MAX_XML_BYTES + 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void dtdBearingDocumentsAreRefused() {
        assertRefused(("<?xml version=\"1.0\"?><!DOCTYPE samlp:Response [<!ELEMENT samlp:Response ANY>]>"
                        + RESPONSE_OPEN + "ID=\"_a\"/>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void externalEntityPayloadsAreRefused() {
        assertRefused(("<?xml version=\"1.0\"?><!DOCTYPE samlp:Response ["
                        + "<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                        + RESPONSE_OPEN
                        + "ID=\"_a\">&xxe;</samlp:Response>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void duplicatedIdsRefuseTheDocumentWhole() {
        byte[] payload = (RESPONSE_OPEN + "ID=\"_a\">"
                        + "<saml:Assertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_dup\"/>"
                        + "<saml:Assertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"_dup\"/>"
                        + "</samlp:Response>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SamlXml.parse(payload, "saml_malformed"))
                .as("two elements answering to one ID is the wrap attack's raw material")
                .isInstanceOfSatisfying(
                        SamlValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo("saml_wrap"));
    }

    @Test
    void aWellFormedDocumentParses() {
        Document document = SamlXml.parse(
                (RESPONSE_OPEN + "ID=\"_a\"/>").getBytes(java.nio.charset.StandardCharsets.UTF_8), "saml_malformed");
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("Response");
    }

    private static void assertRefused(byte[] xml) {
        assertThatThrownBy(() -> SamlXml.parse(xml, "saml_malformed"))
                .isInstanceOfSatisfying(
                        SamlValidationException.class,
                        e -> assertThat(e.reason()).isEqualTo("saml_malformed"));
    }
}
