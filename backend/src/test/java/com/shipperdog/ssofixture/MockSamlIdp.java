package com.shipperdog.ssofixture;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.xml.security.Init;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.Constants;
import org.apache.xml.security.utils.IdResolver;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * A contract-faithful mock SAML IdP for the SSO suites (S14-02, issue
 * #68): RSA keypair + self-signed certificate, an EntityDescriptor
 * indistinguishable from a real IdP's, and Response/Assertion
 * builder-signers using the SAME Apache Santuario XML-DSig stack the
 * platform verifies with (enveloped signature, exclusive c14n,
 * RSA-SHA256/SHA-256). The attack knobs exist precisely so the
 * platform's refusals are proven against spec-shaped attacks rather
 * than contrived ones: unsigned legs, foreign signing cert, wrong
 * audience, expired and not-yet-valid windows, wrong recipient,
 * SHA-1 signature downgrade, an XPath transform, and the classic
 * wrap payload (an honestly signed assertion copied inside a forged
 * wrapper that reuses its ID).
 *
 * <p>Provider shape variance is pinned through {@link Shape}: ENTRA
 * (persistent NameID, URI-named role attribute, assertion-signed),
 * OKTA (email NameID, {@code groups} attribute, either leg signed),
 * and WORKSPACE (email NameID, response-signed only -- the
 * assertion-signature-less shape the configuration exists for).
 */
public final class MockSamlIdp {

    static {
        Init.init();
    }

    /** The IdP's SSO location (HTTP-Redirect binding) the flow targets. */
    public static final String SSO_LOCATION = "https://sso.mock-idp.local/saml/sso";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String entityId;
    private final KeyPair signingKey = generateRsa();
    private final X509Certificate certificate = selfSignedCertificate(signingKey);
    /** The foreign key signs wrap payloads and mismatched-cert attacks (never in metadata). */
    private final KeyPair foreignKey = generateRsa();

    /** A fresh IdP identity: random entityID, fresh signing key. */
    public MockSamlIdp() {
        this("https://sso.mock-idp.local/" + UUID.randomUUID());
    }

    /**
     * The metadata-rotation shape: the SAME entityID with a NEW
     * signing key and certificate -- exactly what an administrator
     * re-uploads when the IdP rotates its certificate.
     */
    public MockSamlIdp(String entityId) {
        this.entityId = entityId;
    }

    /** What the provider asserts for the next response. */
    public static final class Behavior {
        public String subject = "corp.user@acme.example";
        public String email = "corp.user@acme.example";
        public String displayName = "Corp User";
        public Shape shape = Shape.OKTA;
        public List<String> groups = List.of();
        public String groupAttributeName = "groups";
        public String inResponseTo = null;
        /**
         * When set, the SubjectConfirmationData's InResponseTo carries
         * THIS value while the Response's own keeps {@link #inResponseTo}
         * -- the internally-inconsistent shape the validator's
         * request-mismatch check refuses.
         */
        public String subjectInResponseTo = null;
        /**
         * Omits the Response-level InResponseTo while the confirmation
         * data keeps naming the request -- the stripped-replay shape: the
         * attribute rides outside the assertion's enveloped signature, so
         * a captured SP-initiated response shorn of it must NOT pass as
         * IdP-initiated.
         */
        public boolean responseInResponseToSuppressed = false;

        public boolean signAssertion = true;
        public boolean signResponse = false;
        public boolean useForeignSigningCert = false;
        public boolean unsignedAssertionAttack = false;
        public boolean wrongAudience = false;
        public boolean expired = false;
        public boolean notYetValid = false;
        public boolean wrongRecipient = false;
        /** The Response's Destination names some other SP's ACS. */
        public boolean wrongDestination = false;
        /** The Response/Assertion Issuer names some other IdP. */
        public boolean wrongIssuer = false;

        public boolean successStatus = true;
        public boolean sha1SignatureAttack = false;
        public boolean xpathTransformAttack = false;
        public boolean wrapAttack = false;
        /** A second, differently-IDed (forged, unsigned) Assertion child. */
        public boolean extraAssertion = false;
        /** The assertion rides inside an EncryptedAssertion wrapper. */
        public boolean encryptedAssertion = false;

        public boolean xxePayload = false;
        public boolean transientNameId = false;
    }

    /** The three provider shapes the acceptance criteria name. */
    public enum Shape {
        ENTRA,
        OKTA,
        WORKSPACE
    }

    private volatile Behavior behavior = new Behavior();

    /** Resets to the honest default behavior between tests. */
    public void reset() {
        behavior = new Behavior();
    }

    /** The behavior to assert next. */
    public Behavior behave() {
        return behavior;
    }

    /** The IdP EntityDescriptor to configure the organization with. */
    public String metadataXml() {
        String der = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encodeCertificate());
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + entityId + "\">\n"
                + "  <md:IDPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">\n"
                + "    <md:KeyDescriptor use=\"signing\">\n"
                + "      <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">\n"
                + "        <ds:X509Data><ds:X509Certificate>" + der + "</ds:X509Certificate></ds:X509Data>\n"
                + "      </ds:KeyInfo>\n"
                + "    </md:KeyDescriptor>\n"
                + "    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>\n"
                + "    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress</md:NameIDFormat>\n"
                + "    <md:SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\""
                + SSO_LOCATION + "\"/>\n"
                + "  </md:IDPSSODescriptor>\n"
                + "</md:EntityDescriptor>\n";
    }

    /** Builds the base64 POST-binding SAMLResponse for the current behavior. */
    public String signedResponseFor(String acsUrl, String spEntityId) {
        Behavior b = behavior;
        if (b.xxePayload) {
            // A DTD-bearing payload: refused at parse, before any SAML
            // semantics (the XXE family).
            return Base64.getEncoder()
                    .encodeToString(
                            ("<?xml version=\"1.0\"?><!DOCTYPE samlp:Response [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                                            + "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" ID=\"_r\"&xxe;/>")
                                    .getBytes(StandardCharsets.UTF_8));
        }
        Instant now = Instant.now();
        String responseId = "_" + randomHex();
        String assertionId = "_" + randomHex();
        Instant notBefore = b.notYetValid ? now.plus(2, ChronoUnit.HOURS) : now.minusSeconds(30);
        Instant notOnOrAfter = b.expired ? now.minus(2, ChronoUnit.HOURS) : now.plus(10, ChronoUnit.MINUTES);
        String nameIdFormat = b.transientNameId
                ? "urn:oasis:names:tc:SAML:2.0:nameid-format:transient"
                : switch (b.shape) {
                    case ENTRA -> "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent";
                    case OKTA, WORKSPACE -> "urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress";
                };
        String attributeName = b.shape == Shape.ENTRA
                ? "http://schemas.microsoft.com/ws/2008/06/identity/claims/roles"
                : b.groupAttributeName;
        String audience = b.wrongAudience ? "https://other-sp.example/metadata" : spEntityId;
        String recipient = b.wrongRecipient ? "https://evil.example/acs" : acsUrl;
        String destination = b.wrongDestination ? "https://evil.example/acs" : acsUrl;
        StringBuilder xml = new StringBuilder();
        xml.append("<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\"")
                .append(" xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\"")
                .append(" ID=\"")
                .append(responseId)
                .append("\" Version=\"2.0\"")
                .append(" IssueInstant=\"")
                .append(now)
                .append("\"")
                .append(" Destination=\"")
                .append(destination)
                .append("\"");
        if (b.inResponseTo != null && !b.responseInResponseToSuppressed) {
            xml.append(" InResponseTo=\"").append(b.inResponseTo).append("\"");
        }
        String issuer = b.wrongIssuer ? "https://someone-else.example/idp" : entityId;
        xml.append(">")
                .append("<saml:Issuer>")
                .append(issuer)
                .append("</saml:Issuer>")
                .append("<samlp:Status><samlp:StatusCode Value=\"")
                .append(
                        b.successStatus
                                ? "urn:oasis:names:tc:SAML:2.0:status:Success"
                                : "urn:oasis:names:tc:SAML:2.0:status:Responder")
                .append("\"/></samlp:Status>")
                .append("<saml:Assertion ID=\"")
                .append(assertionId)
                .append("\" Version=\"2.0\"")
                .append(" IssueInstant=\"")
                .append(now)
                .append("\">")
                .append("<saml:Issuer>")
                .append(issuer)
                .append("</saml:Issuer>")
                .append("<saml:Subject>")
                .append("<saml:NameID Format=\"")
                .append(nameIdFormat)
                .append("\">")
                .append(xmlEscape(b.subject))
                .append("</saml:NameID>")
                .append("<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\">")
                .append("<saml:SubjectConfirmationData NotOnOrAfter=\"")
                .append(notOnOrAfter)
                .append("\" Recipient=\"")
                .append(recipient)
                .append("\"");
        String subjectInResponseTo = b.subjectInResponseTo != null ? b.subjectInResponseTo : b.inResponseTo;
        if (subjectInResponseTo != null) {
            xml.append(" InResponseTo=\"").append(subjectInResponseTo).append("\"");
        }
        xml.append("/>")
                .append("</saml:SubjectConfirmation>")
                .append("</saml:Subject>")
                .append("<saml:Conditions NotBefore=\"")
                .append(notBefore)
                .append("\" NotOnOrAfter=\"")
                .append(notOnOrAfter)
                .append("\">")
                .append("<saml:AudienceRestriction><saml:Audience>")
                .append(audience)
                .append("</saml:Audience></saml:AudienceRestriction>")
                .append("</saml:Conditions>")
                .append("<saml:AuthnStatement AuthnInstant=\"")
                .append(now)
                .append("\"><saml:AuthnContext><saml:AuthnContextClassRef>")
                .append("urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport")
                .append("</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>");
        if (!b.groups.isEmpty() || b.email != null || b.displayName != null) {
            xml.append("<saml:AttributeStatement>");
            appendAttribute(xml, attributeName, b.groups);
            appendAttribute(xml, "email", b.email == null ? List.of() : List.of(b.email));
            appendAttribute(xml, "displayName", b.displayName == null ? List.of() : List.of(b.displayName));
            xml.append("</saml:AttributeStatement>");
        }
        xml.append("</saml:Assertion>").append("</samlp:Response>");

        if (b.encryptedAssertion) {
            // The deferred EncryptedAssertion shape: the honest
            // assertion rides inside an EncryptedAssertion wrapper.
            // This SP publishes no encryption KeyDescriptor, so the
            // validator refuses the wrapper outright (never decrypts).
            String document = xml.toString();
            int start = document.indexOf("<saml:Assertion ");
            int end = document.indexOf("</saml:Assertion>") + "</saml:Assertion>".length();
            String wrapped = "<saml:EncryptedAssertion xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\">"
                    + document.substring(start, end)
                    + "</saml:EncryptedAssertion>";
            return Base64.getEncoder()
                    .encodeToString(document.substring(0, start)
                            .concat(wrapped)
                            .concat(document.substring(end))
                            .getBytes(StandardCharsets.UTF_8));
        }

        Document document = parse(xml.toString());
        Element response = document.getDocumentElement();
        Element assertion = firstChild(response, "urn:oasis:names:tc:SAML:2.0:assertion", "Assertion");

        if (b.wrapAttack) {
            // The classic wrap: the honestly signed assertion is
            // cloned into a forged outer wrapper that REUSES its ID,
            // and the wrapper's own signature references it -- only
            // the document-wide ID uniqueness and the single-direct-
            // assertion rules stand between this and a forged login.
            Element clonedAssertion = (Element) assertion.cloneNode(true);
            Element forgedAssertion = (Element) assertion.cloneNode(true);
            // forged copy keeps the SAME ID; remove the original
            response.removeChild(assertion);
            forgedAssertion.removeAttribute("ID");
            forgedAssertion.setAttribute("ID", clonedAssertion.getAttribute("ID"));
            response.appendChild(clonedAssertion);
            response.appendChild(forgedAssertion);
            signEnveloped(clonedAssertion, signingKey(b).getPrivate(), b);
            return serialize(document);
        }

        if (b.extraAssertion) {
            // A second Assertion child with a DIFFERENT ID (unique IDs
            // pass the parse-time check): the exactly-one-direct-child
            // structural rule refuses it as a wrap shape.
            Element forged = (Element) assertion.cloneNode(true);
            forged.removeAttribute("ID");
            forged.setAttribute("ID", "_forged" + randomHex());
            response.appendChild(forged);
        }

        // The signature policy is the caller's: ENTRA-shaped tests
        // sign the assertion, WORKSPACE-shaped tests sign the response
        // only (the configuration exists precisely for that shape).
        if (b.signResponse) {
            signEnveloped(response, signingKey(b).getPrivate(), b);
        }
        if (b.signAssertion && !b.unsignedAssertionAttack) {
            signEnveloped(assertion, signingKey(b).getPrivate(), b);
        }
        return serialize(document);
    }

    /** Selects the signing key: the foreign key for mismatched-cert attacks. */
    private KeyPair signingKey(Behavior b) {
        return b.useForeignSigningCert ? foreignKey : signingKey;
    }

    private void signEnveloped(Element element, PrivateKey privateKey, Behavior b) {
        try {
            String id = element.getAttribute("ID");
            IdResolver.registerElementById(element, element.getAttributeNode("ID"));
            XMLSignature signature = new XMLSignature(
                    element.getOwnerDocument(),
                    "",
                    XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256,
                    org.apache.xml.security.c14n.Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            Transforms transforms = new Transforms(element.getOwnerDocument());
            transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
            transforms.addTransform(Transforms.TRANSFORM_C14N_EXCL_OMIT_COMMENTS);
            if (b.xpathTransformAttack) {
                // The XPath transform carries its expression in a
                // ds:XPath child element (spec shape; the transform
                // pin refuses the algorithm before any evaluation).
                Element xpath = element.getOwnerDocument().createElementNS(Constants.SignatureSpecNS, "ds:XPath");
                xpath.setTextContent("not(ancestor-or-self::ds:Signature)");
                transforms.addTransform(Transforms.TRANSFORM_XPATH, xpath);
            }
            signature.addDocument("#" + id, transforms, "http://www.w3.org/2001/04/xmlenc#sha256");
            signature.addKeyInfo(certificate);
            // Schema position: the signature follows the Issuer.
            Element issuer = firstChild(element, "urn:oasis:names:tc:SAML:2.0:assertion", "Issuer");
            if (issuer != null && issuer.getNextSibling() != null) {
                element.insertBefore(signature.getElement(), issuer.getNextSibling());
            } else {
                element.insertBefore(signature.getElement(), element.getFirstChild());
            }
            signature.sign(privateKey);
            if (b.sha1SignatureAttack) {
                // The downgrade shape: Santuario 4.x dropped its
                // rsa-sha1 signing SPI, so the attack relabels an
                // honestly RSA-SHA256-signed payload's DECLARED
                // SignatureMethod -- exactly the declaration the
                // verifier's algorithm pin must refuse before any
                // crypto runs.
                for (org.w3c.dom.Node node = signature.getElement().getFirstChild();
                        node != null;
                        node = node.getNextSibling()) {
                    if (node instanceof Element signedInfo
                            && "SignedInfo".equals(signedInfo.getLocalName())
                            && Constants.SignatureSpecNS.equals(signedInfo.getNamespaceURI())) {
                        for (org.w3c.dom.Node method = signedInfo.getFirstChild();
                                method != null;
                                method = method.getNextSibling()) {
                            if (method instanceof Element signatureMethod
                                    && "SignatureMethod".equals(signatureMethod.getLocalName())) {
                                signatureMethod.setAttribute(
                                        "Algorithm", "http://www.w3.org/2000/09/xmldsig-more#rsa-sha1");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("the mock IdP could not sign its payload", e);
        }
    }

    // ------------------------------------------------------------------
    // Key material
    // ------------------------------------------------------------------

    private static KeyPair generateRsa() {
        try {
            return KeyPairGenerator.getInstance("RSA").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate the mock IdP's RSA key", e);
        }
    }

    private byte[] encodeCertificate() {
        try {
            return certificate.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode the mock certificate", e);
        }
    }

    /** A self-signed certificate over the given key (BC builder; no bcpkix helpers needed). */
    private static X509Certificate selfSignedCertificate(KeyPair keyPair) {
        try {
            Instant now = Instant.now();
            org.bouncycastle.asn1.x500.X500Name subject = new org.bouncycastle.asn1.x500.X500Name("CN=mock-saml-idp");
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo publicKeyInfo =
                    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
                            keyPair.getPublic().getEncoded());
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    subject,
                    BigInteger.probablePrime(128, RANDOM),
                    java.util.Date.from(now.minus(Duration.ofDays(1))),
                    java.util.Date.from(now.plus(Duration.ofDays(365))),
                    subject,
                    publicKeyInfo);
            ContentSigner signer = sha256Signer(keyPair.getPrivate());
            byte[] encoded = builder.build(signer).getEncoded();
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the mock certificate", e);
        }
    }

    /** A minimal JCA-backed SHA256withRSA ContentSigner (bcprov's operator SPI). */
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
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(privateKey);
                    signature.update(output.toByteArray());
                    return signature.sign();
                } catch (Exception e) {
                    throw new IllegalStateException("cannot sign the mock certificate", e);
                }
            }
        };
    }

    // ------------------------------------------------------------------
    // DOM helpers (the fixture builds its own documents; the platform
    // re-parses them through its hardened seam)
    // ------------------------------------------------------------------

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("the mock IdP built an unparsable document", e);
        }
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        org.w3c.dom.Node node = parent.getFirstChild();
        while (node != null) {
            if (node instanceof Element element
                    && namespace.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                return element;
            }
            node = node.getNextSibling();
        }
        return null;
    }

    private static String serialize(Document document) {
        try {
            javax.xml.transform.Transformer transformer =
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            transformer.transform(
                    new javax.xml.transform.dom.DOMSource(document),
                    new javax.xml.transform.stream.StreamResult(output));
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("the mock IdP could not serialize its document", e);
        }
    }

    private static void appendAttribute(StringBuilder xml, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        xml.append("<saml:Attribute Name=\"").append(xmlEscape(name)).append("\">");
        for (String value : values) {
            xml.append("<saml:AttributeValue xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                    .append(" xsi:type=\"xs:string\"")
                    .append(" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">")
                    .append(xmlEscape(value))
                    .append("</saml:AttributeValue>");
        }
        xml.append("</saml:Attribute>");
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String randomHex() {
        byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        return java.util.HexFormat.of().formatHex(entropy);
    }

    /** The certificate the metadata pins. */
    public X509Certificate pinnedCertificate() {
        return certificate;
    }

    /** The pinned certificate's base64 DER, exactly as it rides in metadata. */
    public String pinnedCertificatePem() {
        return Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encodeCertificate());
    }
}
