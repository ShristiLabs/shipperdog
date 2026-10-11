package com.shipperdog.api.identity.internal.saml;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.xml.security.Init;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.signature.XMLSignatureException;
import org.apache.xml.security.utils.IdResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * THE critical surface of the SAML stack (S14-02, issue #68,
 * ADR-0009): validation of an inbound Response/Assertion payload.
 * Nothing the payload claims is acted on before every check here
 * passes.
 *
 * <p>Signature verification runs on Apache Santuario -- the proven
 * XML-DSig implementation (c14n, transforms, digest, asymmetric
 * signature) -- under semantics stricter than the library's defaults:
 * <ul>
 * <li><strong>Cert pinning</strong>: only the X.509 certificates from
 * the ingested IdP metadata are ever handed to the verifier; a
 * certificate embedded in the assertion's KeyInfo is ignored
 * entirely. Any pinned certificate may verify (rotation overlap).</li>
 * <li><strong>Algorithm pinning</strong>: RSA-SHA256/384/512 and
 * ECDSA-SHA256/384/512 signatures over SHA-256/384/512 digests only --
 * SHA-1, HMAC (key confusion), and anything else is refused before
 * the crypto runs.</li>
 * <li><strong>Wrap defense</strong>: the document-wide ID uniqueness
 * holds ({@link SamlXml}), the Response carries exactly one Assertion
 * as a DIRECT child, and every signature's single Reference must carry
 * the {@code #ID} of its own ENCLOSING element -- a signature covering
 * anything other than the element it lives in does not count.</li>
 * <li><strong>Transform pinning</strong>: only the enveloped-signature
 * and exclusive-c14n transforms are permitted (no XPath/XSLT
 * evaluators).</li>
 * </ul>
 *
 * <p>Conditions: NotBefore/NotOnOrAfter within the configured clock
 * skew; the AudienceRestriction must name this SP's entityID;
 * SubjectConfirmationData must be bearer-method with Recipient equal
 * to this deployment's ACS and its own NotOnOrAfter respected. A
 * SubjectConfirmationData InResponseTo DEMANDS the Response repeat
 * the same id -- a blank or different Response-level value is the
 * stripped-replay shape an honest IdP never sends (a well-formed
 * IdP-initiated response carries no InResponseTo anywhere); whether
 * an SP-initiated request matches is the login service's single-use
 * consume to decide.
 *
 * <p>Every refusal throws {@link SamlValidationException} whose reason
 * is the audit line's distinct token; the browser never learns it.
 */
public final class SamlResponseValidator {

    // The audit-line reasons (distinct, never sent to the browser).
    public static final String REASON_METADATA = "saml_metadata";
    public static final String REASON_MALFORMED = "saml_malformed";
    public static final String REASON_STATUS = "saml_status";
    public static final String REASON_ISSUER = "saml_issuer";
    public static final String REASON_WRAP = "saml_wrap";
    public static final String REASON_RESPONSE_UNSIGNED = "saml_response_unsigned";
    public static final String REASON_RESPONSE_SIGNATURE = "saml_response_signature";
    public static final String REASON_ASSERTION_UNSIGNED = "saml_assertion_unsigned";
    public static final String REASON_ASSERTION_SIGNATURE = "saml_assertion_signature";
    public static final String REASON_ALGORITHM = "saml_algorithm";
    public static final String REASON_NOT_YET_VALID = "saml_not_yet_valid";
    public static final String REASON_EXPIRED = "saml_expired";
    public static final String REASON_AUDIENCE = "saml_audience";
    public static final String REASON_SUBJECT = "saml_subject";
    public static final String REASON_RECIPIENT = "saml_recipient";
    public static final String REASON_NAMEID = "saml_nameid";
    public static final String REASON_REQUEST_MISMATCH = "saml_request_mismatch";
    public static final String REASON_ENCRYPTED = "saml_encrypted";

    /** Attribute names an email is read from, in order (Entra's claim URI included). */
    private static final List<String> EMAIL_ATTRIBUTE_NAMES = List.of(
            "email",
            "emailAddress",
            "emailaddress",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
            "urn:oid:0.9.2342.19200300.100.1.3");

    private static final List<String> NAME_ATTRIBUTE_NAMES = List.of("displayName", "name", "userName");

    private static final String BEARER_CONFIRMATION = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
    private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    private static final String NAMEID_TRANSIENT = "urn:oasis:names:tc:SAML:2.0:nameid-format:transient";

    /** Pinned signature methods (no SHA-1, no HMAC -- key confusion is not a signature here). */
    private static final Set<String> SIGNATURE_METHODS = Set.of(
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512");

    /** Pinned digest methods. */
    private static final Set<String> DIGEST_METHODS = Set.of(
            "http://www.w3.org/2001/04/xmlenc#sha256",
            "http://www.w3.org/2001/04/xmldsig-more#sha384",
            "http://www.w3.org/2001/04/xmlenc#sha512");

    /** Pinned transforms (no expression evaluators). */
    private static final Set<String> TRANSFORMS = Set.of(
            "http://www.w3.org/2000/09/xmldsig#enveloped-signature",
            "http://www.w3.org/2001/10/xml-exc-c14n#",
            "http://www.w3.org/2001/10/xml-exc-c14n#WithComments");

    static {
        Init.init();
    }

    /**
     * What the flow demands of the payload and where to find it.
     *
     * @param idpEntityId        the pinned IdP entityID (metadata-derived)
     * @param spEntityId         this SP's entityID (audience and Issuer checks)
     * @param acsUrl             this deployment's ACS (Recipient/Destination)
     * @param wantResponseSigned the Response must carry a verifiable signature
     * @param wantAssertionSigned the Assertion must carry its own signature
     * @param groupAttributeName SAML attribute Name carrying the enterprise
     *                           groups/roles for mapping (null disables)
     * @param pinnedCertificates the ONLY trust anchors (IdP metadata)
     */
    public record Requirements(
            String idpEntityId,
            String spEntityId,
            String acsUrl,
            boolean wantResponseSigned,
            boolean wantAssertionSigned,
            String groupAttributeName,
            List<X509Certificate> pinnedCertificates) {}

    /** The validated assertion, expressed in the shared SSO identity shape. */
    public record ValidatedAssertion(
            String nameId,
            String nameIdFormat,
            String email,
            String displayName,
            List<String> groups,
            String inResponseTo) {}

    /**
     * Validates a POST-binding SAMLResponse payload (raw XML bytes)
     * end to end and extracts the identity the flow will act on.
     *
     * @throws SamlValidationException on the first failed check
     */
    public static ValidatedAssertion validate(
            byte[] responseXml, Requirements requirements, Instant now, Duration skew) {
        Document document = SamlXml.parse(responseXml, REASON_MALFORMED);
        Element response = document.getDocumentElement();
        if (!SamlXml.NS_PROTOCOL.equals(response.getNamespaceURI())
                || !"Response".equals(response.getLocalName())
                || !"2.0".equals(response.getAttribute("Version"))
                || response.getAttribute("ID").isBlank()) {
            throw new SamlValidationException(REASON_MALFORMED);
        }
        String destination = response.getAttribute("Destination");
        if (!destination.isBlank() && !destination.equals(requirements.acsUrl())) {
            throw new SamlValidationException(REASON_RECIPIENT);
        }
        requireSuccessStatus(response);
        requireIssuer(SamlXml.childElement(response, SamlXml.NS_ASSERTION, "Issuer"), requirements.idpEntityId());

        if (!SamlXml.childElements(response, SamlXml.NS_ASSERTION, "EncryptedAssertion")
                .isEmpty()) {
            // Deferred (ADR-0009): this SP publishes no encryption
            // KeyDescriptor, so no honest IdP encrypts for it.
            throw new SamlValidationException(REASON_ENCRYPTED);
        }
        List<Element> assertions = SamlXml.childElements(response, SamlXml.NS_ASSERTION, "Assertion");
        if (assertions.size() != 1) {
            // Zero is nothing to act on; more than one is a shape this
            // SP never requested and a wrap vector (exactly one direct
            // child survived the document-wide ID uniqueness too).
            throw new SamlValidationException(REASON_WRAP);
        }
        Element assertion = assertions.getFirst();
        if (assertion.getAttribute("ID").isBlank()) {
            throw new SamlValidationException(REASON_MALFORMED);
        }

        // Signatures FIRST: nothing the payload says is read further
        // before the required enveloped signatures verify against the
        // pinned metadata certificates.
        if (requirements.wantResponseSigned()) {
            Element signature = SamlXml.childElement(response, SamlXml.NS_SIGNATURE, "Signature");
            if (signature == null) {
                throw new SamlValidationException(REASON_RESPONSE_UNSIGNED);
            }
            verifyEnvelopedSignature(response, signature, requirements, REASON_RESPONSE_SIGNATURE);
        }
        if (requirements.wantAssertionSigned()) {
            Element signature = SamlXml.childElement(assertion, SamlXml.NS_SIGNATURE, "Signature");
            if (signature == null) {
                throw new SamlValidationException(REASON_ASSERTION_UNSIGNED);
            }
            verifyEnvelopedSignature(assertion, signature, requirements, REASON_ASSERTION_SIGNATURE);
        }

        requireIssuer(SamlXml.childElement(assertion, SamlXml.NS_ASSERTION, "Issuer"), requirements.idpEntityId());
        requireConditions(assertion, requirements, now, skew);
        return subjectOf(assertion, requirements, now, skew, response.getAttribute("InResponseTo"));
    }

    // ------------------------------------------------------------------
    // Signature verification: Santuario under strict semantics
    // ------------------------------------------------------------------

    /**
     * Verifies one enveloped signature under the wrap-defense and
     * algorithm-pinning rules: a single Reference carrying the
     * enclosing element's own {@code #ID}, pinned algorithms and
     * transforms, and a cryptographic verdict from one of the pinned
     * metadata certificates (never an embedded KeyInfo certificate).
     * The {@code legReason} names WHICH signature failed -- the audit
     * line distinguishes the Response leg from the Assertion leg.
     */
    private static void verifyEnvelopedSignature(
            Element enclosing, Element signatureElement, Requirements requirements, String legReason) {
        Element signedInfo = SamlXml.childElement(signatureElement, SamlXml.NS_SIGNATURE, "SignedInfo");
        if (signedInfo == null) {
            throw new SamlValidationException(legReason);
        }
        Element signatureMethod = SamlXml.childElement(signedInfo, SamlXml.NS_SIGNATURE, "SignatureMethod");
        String algorithm = signatureMethod == null ? null : signatureMethod.getAttribute("Algorithm");
        if (algorithm == null || !SIGNATURE_METHODS.contains(algorithm)) {
            throw new SamlValidationException(REASON_ALGORITHM);
        }
        List<Element> references = SamlXml.childElements(signedInfo, SamlXml.NS_SIGNATURE, "Reference");
        if (references.size() != 1) {
            // A SAML enveloped signature references exactly its own
            // element; more (or none) is a coverage trick.
            throw new SamlValidationException(legReason);
        }
        Element reference = references.getFirst();
        String uri = reference.getAttribute("URI");
        String id = enclosing.getAttribute("ID");
        if (!uri.equals("#" + id)) {
            // The signature must cover the element it lives in --
            // referencing a DIFFERENT element's id is the wrap attack.
            throw new SamlValidationException(legReason);
        }
        Element digestMethod = SamlXml.childElement(reference, SamlXml.NS_SIGNATURE, "DigestMethod");
        String digest = digestMethod == null ? null : digestMethod.getAttribute("Algorithm");
        if (digest == null || !DIGEST_METHODS.contains(digest)) {
            throw new SamlValidationException(REASON_ALGORITHM);
        }
        Element transformsElement = SamlXml.childElement(reference, SamlXml.NS_SIGNATURE, "Transforms");
        if (transformsElement == null) {
            throw new SamlValidationException(legReason);
        }
        for (Element transform : SamlXml.childElements(transformsElement, SamlXml.NS_SIGNATURE, "Transform")) {
            if (!TRANSFORMS.contains(transform.getAttribute("Algorithm"))) {
                throw new SamlValidationException(REASON_ALGORITHM);
            }
        }
        // Register the enclosing element's id so the reference resolves
        // to IT (never to a same-id imposter -- uniqueness already held).
        IdResolver.registerElementById(enclosing, enclosing.getAttributeNode("ID"));
        XMLSignature signature;
        try {
            signature = new XMLSignature(signatureElement, "");
        } catch (RuntimeException | XMLSecurityException e) {
            throw new SamlValidationException(legReason);
        }
        // Skip the embedded KeyInfo entirely: the pinned metadata
        // certificates are the only candidate verifiers.
        boolean verified = false;
        for (X509Certificate pinned : requirements.pinnedCertificates()) {
            try {
                if (signature.checkSignatureValue(pinned.getPublicKey())) {
                    verified = true;
                    break;
                }
            } catch (RuntimeException | XMLSignatureException e) {
                // A malformed signature structure surfaces here for
                // some keys; the next pinned certificate gets its turn.
            }
        }
        if (!verified) {
            throw new SamlValidationException(legReason);
        }
    }

    // ------------------------------------------------------------------
    // Assertion semantics: status, issuer, conditions, subject
    // ------------------------------------------------------------------

    private static void requireSuccessStatus(Element response) {
        Element status = SamlXml.childElement(response, SamlXml.NS_PROTOCOL, "Status");
        Element statusCode = status == null ? null : SamlXml.childElement(status, SamlXml.NS_PROTOCOL, "StatusCode");
        String code = statusCode == null ? null : statusCode.getAttribute("Value");
        if (!STATUS_SUCCESS.equals(code)) {
            throw new SamlValidationException(REASON_STATUS);
        }
    }

    private static void requireIssuer(Element issuer, String expectedEntityId) {
        String value = SamlXml.textOf(issuer);
        if (value == null || !value.equals(expectedEntityId)) {
            throw new SamlValidationException(REASON_ISSUER);
        }
    }

    private static void requireConditions(Element assertion, Requirements requirements, Instant now, Duration skew) {
        Element conditions = SamlXml.childElement(assertion, SamlXml.NS_ASSERTION, "Conditions");
        if (conditions == null) {
            throw new SamlValidationException(REASON_SUBJECT);
        }
        String notBefore = conditions.getAttribute("NotBefore");
        if (!notBefore.isBlank()) {
            Instant bound = parseTime(notBefore);
            if (now.plus(skew).isBefore(bound)) {
                throw new SamlValidationException(REASON_NOT_YET_VALID);
            }
        }
        String notOnOrAfter = conditions.getAttribute("NotOnOrAfter");
        if (notOnOrAfter.isBlank()) {
            throw new SamlValidationException(REASON_SUBJECT);
        }
        if (!parseTime(notOnOrAfter).isAfter(now.minus(skew))) {
            throw new SamlValidationException(REASON_EXPIRED);
        }
        boolean audienceOk = false;
        for (Element restriction : SamlXml.childElements(conditions, SamlXml.NS_ASSERTION, "AudienceRestriction")) {
            for (Element audience : SamlXml.childElements(restriction, SamlXml.NS_ASSERTION, "Audience")) {
                if (requirements.spEntityId().equals(SamlXml.textOf(audience))) {
                    audienceOk = true;
                }
            }
        }
        if (!audienceOk) {
            throw new SamlValidationException(REASON_AUDIENCE);
        }
    }

    /**
     * The Subject leg: the NameID the identity links on (transient
     * refused -- it is single-use by definition), the bearer
     * SubjectConfirmationData whose Recipient must be this deployment's
     * ACS, whose NotOnOrAfter must hold, and whose InResponseTo must
     * agree with the Response's own.
     */
    private static ValidatedAssertion subjectOf(
            Element assertion, Requirements requirements, Instant now, Duration skew, String responseInResponseTo) {
        Element subject = SamlXml.childElement(assertion, SamlXml.NS_ASSERTION, "Subject");
        if (subject == null) {
            throw new SamlValidationException(REASON_SUBJECT);
        }
        Element nameIdElement = SamlXml.childElement(subject, SamlXml.NS_ASSERTION, "NameID");
        String nameId = SamlXml.textOf(nameIdElement);
        if (nameId == null || nameId.length() > 320) {
            throw new SamlValidationException(REASON_NAMEID);
        }
        String nameIdFormat = nameIdElement == null ? "" : nameIdElement.getAttribute("Format");
        if (NAMEID_TRANSIENT.equals(nameIdFormat)) {
            throw new SamlValidationException(REASON_NAMEID);
        }
        boolean bearerOk = false;
        for (Element confirmation : SamlXml.childElements(subject, SamlXml.NS_ASSERTION, "SubjectConfirmation")) {
            if (!BEARER_CONFIRMATION.equals(confirmation.getAttribute("Method"))) {
                continue;
            }
            Element data = SamlXml.childElement(confirmation, SamlXml.NS_ASSERTION, "SubjectConfirmationData");
            if (data == null) {
                continue;
            }
            if (!requirements.acsUrl().equals(data.getAttribute("Recipient"))) {
                continue;
            }
            String dataExpiry = data.getAttribute("NotOnOrAfter");
            if (dataExpiry.isBlank() || !parseTime(dataExpiry).isAfter(now.minus(skew))) {
                continue;
            }
            String dataInResponseTo = data.getAttribute("InResponseTo");
            if (!dataInResponseTo.isBlank() && !dataInResponseTo.equals(responseInResponseTo)) {
                // The confirmation data names a request: the Response
                // must repeat the SAME id (SAML Profiles 4.1.4.2). A
                // blank Response-level value here is the stripped
                // replay shape -- the attribute rides OUTSIDE the
                // assertion's enveloped signature, so a captured
                // SP-initiated response shorn of it would otherwise
                // pass as IdP-initiated and mint a session. An honest
                // IdP-initiated response carries no InResponseTo
                // anywhere; an honest SP-initiated one repeats it on
                // both legs.
                throw new SamlValidationException(REASON_REQUEST_MISMATCH);
            }
            bearerOk = true;
        }
        if (!bearerOk) {
            throw new SamlValidationException(REASON_SUBJECT);
        }
        String email = null;
        String displayName = null;
        List<String> groups = new ArrayList<>();
        for (Element statement : SamlXml.childElements(assertion, SamlXml.NS_ASSERTION, "AttributeStatement")) {
            for (Element attribute : SamlXml.childElements(statement, SamlXml.NS_ASSERTION, "Attribute")) {
                String name = attribute.getAttribute("Name");
                List<String> values = new ArrayList<>();
                for (Element value : SamlXml.childElements(attribute, SamlXml.NS_ASSERTION, "AttributeValue")) {
                    String text = SamlXml.textOf(value);
                    if (text != null) {
                        values.add(text);
                    }
                }
                if (requirements.groupAttributeName() != null
                        && requirements.groupAttributeName().equals(name)) {
                    groups.addAll(values);
                }
                if (email == null && EMAIL_ATTRIBUTE_NAMES.contains(name) && !values.isEmpty()) {
                    email = values.getFirst();
                }
                if (displayName == null && NAME_ATTRIBUTE_NAMES.contains(name) && !values.isEmpty()) {
                    displayName = values.getFirst();
                }
            }
        }
        if (email == null && "urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress".equals(nameIdFormat)) {
            // An email-format NameID asserts the address directly
            // (Okta's and Workspace's common shape).
            email = nameId;
        }
        return new ValidatedAssertion(
                nameId, nameIdFormat, email, displayName, List.copyOf(groups), blankToNull(responseInResponseTo));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant parseTime(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            throw new SamlValidationException(REASON_MALFORMED);
        }
    }

    private SamlResponseValidator() {}
}
