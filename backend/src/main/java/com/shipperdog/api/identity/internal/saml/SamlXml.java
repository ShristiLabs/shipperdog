package com.shipperdog.api.identity.internal.saml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The hardened XML parsing seam of the SAML stack (S14-02, issue #68,
 * ADR-0009): every SAML document -- ingested IdP metadata and inbound
 * Response/Assertion payloads -- passes through here and nowhere else.
 *
 * <p>Hardening is total and non-negotiable: DTDs are disallowed
 * outright (SAML documents never legitimately carry one), external
 * general and parameter entities are disabled, XInclude and entity
 * expansion are off, and secure processing is on -- the XXE family is
 * refused by the parser before any SAML semantics run. Payloads are
 * size-capped so a hostile POST cannot exhaust memory with a
 * compressed or oversized body.
 *
 * <p>Beyond the parser configuration, this seam enforces the
 * document-wide SAML ID uniqueness that signature wrapping attacks
 * rely on subverting: a document whose elements reuse an ID value is
 * refused whole (the wrap-attack defense documented in ADR-0009).
 */
public final class SamlXml {

    /** The SAML 2.0 protocol namespace ({@code samlp}). */
    public static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";

    /** The SAML 2.0 assertion namespace ({@code saml}). */
    public static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";

    /** The XML-DSig namespace ({@code ds}). */
    public static final String NS_SIGNATURE = "http://www.w3.org/2000/09/xmldsig#";

    /** The metadata namespace ({@code md}). */
    public static final String NS_METADATA = "urn:oasis:names:tc:SAML:2.0:metadata";

    /**
     * The ceiling on any SAML XML document this stack will parse: real
     * IdP metadata and responses are a few kilobytes; anything larger
     * is hostile or misconfigured, and either way refused before the
     * parser sees it.
     */
    public static final int MAX_XML_BYTES = 256 * 1024;

    private SamlXml() {}

    /**
     * Parses a SAML document with the hardened configuration. Duplicate
     * ID attribute values document-wide refuse the parse (the wrap
     * defense): a signature verifier must never face two elements
     * answering to the same reference URI.
     *
     * @throws SamlValidationException on any malformed, oversized,
     *         DTD-bearing, duplicate-ID, or otherwise unparsable input
     */
    public static Document parse(byte[] xml, String failureReason) {
        if (xml == null || xml.length == 0 || xml.length > MAX_XML_BYTES) {
            throw new SamlValidationException(failureReason);
        }
        javax.xml.parsers.DocumentBuilderFactory factory;
        try {
            factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IllegalStateException("the hardened XML parser configuration is unavailable", e);
        }
        Document document;
        try {
            document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
        } catch (SAXException | java.io.IOException | javax.xml.parsers.ParserConfigurationException e) {
            // A DTD-bearing document fails here (disallowed); the
            // uniform reason keeps the caller's audit line honest.
            throw new SamlValidationException(failureReason);
        }
        requireUniqueIds(document);
        return document;
    }

    /**
     * Rejects any document whose elements carry a duplicated SAML-style
     * ID value: classic signature-wrapping payloads nest a legitimately
     * signed assertion inside a forged wrapper that reuses its ID, so
     * the verifier cannot be tricked into validating the honest
     * signature while acting on the attacker's element.
     */
    private static void requireUniqueIds(Document document) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        collectIds(document.getElementsByTagNameNS(NS_PROTOCOL, "*"), seen);
        collectIds(document.getElementsByTagNameNS(NS_ASSERTION, "*"), seen);
        collectIds(document.getElementsByTagNameNS(NS_METADATA, "*"), seen);
    }

    private static void collectIds(NodeList nodes, java.util.Set<String> seen) {
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String id = element.getAttribute("ID");
            if (!id.isEmpty() && !seen.add(id)) {
                throw new SamlValidationException(SamlResponseValidator.REASON_WRAP);
            }
        }
    }

    /** Convenience overload for String payloads (UTF-8). */
    public static Document parse(String xml, String failureReason) {
        return parse(xml == null ? null : xml.getBytes(java.nio.charset.StandardCharsets.UTF_8), failureReason);
    }

    /** The first direct child element with the given namespace and local name, or null. */
    public static Element childElement(Element parent, String namespace, String localName) {
        org.w3c.dom.Node node = parent.getFirstChild();
        while (node != null) {
            if (node instanceof Element element
                    && node.getNamespaceURI() != null
                    && node.getNamespaceURI().equals(namespace)
                    && node.getLocalName().equals(localName)) {
                return element;
            }
            node = node.getNextSibling();
        }
        return null;
    }

    /** All direct child elements with the given namespace and local name, in document order. */
    public static java.util.List<Element> childElements(Element parent, String namespace, String localName) {
        java.util.List<Element> found = new java.util.ArrayList<>();
        org.w3c.dom.Node node = parent.getFirstChild();
        while (node != null) {
            if (node instanceof Element element
                    && node.getNamespaceURI() != null
                    && node.getNamespaceURI().equals(namespace)
                    && node.getLocalName().equals(localName)) {
                found.add(element);
            }
            node = node.getNextSibling();
        }
        return found;
    }

    /** The element's text content trimmed, or null when blank. */
    public static String textOf(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }
}
