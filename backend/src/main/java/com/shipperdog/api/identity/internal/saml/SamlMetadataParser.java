package com.shipperdog.api.identity.internal.saml;

import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Ingests IdP metadata XML and generates SP metadata XML (S14-02,
 * ADR-0009).
 *
 * <p><strong>Ingestion</strong> parses the uploaded EntityDescriptor
 * through the hardened {@link SamlXml} seam and refuses anything the
 * flow could not pin against: no entityID, no HTTP-Redirect binding
 * SingleSignOnService, or no signing certificate. Signing certificates
 * come from {@code KeyDescriptor use="signing"} elements (an omitted
 * use attribute counts as signing -- several providers omit it);
 * multiple certificates are kept (rotation overlap: validation accepts
 * any of them). Certificates embedded in inbound ASSERTIONS are never
 * trusted -- only this pinned set.
 *
 * <p><strong>Generation</strong> renders the SP's EntityDescriptor:
 * the entityID is the SP metadata route's own URL (a stable,
 * per-organization identity), the ACS is the POST-binding consumer
 * route, and the optional signing KeyDescriptor appears only when the
 * deployment configured an SP signing certificate.
 */
public final class SamlMetadataParser {

    static final String BINDING_REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
    static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    private SamlMetadataParser() {}

    /**
     * Parses and validates an IdP EntityDescriptor.
     *
     * @throws SamlValidationException with reason {@code saml_metadata}
     *         when the document is malformed or missing anything the
     *         flow must pin against
     */
    public static SamlIdpMetadata parseIdpMetadata(String metadataXml) {
        Document document = SamlXml.parse(metadataXml, SamlResponseValidator.REASON_METADATA);
        Element descriptor = document.getDocumentElement();
        if (!SamlXml.NS_METADATA.equals(descriptor.getNamespaceURI())
                || !"EntityDescriptor".equals(descriptor.getLocalName())) {
            throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
        }
        String entityId = descriptor.getAttribute("entityID");
        if (entityId == null || entityId.isBlank() || entityId.length() > 512) {
            throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
        }
        Element ssoDescriptor = SamlXml.childElement(descriptor, SamlXml.NS_METADATA, "IDPSSODescriptor");
        if (ssoDescriptor == null) {
            throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
        }
        String redirectLocation = null;
        for (Element service : SamlXml.childElements(ssoDescriptor, SamlXml.NS_METADATA, "SingleSignOnService")) {
            if (BINDING_REDIRECT.equals(service.getAttribute("Binding"))) {
                redirectLocation = service.getAttribute("Location");
                break;
            }
        }
        if (redirectLocation == null || redirectLocation.isBlank() || !redirectLocation.startsWith("https://")) {
            throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (Element keyDescriptor : SamlXml.childElements(ssoDescriptor, SamlXml.NS_METADATA, "KeyDescriptor")) {
            String use = keyDescriptor.getAttribute("use");
            if (!use.isEmpty() && !"signing".equals(use)) {
                continue;
            }
            Element keyInfo = SamlXml.childElement(keyDescriptor, SamlXml.NS_SIGNATURE, "KeyInfo");
            Element x509Data = keyInfo == null ? null : SamlXml.childElement(keyInfo, SamlXml.NS_SIGNATURE, "X509Data");
            Element x509Certificate =
                    x509Data == null ? null : SamlXml.childElement(x509Data, SamlXml.NS_SIGNATURE, "X509Certificate");
            String der = x509Certificate == null ? null : SamlXml.textOf(x509Certificate);
            if (der == null) {
                continue;
            }
            try {
                certificates.add((X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new java.io.ByteArrayInputStream(
                                Base64.getMimeDecoder().decode(der.replaceAll("\\s", "")))));
            } catch (RuntimeException | java.security.GeneralSecurityException e) {
                throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
            }
        }
        if (certificates.isEmpty()) {
            throw new SamlValidationException(SamlResponseValidator.REASON_METADATA);
        }
        List<String> formats = new ArrayList<>();
        for (Element format : SamlXml.childElements(ssoDescriptor, SamlXml.NS_METADATA, "NameIDFormat")) {
            String value = SamlXml.textOf(format);
            if (value != null) {
                formats.add(value);
            }
        }
        return new SamlIdpMetadata(entityId, redirectLocation, List.copyOf(certificates), List.copyOf(formats));
    }

    /**
     * Renders the SP's EntityDescriptor: entityID and ACS pinned to
     * this deployment's routes, the POST binding declared for the ACS,
     * and the signing KeyDescriptor only when the deployment carries
     * an SP certificate (unsigned AuthnRequests otherwise -- Entra,
     * Okta, and Workspace all accept them when the metadata says so).
     */
    public static String spMetadataXml(String spEntityId, String acsUrl, X509Certificate signingCertificate) {
        StringBuilder xml = new StringBuilder();
        xml.append("<md:EntityDescriptor xmlns:md=\"")
                .append(SamlXml.NS_METADATA)
                .append("\" entityID=\"")
                .append(escape(spEntityId))
                .append("\">\n");
        xml.append("  <md:SPSSODescriptor protocolSupportEnumeration=\"")
                .append(SamlXml.NS_PROTOCOL)
                .append("\">\n");
        if (signingCertificate != null) {
            xml.append("    <md:KeyDescriptor use=\"signing\">\n");
            xml.append("      <ds:KeyInfo xmlns:ds=\"")
                    .append(SamlXml.NS_SIGNATURE)
                    .append("\">\n");
            xml.append("        <ds:X509Data>\n");
            xml.append("          <ds:X509Certificate>")
                    .append(base64Cert(signingCertificate))
                    .append("</ds:X509Certificate>\n");
            xml.append("        </ds:X509Data>\n");
            xml.append("      </ds:KeyInfo>\n");
            xml.append("    </md:KeyDescriptor>\n");
        }
        xml.append("    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>\n");
        xml.append("    <md:AssertionConsumerService Binding=\"")
                .append(BINDING_POST)
                .append("\" Location=\"")
                .append(escape(acsUrl))
                .append("\" index=\"0\" isDefault=\"true\"/>\n");
        xml.append("  </md:SPSSODescriptor>\n");
        xml.append("</md:EntityDescriptor>\n");
        return xml.toString();
    }

    private static String base64Cert(X509Certificate certificate) {
        try {
            return Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException("the SP certificate cannot be encoded", e);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
