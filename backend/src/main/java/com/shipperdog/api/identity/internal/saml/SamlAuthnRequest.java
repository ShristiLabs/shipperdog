package com.shipperdog.api.identity.internal.saml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds the SP-initiated AuthnRequest (S14-02): a minimal, spec-shaped
 * {@code samlp:AuthnRequest} targeted at the IdP's HTTP-Redirect
 * binding SingleSignOnService, DEFLATE-compressed and base64-encoded
 * per the Redirect binding rules. Issued with a persistent NameID
 * policy (the linking layer treats whatever the IdP asserts; transient
 * is refused at validation).
 *
 * <p>The redirect URL carries the opaque RelayState the ACS will
 * require back; the optional {@code SigAlg/Signature} pair appears only
 * when the deployment configured SP signing credentials.
 */
public final class SamlAuthnRequest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private SamlAuthnRequest() {}

    /** The unsigned AuthnRequest XML for the given flow targets. */
    public static String xml(String spEntityId, String acsUrl, String idpSsoLocation, String requestId) {
        return "<samlp:AuthnRequest xmlns:samlp=\""
                + SamlXml.NS_PROTOCOL
                + "\" xmlns:saml=\""
                + SamlXml.NS_ASSERTION
                + "\" ID=\""
                + requestId
                + "\" Version=\"2.0\" IssueInstant=\""
                + DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                + "\" Destination=\""
                + escape(idpSsoLocation)
                + "\" AssertionConsumerServiceURL=\""
                + escape(acsUrl)
                + "\" ProtocolBinding=\""
                + SamlMetadataParser.BINDING_POST
                + "\">"
                + "<saml:Issuer>"
                + escape(spEntityId)
                + "</saml:Issuer>"
                + "<samlp:NameIDPolicy AllowCreate=\"true\" Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\"/>"
                + "</samlp:AuthnRequest>";
    }

    /** A fresh request id ({@code _} + 160 bits of entropy, ID-format safe). */
    public static String newRequestId() {
        byte[] entropy = new byte[20];
        RANDOM.nextBytes(entropy);
        return "_" + URL_SAFE.encodeToString(entropy);
    }

    /** DEFLATE (raw) + base64 of the request XML -- the Redirect binding payload. */
    public static String encodeForRedirect(String requestXml) {
        try {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            DeflaterOutputStream deflater = new DeflaterOutputStream(compressed, new Deflater(Deflater.DEFLATED, true));
            deflater.write(requestXml.getBytes(StandardCharsets.UTF_8));
            deflater.close();
            return Base64.getEncoder().encodeToString(compressed.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the AuthnRequest could not be compressed", e);
        }
    }

    /** Reverses {@link #encodeForRedirect} (tests and inspection only). */
    public static String decodeFromRedirect(String encoded) {
        try {
            byte[] compressed = Base64.getDecoder().decode(encoded);
            var inflater = new java.util.zip.Inflater(true);
            var stream = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed), inflater);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            throw new IllegalArgumentException("the SAMLRequest payload is not redirect-binding encoded", e);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
